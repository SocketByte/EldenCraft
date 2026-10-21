package dev.eldencraft.bridge;

/**
 * Pixel geometry of the NPC dialogue box: a speaker header, wrapped lines and a skip hint. It
 * floats above the vanilla status rows, so it never covers health, armor, stamina or the hotbar.
 */
public record DialogueLayout(
    int x,
    int y,
    int width,
    int height,
    int textX,
    int textY,
    int textWidth,
    int headerY,
    int hintY,
    int step,
    int rows) {
  public static final int PADDING = 8;
  public static final int MAX_ROWS = 6;
  private static final int MAX_WIDTH = 340;

  /** Space kept free beneath the box for the status rows, hotbar and the interaction prompt. */
  private static final int BOTTOM_RESERVED = 96;

  /** Width available to wrapped text in a window of this GUI width. */
  public static int textWidth(int guiWidth) {
    return boxWidth(guiWidth) - PADDING * 2;
  }

  private static int boxWidth(int guiWidth) {
    return Math.max(32, Math.min(MAX_WIDTH, guiWidth - 32));
  }

  /**
   * @param lines wrapped text lines; the caller clips any beyond {@link #rows()}
   * @param header whether a speaker name row precedes the text
   */
  public static DialogueLayout create(
      int guiWidth, int guiHeight, int lineHeight, int lines, boolean header) {
    int step = lineHeight + 2;
    int headerHeight = header ? lineHeight + 4 : 0;
    int footer = lineHeight + 4;
    int room = Math.max(0, guiHeight - BOTTOM_RESERVED - 8 - PADDING * 2 - headerHeight - footer);
    int rows = Math.max(1, Math.min(Math.min(MAX_ROWS, Math.max(1, lines)), room / step));
    int width = boxWidth(guiWidth);
    int height = PADDING * 2 + headerHeight + rows * step + footer;
    int x = (guiWidth - width) / 2;
    int y = Math.max(4, guiHeight - BOTTOM_RESERVED - height);
    return new DialogueLayout(
        x,
        y,
        width,
        height,
        x + PADDING,
        y + PADDING + headerHeight,
        width - PADDING * 2,
        y + PADDING,
        y + height - PADDING - lineHeight,
        step,
        rows);
  }

  /**
   * Which page of a speech too long for the box is on screen. A page stays up for a reading time
   * proportional to its length; the last one stays until the line ends.
   */
  public static int pageAt(int[] pageCharacters, long elapsedMillis) {
    long remaining = Math.max(0, elapsedMillis);
    for (int page = 0; page < pageCharacters.length - 1; page++) {
      long reading = Math.max(1800, 65L * Math.max(0, pageCharacters[page]));
      if (remaining < reading) return page;
      remaining -= reading;
    }
    return Math.max(0, pageCharacters.length - 1);
  }

  public int rowY(int row) {
    return textY + row * step;
  }
}
