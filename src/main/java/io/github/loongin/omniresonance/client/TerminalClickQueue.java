// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.TerminalStorageRequest;
import java.util.HashSet;
import java.util.Set;

/** Client-owned bounded window of sent operation IDs. Results retire individual IDs; no serialization or replay. */
final class TerminalClickQueue {
    record Click(long resourceId, int slot, int button, boolean shift, boolean bulk) {
        Click(long resourceId, int slot, int button, boolean shift) {
            this(resourceId, slot, button, shift, false);
        }
    }

    private final Set<Long> pending = new HashSet<>();

    boolean offer(long sequence) {
        if (sequence <= 0) throw new IllegalArgumentException("Invalid operation sequence");
        return pending.size() < TerminalStorageRequest.MAX_PENDING && pending.add(sequence);
    }

    boolean contains(long sequence) {
        return pending.contains(sequence);
    }

    boolean finish(long sequence) {
        return pending.remove(sequence);
    }

    boolean busy() {
        return !pending.isEmpty();
    }

    void clear() {
        pending.clear();
    }
}
