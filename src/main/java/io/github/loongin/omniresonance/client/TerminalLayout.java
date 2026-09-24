// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Pure terminal geometry independent of Minecraft rendering state. */
record TerminalLayout(Rect window, Rect titleBar, Rect content, boolean compact) {
    public static final int STANDARD_WIDTH = 440;
    public static final int STANDARD_HEIGHT = 260;
    private static final int TERMINAL_WIDTH = 380;
    private static final int TERMINAL_HEIGHT = 230;
    public static final int SCREEN_MARGIN = 8;
    public static final int TITLE_HEIGHT = 28;
    public static final int PADDING = 8;
    public static final int GAP = 6;
    public static final int SCROLLBAR_WIDTH = 6;

    public static TerminalLayout calculate(int screenWidth, int screenHeight) {
        int safeScreenWidth = Math.max(0, screenWidth);
        int safeScreenHeight = Math.max(0, screenHeight);
        int panelWidth = Math.min(STANDARD_WIDTH, Math.max(0, safeScreenWidth - SCREEN_MARGIN * 2));
        int panelHeight = Math.min(STANDARD_HEIGHT, Math.max(0, safeScreenHeight - SCREEN_MARGIN * 2));
        return centered(safeScreenWidth, safeScreenHeight, panelWidth, panelHeight);
    }

    private static TerminalLayout centered(int safeScreenWidth, int safeScreenHeight, int panelWidth, int panelHeight) {
        int left = Math.floorDiv(safeScreenWidth - panelWidth, 2);
        int top = Math.floorDiv(safeScreenHeight - panelHeight, 2);
        Rect window = new Rect(left, top, panelWidth, panelHeight);
        int titleHeight = Math.min(TITLE_HEIGHT, panelHeight);
        Rect titleBar = new Rect(left, top, panelWidth, titleHeight);
        int contentWidth = Math.max(0, panelWidth - PADDING * 2);
        int contentHeight = Math.max(0, panelHeight - titleHeight - PADDING * 2);
        Rect content = new Rect(
                left + Math.min(PADDING, panelWidth),
                top + Math.min(titleHeight + PADDING, panelHeight),
                contentWidth,
                contentHeight);
        return new TerminalLayout(window, titleBar, content, panelWidth < 400);
    }

    static TerminalLayout terminal(int screenWidth, int screenHeight) {
        var layout = centered(Math.max(0, screenWidth), Math.max(0, screenHeight), TERMINAL_WIDTH, TERMINAL_HEIGHT);
        // Terminal pages retain their fixed arrangement; window resizing never changes the home column count.
        return new TerminalLayout(layout.window(), layout.titleBar(), layout.content(), false);
    }

    /** Returns a stable inner width that reserves the scrollbar slot even when content currently fits. */
    public static int reservedScrollContentWidth(int panelWidth, int horizontalPadding) {
        if (panelWidth < 0 || horizontalPadding < 0) {
            throw new IllegalArgumentException("Scrollable geometry must be nonnegative");
        }
        return Math.max(0, panelWidth - horizontalPadding * 2 - SCROLLBAR_WIDTH);
    }

    /** Integer bounds whose width and height are always non-negative. */
    public record Rect(int x, int y, int width, int height) {
        public Rect {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Rectangle dimensions must be non-negative");
            }
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }
}
