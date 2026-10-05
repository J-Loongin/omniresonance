// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.ManagementTransferMessage;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import java.util.UUID;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/** One client-connection-owned save, independent of screens. At most one bounded immutable policy/body is
 * retained; completion, timeout or logout clears it. Never retries a modification or predicts its result. */
final class NodePolicySaves {
    record Completion(NodeMenuResponse response, boolean uncertain) {}

    private final NodePolicyUpload upload = new NodePolicyUpload();
    private @Nullable UUID session;
    private int container;
    private long sequence, ticks, deadlineTicks;
    private boolean finishSent;

    boolean active() {
        return session != null;
    }

    boolean belongsTo(int container, UUID session) {
        return this.container == container && session.equals(this.session);
    }

    void begin(
            int container, UUID session, long sequence, @Nullable UUID transfer, @Nullable ResourcePolicyEdit policy) {
        if (active()) throw new IllegalStateException("A node save is already awaiting its result");
        if (transfer != null) upload.begin(transfer, java.util.Objects.requireNonNull(policy), ticks);
        this.container = container;
        this.session = java.util.Objects.requireNonNull(session);
        this.sequence = sequence;
        deadlineTicks = Math.addExact(ticks, 200);
        finishSent = transfer == null;
    }

    @Nullable
    Completion receive(NodeMenuResponse response) {
        if (!belongsTo(response.containerId(), response.sessionId()) || response.sequence() != sequence) return null;
        if (response instanceof NodeMenuResponse.UploadReady ready) {
            upload.ready(ready.transfer());
            return null;
        }
        if (!(response instanceof NodeMenuResponse.State) && !(response instanceof NodeMenuResponse.Failure))
            return null;
        boolean uncertain = response instanceof NodeMenuResponse.Failure failure
                && failure.reason() == NodeMenuResponse.Reason.INTERNAL_ERROR;
        clear();
        return new Completion(response, uncertain);
    }

    void tick(Consumer<ManagementTransferMessage> sender, Consumer<Completion> completed) {
        ticks++;
        if (!active()) return;
        if (ticks >= deadlineTicks) {
            var abort = upload.id() != null && !finishSent
                    ? new ManagementTransferMessage.Abort(session, upload.id())
                    : null;
            var result = failed();
            if (abort != null) {
                try {
                    sender.accept(abort);
                } catch (RuntimeException failure) {
                    logTransportFailure(failure);
                }
            }
            completed.accept(result);
            return;
        }
        if (upload.hasNext()) {
            try {
                var part = upload.next();
                sender.accept(new ManagementTransferMessage.Chunk(session, upload.id(), part.offset(), part.bytes()));
                if (!upload.hasNext()) {
                    // A send can throw after enqueueing bytes, so the final result remains unknown.
                    finishSent = true;
                    sender.accept(new ManagementTransferMessage.Finish(session, upload.id()));
                }
            } catch (RuntimeException failure) {
                logTransportFailure(failure);
                completed.accept(failed());
            }
        }
    }

    Completion failed() {
        var result = new Completion(
                new NodeMenuResponse.Failure(
                        container,
                        java.util.Objects.requireNonNull(session),
                        sequence,
                        NodeMenuResponse.Reason.UNAVAILABLE,
                        null),
                finishSent);
        clear();
        return result;
    }

    private static void logTransportFailure(RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(NodePolicySaves.class)
                .warn("Node save transport failed; no automatic retry", failure);
    }

    void clear() {
        upload.clear();
        session = null;
        sequence = deadlineTicks = 0;
        finishSent = false;
    }
}
