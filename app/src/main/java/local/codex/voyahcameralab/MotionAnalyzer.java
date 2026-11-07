package local.codex.voyahcameralab;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;

/**
 * MOG2 foreground on the stitched top-view panorama, which stays on screen whichever camera is
 * selected. It covers only the area around the car, so a blob here means somebody is close.
 * The camera panel gets its own model: it reports the centres of blobs below the horizon, and
 * {@link CameraTracker} decides which of them travel. Single-threaded: owned by the analysis thread.
 */
final class MotionAnalyzer {
  // A dark figure on night asphalt is only a few gray levels off the background and the stitching
  // stretches it into a thin wedge. Replayed on a person standing at the bumper: seen in 29 of 32
  // seconds with these values against 7 with the MOG2 defaults, with no false events in silence.
  private static final int HISTORY = 300; // Slow enough to keep somebody standing still, about a minute.
  private static final double VAR_THRESHOLD = 16;
  private static final double DARK_TONE_GAMMA = 0.5;
  private static final int FOREGROUND = 255;
  private static final double WHOLE_SCENE = 0.35;
  /** Replayed on real walks: a person gives 50-900 samples, quiet night scenes stay at 0. */
  static final int MIN_BLOB = 30;
  // Camera panel, tuned on daytime recordings of a parking lot: people and cars far away pass,
  // windows, clouds and reflections above the horizon do not. Many blobs at once mean rain or snow.
  private static final double CAMERA_VAR_THRESHOLD = 25;
  static final int CAMERA_X0 = AvmLayout.sample(760), CAMERA_MIN_BLOB = 20, CAMERA_HORIZON = 66, CAMERA_NOISY = 30;

  static final class Reading {
    int largestBlob; boolean rejected; final boolean[] near;
    /** For the alarm, per zone (AvmLayout FRONT, LEFT, REAR, RIGHT): metres from the body to the nearest blob. */
    final double[] gaps = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    /** x0, y0, x1, y1, ... of camera-panel blobs worth following; empty when the sample is unusable. */
    double[] cameraCentres = new double[0];
    boolean cameraNoisy;
    Reading(int views) { near = new boolean[views]; }
  }

  private final Mat buffer = new Mat(AvmLayout.BUFFER_HEIGHT, AvmLayout.SAMPLE_WIDTH, CvType.CV_8UC4);
  private final Mat rgba = buffer.rowRange(0, AvmLayout.SAMPLE_HEIGHT);
  private final Mat gray = new Mat(), blurred = new Mat(), foreground = new Mat(), opened = new Mat();
  private final Mat labels = new Mat(), stats = new Mat(), centroids = new Mat();
  private final Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
  private final Rect topRect = new Rect(AvmLayout.TOP_X0, 0, AvmLayout.TOP_X1 - AvmLayout.TOP_X0, AvmLayout.SAMPLE_HEIGHT);
  private final Mat mask = new Mat(topRect.height, topRect.width, CvType.CV_8UC1, new Scalar(FOREGROUND));
  private final BackgroundSubtractorMOG2 model = Video.createBackgroundSubtractorMOG2(HISTORY, VAR_THRESHOLD, false);
  private final Rect cameraRect = new Rect(CAMERA_X0, 0, AvmLayout.SAMPLE_WIDTH - CAMERA_X0, AvmLayout.SAMPLE_HEIGHT);
  private final Mat cameraMask = new Mat(cameraRect.height, cameraRect.width, CvType.CV_8UC1, new Scalar(FOREGROUND));
  private final Mat cameraLifted = new Mat(), cameraForeground = new Mat();
  private final BackgroundSubtractorMOG2 cameraModel = Video.createBackgroundSubtractorMOG2(HISTORY, CAMERA_VAR_THRESHOLD, false);
  private boolean relearnCamera;
  private final Mat lifted = new Mat(), darkTones = new Mat(1, 256, CvType.CV_8UC1);

  private final boolean threeD;

  MotionAnalyzer(boolean threeD) {
    this.threeD = threeD;
    byte[] table = new byte[256];
    for (int level = 0; level < table.length; level++) table[level] = (byte) Math.round(255 * Math.pow(level / 255.0, DARK_TONE_GAMMA));
    darkTones.put(0, 0, table);
    for (int[] box : AvmLayout.TOP_EXCLUDED) exclude(box);
    // The OEM direction glyph at the top of the panel changes with the view; it is not motion.
    int glyphX = AvmLayout.sample(AvmLayout.GLYPH_X) - CAMERA_X0;
    Imgproc.rectangle(cameraMask, new Point(glyphX, 0), new Point(glyphX + AvmLayout.sample(AvmLayout.GLYPH_WIDTH), AvmLayout.sample(AvmLayout.GLYPH_HEIGHT)), new Scalar(0), -1);
    if (threeD) for (int[] box : AvmLayout.TOP_EXCLUDED_3D) exclude(box);
  }

  private void exclude(int[] box) {
    int x0 = Math.max(0, AvmLayout.sample(box[0]) - AvmLayout.TOP_X0), x1 = AvmLayout.sample(box[2]) - AvmLayout.TOP_X0;
    Imgproc.rectangle(mask, new Point(x0, AvmLayout.sample(box[1])), new Point(x1, AvmLayout.sample(box[3])), new Scalar(0), -1);
  }

  /** The view that would show a point of the panorama, or VIEW_UNKNOWN on the car itself. */
  private int viewFor(int sourceX, int sourceY) {
    if (!threeD) return AvmLayout.zone(sourceX, sourceY);
    int sector = AvmLayout.sector(sourceX, sourceY);
    return sector == AvmLayout.VIEW_UNKNOWN ? sector : AvmLayout.throughCar(sector);
  }

  Reading process(byte[] frame) {
    buffer.put(0, 0, frame);
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
    Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
    Mat top = blurred.submat(topRect);
    Core.LUT(top, darkTones, lifted);
    top.release();
    model.apply(lifted, foreground);
    Core.bitwise_and(foreground, mask, foreground);
    Imgproc.morphologyEx(foreground, opened, Imgproc.MORPH_OPEN, kernel);
    int count = Imgproc.connectedComponentsWithStats(opened, labels, stats, centroids, 8, CvType.CV_32S), total = 0;
    Reading reading = new Reading(threeD ? AvmLayout.BUTTONS : AvmLayout.VIEWS);
    int[] area = new int[1], box = new int[4]; double[] centre = new double[2];
    for (int label = 1; label < count; label++) {
      stats.get(label, Imgproc.CC_STAT_AREA, area);
      total += area[0];
      reading.largestBlob = Math.max(reading.largestBlob, area[0]);
      if (area[0] < MIN_BLOB) continue;
      centroids.get(label, 0, centre);
      int x = AvmLayout.source(centre[0] + AvmLayout.TOP_X0), y = AvmLayout.source(centre[1]), view = viewFor(x, y);
      if (view != AvmLayout.VIEW_UNKNOWN) reading.near[view] = true;
      stats.get(label, 0, box);
      int x0 = AvmLayout.source(box[0] + AvmLayout.TOP_X0), y0 = AvmLayout.source(box[1]);
      int zone = AvmLayout.zone(x, y); // the top view is the same in 2D and 3D
      if (zone != AvmLayout.VIEW_UNKNOWN) reading.gaps[zone] = Math.min(reading.gaps[zone], AvmLayout.blobGapMetres(x0, y0, x0 + AvmLayout.source(box[2]), y0 + AvmLayout.source(box[3]), threeD));
    }
    if (total > topRect.width * topRect.height * WHOLE_SCENE) { // exposure or lighting change, not an object
      reading.rejected = true;
      java.util.Arrays.fill(reading.gaps, Double.POSITIVE_INFINITY);
      java.util.Arrays.fill(reading.near, false);
    }
    camera(reading);
    return reading;
  }

  private void camera(Reading reading) {
    Mat panel = blurred.submat(cameraRect);
    Core.LUT(panel, darkTones, cameraLifted);
    panel.release();
    cameraModel.apply(cameraLifted, cameraForeground, relearnCamera ? 1 : -1); // 1 takes this picture as the whole background
    relearnCamera = false;
    Core.bitwise_and(cameraForeground, cameraMask, cameraForeground);
    Imgproc.morphologyEx(cameraForeground, opened, Imgproc.MORPH_OPEN, kernel);
    int count = Imgproc.connectedComponentsWithStats(opened, labels, stats, centroids, 8, CvType.CV_32S), total = 0;
    double[] centres = new double[2 * Math.max(0, count - 1)];
    int found = 0;
    int[] box = new int[5]; double[] centre = new double[2];
    for (int label = 1; label < count; label++) {
      stats.get(label, 0, box); // left, top, width, height, area
      total += box[4];
      if (box[4] < CAMERA_MIN_BLOB || box[1] + box[3] < CAMERA_HORIZON) continue;
      centroids.get(label, 0, centre);
      centres[found++] = centre[0];
      centres[found++] = centre[1];
    }
    reading.cameraNoisy = count - 1 > CAMERA_NOISY || total > cameraRect.area() * WHOLE_SCENE;
    reading.cameraCentres = reading.cameraNoisy ? new double[0] : java.util.Arrays.copyOf(centres, found);
  }

  /** The panel shows another camera: what the model learnt is no background any more. */
  void resetCamera() { relearnCamera = true; }

  void release() {
    for (Mat mat : new Mat[]{rgba, buffer, gray, blurred, lifted, darkTones, foreground, opened, labels, stats, centroids, kernel, mask, cameraMask, cameraLifted, cameraForeground}) mat.release();
  }
}
