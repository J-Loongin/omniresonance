// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayDeque;
import org.jetbrains.annotations.Nullable;

/** Client-owned, bounded explicit click intents. Starts each once; failures/close discard unsent clicks without retries. */
final class TerminalClickQueue {
    record Click(long resourceId, int slot, int button, boolean shift, boolean bulk) {
        Click(long resourceId, int slot, int button, boolean shift) {
            this(resourceId, slot, button, shift, false);
        }
    }

    private final ArrayDeque<Click> clicks = new ArrayDeque<>();
    private boolean active;

    boolean offer(Click click) {
        if (clicks.size() >= 16) return false;
        clicks.addLast(click);
        return true;
    }

    @Nullable
    Click start() {
        if (active || clicks.isEmpty()) return null;
        active = true;
        return clicks.removeFirst();
    }

    void finish(boolean success) {
        active = false;
        if (!success) clicks.clear();
    }

    void clear() {
        clicks.clear();
        active = false;
    }
}
