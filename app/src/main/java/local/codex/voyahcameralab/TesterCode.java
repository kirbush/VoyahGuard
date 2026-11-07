package local.codex.voyahcameralab;

/**
 * A tester's personal code for sending logs: 8 letters and digits, Crockford base32, all random
 * (40 bits). Pure logic. Case, spaces, dashes and the usual look-alikes (O for 0, I and L for 1)
 * are forgiven. The receiver knows the code only by its SHA-256 and limits guessing; the
 * receiver address comes with the build, not with the code.
 */
final class TesterCode {
  static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  static final int LENGTH = 8;

  final String secret;

  private TesterCode(String secret) { this.secret = secret; }

  /** Null when it is not a valid code. */
  static TesterCode parse(String text) {
    String code = normalize(text);
    return code == null || code.length() != LENGTH ? null : new TesterCode(code);
  }

  static String normalize(String text) {
    if (text == null) return null;
    StringBuilder out = new StringBuilder();
    for (char c : text.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
      if (c == ' ' || c == '-' || c == '_' || c == '\t') continue;
      if (c == 'O') c = '0';
      if (c == 'I' || c == 'L') c = '1';
      if (ALPHABET.indexOf(c) < 0 || out.length() >= 64) return null;
      out.append(c);
    }
    return out.toString();
  }
}
