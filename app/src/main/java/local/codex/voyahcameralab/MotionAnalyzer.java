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
 * The camera panel is never analysed. Single-threaded: owned by the analysis thread.
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

  static final class Reading {
    int largestBlob; boolean rejected; final boolean[] near;
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
  private final Mat lifted = new Mat(), darkTones = new Mat(1, 256, CvType.CV_8UC1);

  private final boolean threeD;

  MotionAnalyzer(boolean threeD) {
    this.threeD = threeD;
    byte[] table = new byte[256];
    for (int level = 0; level < table.length; level++) table[level] = (byte) Math.round(255 * Math.pow(level / 255.0, DARK_TONE_GAMMA));
    darkTones.put(0, 0, table);
    for (int[] box : AvmLayout.TOP_EXCLUDED) exclude(box);
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
    int[] area = new int[1]; double[] centre = new double[2];
    for (int label = 1; label < count; label++) {
      stats.get(label, Imgproc.CC_STAT_AREA, area);
      total += area[0];
      reading.largestBlob = Math.max(reading.largestBlob, area[0]);
      if (area[0] < MIN_BLOB) continue;
      centroids.get(label, 0, centre);
      int view = viewFor(AvmLayout.source(centre[0] + AvmLayout.TOP_X0), AvmLayout.source(centre[1]));
      if (view != AvmLayout.VIEW_UNKNOWN) reading.near[view] = true;
    }
    if (total > topRect.width * topRect.height * WHOLE_SCENE) { // exposure or lighting change, not an object
      reading.rejected = true;
      java.util.Arrays.fill(reading.near, false);
    }
    return reading;
  }

  void release() {
    for (Mat mat : new Mat[]{rgba, buffer, gray, blurred, lifted, darkTones, foreground, opened, labels, stats, centroids, kernel, mask}) mat.release();
  }
}
