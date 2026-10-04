// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Connection-owned cosmetic rotation; continuation never advances the next colour. */
final class HighlightColourCycle {
    private static final int[] COLOURS = {0xE8A1AF, 0xB1A3DA, 0x9CCBD9, 0xDEC99A};
    private int next;
    private int current = COLOURS[0];

    int begin(boolean continuation) {
        if (!continuation) {
            current = COLOURS[next];
            next = (next + 1) % COLOURS.length;
        }
        return current;
    }

    void reset() {
        next = 0;
        current = COLOURS[0];
    }
}
