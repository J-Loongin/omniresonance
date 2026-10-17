// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Client-thread pointer gesture; stores only the last physical cell, never inventory or pending mutations. */
final class TerminalQuickMoveDrag {
    static final long OUTSIDE = Long.MIN_VALUE;
    private boolean active;
    private long previous = OUTSIDE;

    void begin(long position, int button, boolean emptyCursor) {
        active = button == 0 && emptyCursor;
        previous = position;
    }

    boolean enter(long position, int button, boolean shift, boolean emptyCursor) {
        if (button != 0 || !emptyCursor) clear();
        if (!active || position == previous) return false;
        previous = position;
        return position != OUTSIDE && shift;
    }

    boolean active() {
        return active;
    }

    void clear() {
        active = false;
        previous = OUTSIDE;
    }
}
