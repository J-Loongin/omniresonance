// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.ManagementTransferPool;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

/** Client-thread byte ownership and ordered fragmentation for one management upload. Protocol authorization,
 * acknowledgements, deadlines and commit decisions belong to the caller. No packet sends or retries occur here. */
final class ClientUploadBuffer {
    record Fragment(int offset, byte[] bytes) {}

    private byte @Nullable [] body;
    private int offset;

    /** Takes exclusive ownership of freshly encoded bytes until clear; the caller must not modify them. */
    void begin(byte[] bytes) {
        if (active()) throw new IllegalStateException("Upload already active");
        if (bytes.length == 0 || bytes.length > ManagementTransferPool.MAXIMUM_OBJECT_BYTES)
            throw new IllegalArgumentException("Invalid upload length");
        body = bytes;
        offset = 0;
    }

    boolean active() {
        return body != null;
    }

    boolean hasNext() {
        return body != null && offset < body.length;
    }

    /** Returns a detached fragment and advances once. Exhaustion is not server acknowledgement or commit. */
    Fragment next() {
        if (!hasNext()) throw new IllegalStateException("No upload fragment available");
        int start = offset;
        int end = start + Math.min(ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES, body.length - start);
        byte[] fragment = Arrays.copyOfRange(body, start, end);
        offset = end;
        return new Fragment(start, fragment);
    }

    void clear() {
        body = null;
        offset = 0;
    }
}
