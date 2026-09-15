// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec;
import io.github.loongin.omniresonance.storage.DomainLedger;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One client-thread requested stream; at most one bounded record is assembled beside the temporary mirror. */
final class DomainInventoryReceiver implements AutoCloseable {
    private final DomainInventoryMirror mirror = new DomainInventoryMirror();
    private @Nullable UUID session;
    private long generation;
    private long nextFrame;
    private long nextRecord;
    private boolean started;
    private boolean failed;
    private @Nullable byte[] record;
    private int offset;
    private boolean base;
    private int initialCount;
    private int baseCount;
    private long version;
    private @Nullable DomainLedger.Change lastChange;

    long version() {
        return version;
    }

    @Nullable
    DomainLedger.Change lastChange() {
        return lastChange;
    }

    boolean started() {
        return started;
    }

    private @Nullable DomainInventoryFrame.Reason failureReason;

    void request(UUID session, long generation) {
        if (generation <= 0) throw new IllegalArgumentException("Invalid inventory generation");
        close();
        this.session = Objects.requireNonNull(session);
        this.generation = generation;
    }

    boolean accept(DomainInventoryFrame frame) {
        lastChange = null;
        if (!Objects.equals(session, frame.session()) || generation != frame.generation() || failed) return false;
        if (frame.sequence() != nextFrame || nextFrame == Long.MAX_VALUE) return fail(null);
        nextFrame++;
        try {
            if (frame instanceof DomainInventoryFrame.Failed failure) return fail(failure.reason());
            if (frame instanceof DomainInventoryFrame.Begin begin) {
                if (started) return fail(null);
                started = true;
                initialCount = begin.initialCount();
                mirror.begin(session, generation, begin.ceiling());
                return true;
            }
            if (!started) return fail(null);
            if (frame instanceof DomainInventoryFrame.End end) {
                if (record != null || !mirror.finish(session, generation, nextRecord++, end.count(), end.revision()))
                    return fail(null);
                return true;
            }
            var data = (DomainInventoryFrame.Data) frame;
            if (record == null) {
                if (data.offset() != 0) return fail(null);
                record = new byte[data.totalLength()];
                offset = 0;
                base = data.base();
            }
            if (data.offset() != offset || data.totalLength() != record.length || data.base() != base)
                return fail(null);
            byte[] bytes = data.data();
            System.arraycopy(bytes, 0, record, offset, bytes.length);
            offset += bytes.length;
            if (offset == record.length) {
                var complete = DomainInventoryRecordCodec.decode(record);
                record = null;
                boolean accepted = base
                        ? mirror.base(
                                session,
                                generation,
                                nextRecord++,
                                new DomainLedger.Cursor(complete.sequence(), complete.key(), complete.amount()))
                        : mirror.change(session, generation, nextRecord++, complete);
                if (!accepted) return fail(null);
                if (base) baseCount++;
                lastChange = complete;
                version = Math.incrementExact(version);
            }
            return true;
        } catch (RuntimeException invalid) {
            return fail(null);
        }
    }

    DomainInventoryMirror mirror() {
        return mirror;
    }

    int assemblingBytes() {
        return record == null ? 0 : record.length;
    }

    int initialCount() {
        return initialCount;
    }

    int receivedBaseCount() {
        return baseCount;
    }

    @Nullable
    DomainInventoryFrame.Reason failureReason() {
        return failureReason;
    }

    private boolean fail(@Nullable DomainInventoryFrame.Reason reason) {
        failed = true;
        failureReason = reason;
        record = null;
        mirror.reject();
        return false;
    }

    @Override
    public void close() {
        mirror.close();
        session = null;
        generation = nextFrame = nextRecord = 0;
        started = failed = false;
        record = null;
        initialCount = baseCount = offset = 0;
        failureReason = null;
        lastChange = null;
        version = 0;
    }
}
