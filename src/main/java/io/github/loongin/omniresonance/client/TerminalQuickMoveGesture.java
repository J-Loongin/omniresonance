// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Client click timing only; the server derives the full item identity from the preceding authorized single move. */
final class TerminalQuickMoveGesture {
    private int slot = -1;
    private long lastMillis;

    boolean click(int index, int button, boolean shift, long nowMillis) {
        if (index < 0 || button != 0 || !shift) {
            clear();
            return false;
        }
        boolean bulk = slot == index && nowMillis >= lastMillis && nowMillis - lastMillis <= 250;
        slot = bulk ? -1 : index;
        lastMillis = nowMillis;
        return bulk;
    }

    void clear() {
        slot = -1;
    }
}
