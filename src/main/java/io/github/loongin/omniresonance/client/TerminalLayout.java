// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Pure terminal geometry independent of Minecraft rendering state. */
record TerminalLayout(Rect window, Rect titleBar, Rect content, boolean compact) {
    public static final int TITLE_HEIGHT = 28;
    public static final int PADDING = 8;
    public static final int GAP = 6;
    public static final int SCROLLBAR_WIDTH = 6;

    public static TerminalLayout calculate(int screenWidth, int screenHeight) {
        int safeScreenWidth = Math.max(0, screenWidth);
        int safeScreenHeight = Math.max(0, screenHeight);
        int panelWidth = Math.min(720, Math.max(304, (int) Math.floor(safeScreenWidth * 0.9D)));
        int panelHeight = Math.min(420, Math.max(216, (int) Math.floor(safeScreenHeight * 0.9D)));
        int left = Math.floorDiv(safeScreenWidth - panelWidth, 2);
        int top = Math.floorDiv(safeScreenHeight - panelHeight, 2);
        Rect window = new Rect(left, top, panelWidth, panelHeight);
        Rect titleBar = new Rect(left, top, panelWidth, TITLE_HEIGHT);
        int contentWidth = Math.max(0, panelWidth - PADDING * 2);
        int contentHeight = Math.max(0, panelHeight - TITLE_HEIGHT - PADDING * 2);
        Rect content = new Rect(left + PADDING, top + TITLE_HEIGHT + PADDING, contentWidth, contentHeight);
        return new TerminalLayout(window, titleBar, content, panelWidth < 400);
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
