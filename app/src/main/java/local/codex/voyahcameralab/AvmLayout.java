package local.codex.voyahcameralab;

/**
 * Fixed layout of the 1920×1200 AVM frame on H97X, all values confirmed on the car.
 * Views are the four 2D wide views selected by the OEM top-view buttons. Those buttons hide a
 * few seconds after a touch, so the selected view is read from the direction glyph that the
 * OEM UI always draws at the top of the camera panel: a car with a green arc on the active side.
 */
final class AvmLayout {
  private AvmLayout() {}
  static final int VIEW_UNKNOWN = -1, FRONT = 0, LEFT = 1, REAR = 2, RIGHT = 3, VIEWS = 4;
  /** Not a view: the toolbar touch that flips the OEM screen between 2D and 3D. */
  static final int TOGGLE_2D = 100;
  /**
   * 3D scenario. Buttons run clockwise from the one ahead of the car. Each puts the virtual camera
   * at that side looking through the transparent car, so it shows the opposite side.
   */
  static final int BUTTONS = 8, HOME_3D = 4;
  static final String[] WATCH_3D = {"зад", "зад-лево", "лево", "перед-лево", "перед", "перед-право", "право", "зад-право"};
  private static final int[][] BUTTON_CENTRES = {{440, 165}, {575, 342}, {600, 600}, {575, 855}, {440, 1037}, {303, 855}, {280, 600}, {303, 342}};
  private static final int BUTTON_HALF_X = 3, BUTTON_HALF_Y = 5, CAR_CENTRE_X = 440, CAR_CENTRE_Y = 600, HALF_WIDTH = 280, HALF_HEIGHT = 600;
  /** The four diagonal buttons exist only in 3D. */
  static final int[][] TOP_EXCLUDED_3D = {{258, 270, 348, 415}, {530, 270, 620, 415}, {258, 780, 348, 930}, {530, 780, 620, 930}};
  static final String[] NAMES = {"перед", "лево", "зад", "право"};

  static final int SOURCE_WIDTH = 1920, SOURCE_HEIGHT = 1200;
  static final int SAMPLE_WIDTH = 320, SAMPLE_HEIGHT = 200;
  /** The glyph is read at source resolution from extra rows under the frame in the same sample. */
  static final int GLYPH_X = 1240, GLYPH_Y = 0, GLYPH_WIDTH = 165, GLYPH_HEIGHT = 105;
  static final int BUFFER_HEIGHT = SAMPLE_HEIGHT + GLYPH_HEIGHT, BUFFER_BYTES = SAMPLE_WIDTH * BUFFER_HEIGHT * 4;
  private static final int SCALE = 1920 / SAMPLE_WIDTH;

  /** Composed top view strip, sample pixels. */
  static final int TOP_X0 = 160 / SCALE, TOP_X1 = 720 / SCALE;

  /** Where each view's green arc sits inside the glyph: x0, y0, x1, y1. */
  private static final int[][] ARCS = {{60, 17, 102, 36}, {48, 25, 66, 78}, {60, 60, 102, 80}, {98, 25, 116, 78}};
  private static final int MIN_ARC_PIXELS = 40;

  /** Rendered car outline and every OEM button, excluded from top-view motion. Source pixels. */
  static final int[][] TOP_EXCLUDED = {
      {355, 265, 525, 935}, {395, 90, 485, 240}, {240, 525, 330, 675}, {555, 525, 645, 675},
      {395, 960, 485, 1110}, {190, 60, 270, 185}};
  static final int CAR_LEFT = 355, CAR_RIGHT = 525, CAR_TOP = 265, CAR_BOTTOM = 935;

  static int sample(int sourcePixels) { return sourcePixels / SCALE; }
  static int source(double samplePixels) { return (int) (samplePixels * SCALE); }

  /** Source pixels per metre on the ground, from the outline of the 1.96 × 4.9 m car; the frame is anamorphic. */
  static final double ACROSS_PER_METRE = 87, ALONG_PER_METRE = 137;

  /** Source pixels within which a blob counts as reaching a masked button. */
  private static final int TOUCH = 12;

  /**
   * Ground distance of a blob from the body. The OEM buttons in the middle of each side hide the
   * ground from about 0.2 to 1.3 m out, so a blob that reaches one may stand anywhere under it: it
   * is taken at the middle of that hidden strip, between the two errors.
   */
  static double blobGapMetres(int x0, int y0, int x1, int y1, boolean threeD) {
    double gap = gapMetres(x0, y0, x1, y1);
    for (int i = 1; i < TOP_EXCLUDED.length; i++) gap = Math.min(gap, underButton(TOP_EXCLUDED[i], x0, y0, x1, y1));
    if (threeD) for (int[] button : TOP_EXCLUDED_3D) gap = Math.min(gap, underButton(button, x0, y0, x1, y1));
    return gap;
  }

  private static double underButton(int[] b, int x0, int y0, int x1, int y1) {
    if (x1 < b[0] - TOUCH || x0 > b[2] + TOUCH || y1 < b[1] - TOUCH || y0 > b[3] + TOUCH) return Double.POSITIVE_INFINITY;
    int x = (b[0] + b[2]) / 2, y = (b[1] + b[3]) / 2;
    return gapMetres(x, y, x, y);
  }

  /** Ground distance in metres between a box of the top view (source pixels) and the car outline; 0 when they touch. */
  static double gapMetres(int x0, int y0, int x1, int y1) {
    int across = Math.max(0, Math.max(CAR_LEFT - x1, x0 - CAR_RIGHT)), along = Math.max(0, Math.max(CAR_TOP - y1, y0 - CAR_BOTTOM));
    return Math.hypot(across / ACROSS_PER_METRE, along / ALONG_PER_METRE);
  }

  /** About 0.85 m of the body behind each bumper, up to the wheel arch: corners there belong to the front or rear view. */
  static final int CORNER = 120;

  /**
   * View for a top-view point. Seen on the car: somebody walking past the bumper threw a shadow into
   * the front corner and the view jumped to the side camera, so a side view now needs the person
   * beside the body, not ahead of it or behind it.
   */
  static int zone(int sourceX, int sourceY) {
    boolean beside = sourceX < CAR_LEFT || sourceX > CAR_RIGHT;
    if (sourceY < CAR_TOP + CORNER) return beside || sourceY < CAR_TOP ? FRONT : VIEW_UNKNOWN;
    if (sourceY > CAR_BOTTOM - CORNER) return beside || sourceY > CAR_BOTTOM ? REAR : VIEW_UNKNOWN;
    return beside ? (sourceX < CAR_LEFT ? LEFT : RIGHT) : VIEW_UNKNOWN;
  }

  /** Direction of a top-view point as one of eight sectors clockwise from ahead, or VIEW_UNKNOWN on the car. */
  static int sector(int sourceX, int sourceY) {
    if (sourceX >= CAR_LEFT && sourceX <= CAR_RIGHT && sourceY >= CAR_TOP && sourceY <= CAR_BOTTOM) return VIEW_UNKNOWN;
    double angle = Math.atan2((sourceX - CAR_CENTRE_X) / (double) HALF_WIDTH, (CAR_CENTRE_Y - sourceY) / (double) HALF_HEIGHT);
    return (int) Math.floor((angle + Math.PI / 8) / (Math.PI / 4) + BUTTONS) % BUTTONS;
  }

  /** The 3D button whose camera looks through the car at the given sector. */
  static int throughCar(int sector) { return (sector + BUTTONS / 2) % BUTTONS; }

  /** Which 3D button is lit, or VIEW_UNKNOWN: they hide a few seconds after a touch. */
  static int selectedButton(byte[] rgba) {
    if (rgba == null || rgba.length < BUFFER_BYTES) return VIEW_UNKNOWN;
    int found = VIEW_UNKNOWN;
    for (int button = 0; button < BUTTONS; button++) {
      if (!buttonBlue(rgba, sample(BUTTON_CENTRES[button][0]), sample(BUTTON_CENTRES[button][1]))) continue;
      if (found != VIEW_UNKNOWN) return VIEW_UNKNOWN;
      found = button;
    }
    return found;
  }

  private static boolean buttonBlue(byte[] rgba, int centreX, int centreY) {
    int blue = 0, total = 0;
    for (int y = centreY - BUTTON_HALF_Y; y <= centreY + BUTTON_HALF_Y; y++) {
      for (int x = centreX - BUTTON_HALF_X; x <= centreX + BUTTON_HALF_X; x++) {
        total++;
        if (x < 0 || y < 0 || x >= SAMPLE_WIDTH || y >= SAMPLE_HEIGHT) continue;
        int p = (y * SAMPLE_WIDTH + x) * 4;
        int r = rgba[p] & 255, g = rgba[p + 1] & 255, b = rgba[p + 2] & 255;
        if (b >= 110 && b - r >= 55 && g > r && b > g) blue++;
      }
    }
    return blue * 3 >= total;
  }

  /**
   * Which view the glyph shows, or VIEW_UNKNOWN unless one side clearly wins.
   * rgba is a top-down RGBA8888 buffer of SAMPLE_WIDTH×BUFFER_HEIGHT.
   */
  static int selectedView(byte[] rgba) {
    if (rgba == null || rgba.length < BUFFER_BYTES) return VIEW_UNKNOWN;
    int best = VIEW_UNKNOWN, bestCount = 0, second = 0;
    for (int view = 0; view < VIEWS; view++) {
      int count = greenPixels(rgba, ARCS[view]);
      if (count > bestCount) { second = bestCount; bestCount = count; best = view; }
      else if (count > second) second = count;
    }
    return bestCount >= MIN_ARC_PIXELS && bestCount >= second * 2 ? best : VIEW_UNKNOWN;
  }

  private static int greenPixels(byte[] rgba, int[] box) {
    int count = 0;
    for (int y = box[1]; y < box[3]; y++) {
      for (int x = box[0]; x < box[2]; x++) {
        int p = ((SAMPLE_HEIGHT + y) * SAMPLE_WIDTH + x) * 4;
        int r = rgba[p] & 255, g = rgba[p + 1] & 255, b = rgba[p + 2] & 255;
        if (g >= 80 && g - r >= 35 && g - b >= 15) count++;
      }
    }
    return count;
  }
}
