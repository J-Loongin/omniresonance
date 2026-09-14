// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread-owned single-download assembler, separate from the authenticated server reservation pool.
 * Holds at most one declared 16 MiB object, with a fixed 200-tick deadline. It never treats DOWNLOAD as UPLOAD.
 * The caller pins Expected from its current authorized menu response, routes only that session, and closes this
 * object on page leave, disconnect or server change. No authority, world state, simulation or background work.
 */
public final class ManagementDownloadAssembler implements AutoCloseable {
    /** Immutable independently pinned metadata; constructing it grants no permission and retains no bulk bytes. */
    public record Expected(
            UUID session,
            UUID transfer,
            ManagementTransferMessage.Context context,
            UUID contextId,
            ManagementTransferMessage.Purpose purpose,
            int totalLength) {
        public Expected {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(transfer, "transfer");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(purpose, "purpose");
            if (totalLength < 1 || totalLength > ManagementTransferPool.MAXIMUM_OBJECT_BYTES)
                throw new IllegalArgumentException("Invalid download length");
        }
    }

    private final Thread owner = Thread.currentThread();
    private @Nullable Expected expected;
    private byte @Nullable [] bytes;
    private int offset;
    private long expiresTick;
    private boolean inCallback;
    private boolean closed;

    /** Checks independently pinned context, direction and bounds before allocating one owned buffer. No replacement. */
    public void begin(ManagementTransferMessage.Begin begin, Expected pin, long nowTick) {
        mutation();
        tick(nowTick);
        Objects.requireNonNull(begin, "begin");
        Objects.requireNonNull(pin, "pin");
        if (begin.direction() != ManagementTransferMessage.Direction.DOWNLOAD
                || !pin.session().equals(begin.session())
                || !pin.transfer().equals(begin.transfer())
                || pin.context() != begin.context()
                || !pin.contextId().equals(begin.contextId())
                || pin.purpose() != begin.purpose()
                || pin.totalLength() != begin.totalLength())
            throw new IllegalArgumentException("Download does not match pinned context");
        if (closed || expected != null) throw new IllegalStateException("Download slot unavailable");
        long deadline = Math.addExact(nowTick, ManagementTransferPool.TIMEOUT_TICKS);
        byte[] allocated = new byte[pin.totalLength()];
        expected = pin;
        bytes = allocated;
        offset = 0;
        expiresTick = deadline;
    }

    /** Copies one ordered fragment. Stale identities return false; invalid current content aborts and throws. */
    public boolean append(ManagementTransferMessage.Chunk chunk, long nowTick) {
        mutation();
        if (!current(chunk.session(), chunk.transfer(), nowTick)) return false;
        byte[] fragment = chunk.data();
        if (chunk.offset() != offset || fragment.length > bytes.length - offset) {
            release();
            throw new IllegalArgumentException("Invalid download offset or length");
        }
        System.arraycopy(fragment, 0, bytes, offset, fragment.length);
        offset += fragment.length;
        return true;
    }

    /**
     * Complete current frames decode synchronously through a borrowed view. All exits invalidate the view and
     * release raw bytes, including decoder failure. Detached decoded policy DTOs may remain for the current page.
     * Stale identities cannot publish or cancel a newer download; incomplete current frames abort and throw.
     */
    public boolean finish(
            ManagementTransferMessage.Finish finish, long nowTick, Consumer<ManagementObjectView> decode) {
        mutation();
        if (!current(finish.session(), finish.transfer(), nowTick)) return false;
        View view = new View(bytes, owner);
        try {
            if (offset != bytes.length) throw new IllegalArgumentException("Incomplete download");
            inCallback = true;
            Objects.requireNonNull(decode, "decode").accept(view);
            return true;
        } finally {
            view.bytes = null;
            inCallback = false;
            release();
        }
    }

    /** Owner-thread exact cancellation; old sessions or transfer IDs cannot cancel the active object. */
    public boolean abort(UUID session, UUID transfer) {
        mutation();
        if (!matches(session, transfer)) return false;
        release();
        return true;
    }

    /** Fixed deadline sweep; chunk progress and editor heartbeats never renew the deadline. */
    public boolean expire(long nowTick) {
        mutation();
        tick(nowTick);
        if (expected == null || nowTick < expiresTick) return false;
        release();
        return true;
    }

    /** Current owned raw bytes on this thread, including during decoder callback scope. */
    public int reservedBytes() {
        thread();
        return bytes == null ? 0 : bytes.length;
    }

    /** Idempotent lifecycle close releases raw data and rejects subsequent admission; no authority is changed. */
    @Override
    public void close() {
        mutation();
        closed = true;
        release();
    }

    private boolean current(UUID session, UUID transfer, long nowTick) {
        tick(nowTick);
        if (!matches(session, transfer)) return false;
        if (nowTick >= expiresTick) {
            release();
            throw new IllegalStateException("Download expired");
        }
        return true;
    }

    private boolean matches(UUID session, UUID transfer) {
        return expected != null
                && expected.session().equals(session)
                && expected.transfer().equals(transfer);
    }

    private void release() {
        expected = null;
        bytes = null;
        offset = 0;
    }

    private void thread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Download accessed off owner thread");
    }

    private void mutation() {
        thread();
        if (inCallback) throw new IllegalStateException("Download callback reentry");
    }

    private static void tick(long nowTick) {
        if (nowTick < 0) throw new IllegalArgumentException("Negative game tick");
    }

    private static final class View implements ManagementObjectView {
        private byte @Nullable [] bytes;
        private final Thread owner;

        private View(byte[] bytes, Thread owner) {
            this.bytes = bytes;
            this.owner = owner;
        }

        @Override
        public int length() {
            return valid().length;
        }

        @Override
        public byte byteAt(int index) {
            return valid()[index];
        }

        private byte[] valid() {
            if (Thread.currentThread() != owner || bytes == null)
                throw new IllegalStateException("Expired or off-thread download view");
            return bytes;
        }
    }
}
