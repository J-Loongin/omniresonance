// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Clockwise pixel path on the exact external stroke produced by the window's chamfered fills. */
final class TerminalWindowOutline {
    record Point(double x, double y) {}

    private TerminalWindowOutline() {}

    static double length(TerminalLayout.Rect rect, int cut) {
        int corner = Math.max(0, Math.min(cut, Math.min(rect.width(), rect.height()) / 2));
        return 2.0 * (rect.width() + rect.height()) - 4.0 * corner;
    }

    static Point point(TerminalLayout.Rect rect, int cut, double distance) {
        int c = Math.max(0, Math.min(cut, Math.min(rect.width(), rect.height()) / 2));
        double total = length(rect, c);
        if (total <= 0) return new Point(rect.x(), rect.y());
        int remaining = Math.floorMod((int) Math.floor(distance), (int) total);
        int horizontal = rect.width() - 2 * c;
        int vertical = rect.height() - 2 * c;
        for (int segment = 0; segment < 8; segment++) {
            int size = segment % 2 == 1 ? c : segment % 4 == 0 ? horizontal : vertical;
            if (remaining < size) {
                return switch (segment) {
                    case 0 -> new Point(rect.x() + c + remaining, rect.y() - 1);
                    case 1 -> new Point(rect.right() - c + remaining, rect.y() + remaining);
                    case 2 -> new Point(rect.right(), rect.y() + c + remaining);
                    case 3 -> new Point(rect.right() - 1 - remaining, rect.bottom() - c + remaining);
                    case 4 -> new Point(rect.right() - c - 1 - remaining, rect.bottom());
                    case 5 -> new Point(rect.x() + c - 1 - remaining, rect.bottom() - 1 - remaining);
                    case 6 -> new Point(rect.x() - 1, rect.bottom() - c - 1 - remaining);
                    default -> new Point(rect.x() + remaining, rect.y() + c - 1 - remaining);
                };
            }
            remaining -= size;
        }
        return new Point(rect.x() + c, rect.y() - 1);
    }
}
