// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Client-thread lifecycle of one node-policy upload. The screen validates session and request sequence before
 * authorizing ready or accepting completion. No sends, retries, or optimistic commits occur here. */
final class NodePolicyUpload {
    private enum Stage {
        IDLE,
        WAITING_READY,
        SENDING,
        WAITING_RESULT
    }

    private Stage stage = Stage.IDLE;
    private @Nullable UUID id;
    private @Nullable ResourcePolicyEdit policy;
    private final ClientUploadBuffer bytes = new ClientUploadBuffer();
    private long deadlineTicks;

    void begin(UUID transfer, ResourcePolicyEdit edit, long clientTicks) {
        if (active()) throw new IllegalStateException("Node policy upload already active");
        long deadline = Math.addExact(clientTicks, 200);
        Objects.requireNonNull(transfer);
        Objects.requireNonNull(edit);
        id = transfer;
        policy = edit;
        deadlineTicks = deadline;
        stage = Stage.WAITING_READY;
    }

    void ready(UUID transfer) {
        if (stage != Stage.WAITING_READY || !transfer.equals(id)) return;
        bytes.begin(ResourcePolicyEditCodec.encode(Objects.requireNonNull(policy)));
        policy = null;
        stage = Stage.SENDING;
    }

    boolean active() {
        return stage != Stage.IDLE;
    }

    @Nullable
    UUID id() {
        return id;
    }

    boolean expired(long clientTicks) {
        return active() && clientTicks >= deadlineTicks;
    }

    boolean hasNext() {
        return stage == Stage.SENDING && bytes.hasNext();
    }

    ClientUploadBuffer.Fragment next() {
        if (!hasNext()) throw new IllegalStateException("Node policy upload is not sending");
        var fragment = bytes.next();
        if (!bytes.hasNext()) {
            bytes.clear();
            stage = Stage.WAITING_RESULT;
        }
        return fragment;
    }

    void clear() {
        bytes.clear();
        id = null;
        policy = null;
        deadlineTicks = 0;
        stage = Stage.IDLE;
    }
}
