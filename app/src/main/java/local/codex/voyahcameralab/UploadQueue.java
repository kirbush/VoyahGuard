package local.codex.voyahcameralab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Durable upload-only queue, ported from the FM app. A report is copied in, sent by its SHA-256
 * and dropped only after the receiver confirmed exactly those bytes. Unsent reports are never
 * pushed out by newer ones; a full queue refuses the new report instead.
 */
final class UploadQueue {
  static final long MAX_REPORT = 16L * 1024 * 1024, MAX_QUEUE = 64L * 1024 * 1024;
  static final int MAX_PENDING = 32, MAX_RECEIPTS = 128;
  private static final String HASH = "[0-9a-f]{64}";
  /** A whole request, however slowly the server drips its answer. */
  private static final long REQUEST_DEADLINE_S = 60;
  private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "guard-upload-deadline"); t.setDaemon(true); return t; });

  private final File directory;
  private final String version;
  private volatile String error = "";

  UploadQueue(File directory, String version) throws IOException {
    this.directory = directory;
    this.version = version;
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("QUEUE_UNAVAILABLE");
    for (File f : list()) if (f.getName().matches("\\.[a-zA-Z0-9-]+\\.partial")) f.delete();
  }

  synchronized boolean enqueue(File report) throws IOException {
    if (!report.isFile() || !report.getName().matches(DiagnosticReport.NAME) || report.length() < 1 || report.length() > MAX_REPORT) { error = "REPORT_SIZE_OR_NAME"; return false; }
    String hash = hash(report);
    File target = file(hash, ".zip");
    if (file(hash, ".receipt.json").isFile() || target.isFile()) return true;
    long total = 0;
    int count = 0;
    for (File f : pendingFiles()) { total += f.length(); count++; }
    if (count >= MAX_PENDING || total + report.length() > MAX_QUEUE) { error = "QUEUE_FULL"; return false; }
    File temporary = new File(directory, "." + UUID.randomUUID() + ".partial");
    try {
      try (InputStream in = new FileInputStream(report); FileOutputStream out = new FileOutputStream(temporary)) { copy(in, out, MAX_REPORT); out.getFD().sync(); }
      if (!hash.equals(hash(temporary))) throw new IOException("REPORT_CHANGED");
      atomic(file(hash, ".name"), report.getName());
      if (!temporary.renameTo(target)) throw new IOException("QUEUE_WRITE_FAILED");
      return true;
    } finally { temporary.delete(); }
  }

  /** At most five requests per pass; the first network or receipt failure defers the rest. */
  synchronized int flush(String endpoint, TesterCode code) {
    error = "";
    int sent = 0, attempted = 0;
    long deadline = System.nanoTime() + 60_000_000_000L;
    for (File report : pendingFiles()) {
      if (attempted++ >= 5 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) break;
      String hash = report.getName().substring(0, 64);
      try {
        String name = readBounded(file(hash, ".name"), 256);
        if (!name.matches(DiagnosticReport.NAME) || report.length() < 1 || report.length() > MAX_REPORT || !hash.equals(hash(report))) throw new IOException("QUEUE_ENTRY_INVALID");
        String response = put(endpoint, code, report, hash, name);
        ack(response, hash, report.length());
        atomic(file(hash, ".receipt.json"), response);
        if (!report.delete()) throw new IOException("QUEUE_CLEANUP_FAILED");
        file(hash, ".name").delete();
        sent++;
      } catch (Exception failure) {
        String message = failure.getMessage();
        error = failure instanceof IOException && message != null && message.matches("(?:HTTP_[0-9]{3}|QUEUE_[A-Z_]+|RECEIPT_INVALID)") ? message : "UPLOAD_DEFERRED";
        break;
      }
    }
    pruneReceipts();
    return sent;
  }

  int pending() { return pendingFiles().length; }
  String lastError() { return error; }

  private File file(String hash, String suffix) { return new File(directory, hash + suffix); }
  private File[] list() { File[] files = directory.listFiles(); return files == null ? new File[0] : files; }

  private File[] pendingFiles() {
    List<File> files = new ArrayList<>();
    for (File f : list()) if (f.isFile() && f.getName().matches(HASH + "\\.zip")) files.add(f);
    File[] result = files.toArray(new File[0]);
    Arrays.sort(result, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
    return result;
  }

  private void pruneReceipts() {
    List<File> files = new ArrayList<>();
    for (File f : list()) if (f.isFile() && f.getName().matches(HASH + "\\.receipt\\.json")) files.add(f);
    files.sort(Comparator.comparingLong(File::lastModified).reversed());
    for (int i = MAX_RECEIPTS; i < files.size(); i++) files.get(i).delete();
  }

  private String put(String endpoint, TesterCode code, File report, String hash, String name) throws IOException {
    HttpsURLConnection request = (HttpsURLConnection) new URL(endpoint + "/reports/" + hash).openConnection();
    ScheduledFuture<?> deadline = DEADLINES.schedule(request::disconnect, REQUEST_DEADLINE_S, TimeUnit.SECONDS);
    try {
      request.setInstanceFollowRedirects(false);
      request.setConnectTimeout(15000);
      request.setReadTimeout(20000);
      request.setUseCaches(false);
      request.setRequestMethod("PUT");
      request.setDoOutput(true);
      request.setFixedLengthStreamingMode((int) report.length());
      request.setRequestProperty("Content-Type", "application/zip");
      request.setRequestProperty("Accept", "application/json");
      request.setRequestProperty("Authorization", "Bearer " + code.secret);
      request.setRequestProperty("X-Guard-Source", "headunit");
      request.setRequestProperty("X-Guard-Version", version);
      request.setRequestProperty("X-Guard-Report-Name", name);
      try (InputStream in = new FileInputStream(report); OutputStream out = request.getOutputStream()) { copy(in, out, MAX_REPORT); }
      int status = request.getResponseCode();
      if (status != 200 && status != 201) throw new IOException("HTTP_" + status);
      try (InputStream in = request.getInputStream()) { return readBounded(in, 8192); }
    } finally { deadline.cancel(false); request.disconnect(); }
  }

  /** The receipt names the tester the server knows; the app only checks it is about these bytes. */
  private static void ack(String response, String hash, long size) throws IOException {
    try {
      JSONTokener tokens = new JSONTokener(response);
      JSONObject a = new JSONObject(tokens);
      if (tokens.nextClean() != 0) throw new IOException();
      Object bytes = a.get("bytes"), id = a.get("id");
      if (!Boolean.TRUE.equals(a.get("ok")) || !hash.equals(a.get("sha256")) || !(id instanceof String) || !((String) id).matches("[a-zA-Z0-9_-]{1,64}/" + hash)
          || (!(bytes instanceof Integer) && !(bytes instanceof Long)) || ((Number) bytes).longValue() != size) throw new IOException();
    } catch (Exception | StackOverflowError ignored) { throw new IOException("RECEIPT_INVALID"); } // deeply nested JSON overflows the parser
  }

  static String readBounded(File file, int max) throws IOException { try (InputStream in = new FileInputStream(file)) { return readBounded(in, max); } }
  static String readBounded(InputStream in, int max) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); copy(in, out, max); return out.toString("UTF-8"); }

  static void copy(InputStream in, OutputStream out, long max) throws IOException {
    byte[] b = new byte[8192];
    long total = 0;
    int n;
    while ((n = in.read(b)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      total += n;
      if (total > max) throw new IOException("SIZE_LIMIT");
      out.write(b, 0, n);
    }
  }

  private static void atomic(File target, String content) throws IOException {
    File temp = new File(target.getParentFile(), "." + UUID.randomUUID() + ".partial");
    try {
      try (FileOutputStream out = new FileOutputStream(temp)) { out.write(content.getBytes("UTF-8")); out.getFD().sync(); }
      if (!temp.renameTo(target)) throw new IOException("QUEUE_WRITE_FAILED");
    } finally { temp.delete(); }
  }

  static String hash(File file) throws IOException {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new FileInputStream(file)) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) d.update(b, 0, n);
      }
      StringBuilder out = new StringBuilder();
      for (byte x : d.digest()) out.append(String.format(Locale.US, "%02x", x & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA256_UNAVAILABLE"); }
  }
}
