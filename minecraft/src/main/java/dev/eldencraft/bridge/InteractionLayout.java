package dev.eldencraft.bridge;

/** Screen bounds shared by input and drawing, including high GUI scale and long choice lists. */
public record InteractionLayout(int x, int y, int width, int height, int rows, int bodyHeight) {
  public static InteractionLayout create(int screenWidth, int screenHeight, int choices) {
    return create(screenWidth, screenHeight, choices, 0, 9);
  }

  public static InteractionLayout create(
      int screenWidth, int screenHeight, int choices, int textLines, int lineHeight) {
    int width = Math.max(1, Math.min(320, screenWidth - 16));
    int bodyHeight =
        textLines <= 0
            ? 0
            : Math.max(
                0,
                Math.min(
                    Math.min(textLines, 10) * Math.max(1, lineHeight) + 8, screenHeight - 100));
    int rows =
        Math.max(
            1, Math.min(Math.max(1, choices), Math.max(1, (screenHeight - 102 - bodyHeight) / 24)));
    int height = Math.min(Math.max(1, screenHeight - 16), 66 + rows * 24 + bodyHeight);
    return new InteractionLayout(
        Math.max(0, (screenWidth - width) / 2),
        Math.max(0, (screenHeight - height) / 2),
        width,
        height,
        rows,
        bodyHeight);
  }

  public int rowY(int row) {
    return y + 30 + bodyHeight + row * 24;
  }

  public int pageCount(int choices) {
    return Math.max(1, (choices + rows - 1) / rows);
  }
}
