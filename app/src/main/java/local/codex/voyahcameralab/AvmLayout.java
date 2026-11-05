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
  static final int TOGGLE_2D = 4;
  static final String[] NAMES = {"перед", "лево", "зад", "право"};

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

  /** Nearest view for a top-view point; the corners belong to whichever axis is further out. */
  static int zone(int sourceX, int sourceY) {
    int dx = sourceX < CAR_LEFT ? CAR_LEFT - sourceX : sourceX > CAR_RIGHT ? sourceX - CAR_RIGHT : 0;
    int dy = sourceY < CAR_TOP ? CAR_TOP - sourceY : sourceY > CAR_BOTTOM ? sourceY - CAR_BOTTOM : 0;
    if (dx == 0 && dy == 0) return VIEW_UNKNOWN;
    // 1920×1200 is anamorphic: a source pixel covers 5/3 less height than width on the ground.
    if (dx * 5 >= dy * 3) return sourceX < CAR_LEFT ? LEFT : RIGHT;
    return sourceY < CAR_TOP ? FRONT : REAR;
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
