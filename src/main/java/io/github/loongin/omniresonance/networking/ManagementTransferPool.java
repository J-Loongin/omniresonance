// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Connection-authenticated, creator-thread-owned transient transfer storage; not a permission service.
 * Callers authenticate the actual connection, current menu/session, purpose, role and object relation
 * before admission. There is one slot per player across both directions and no asynchronous work or
 * simulation. Entries reserve their full declared size before allocation, have a fixed 200-tick
 * lifetime, and are removed on completion, failure, cancellation, expiry or close. Callback reentry
 * into mutations is rejected so callbacks cannot release their own reservation prematurely.
 */
public final class ManagementTransferPool implements AutoCloseable {
    public static final int MAXIMUM_OBJECT_BYTES = 16777216;
    public static final long MAXIMUM_SERVER_BYTES = 67108864;
    public static final int MAXIMUM_FRAGMENT_BYTES = 262144 - 1024;
    public static final long TIMEOUT_TICKS = 200;
    private final Thread owner = Thread.currentThread();
    private final Map<UUID, Entry> active = new HashMap<>();
    private long reservedBytes;
    private boolean closed;
    private boolean inCallback;

    /**
     * Reserves before invoking the synchronous factory. Its result must exactly match length and is
     * copied once for isolation; the caller retains its array. The temporary copy is bounded by one
     * object, not a fragment queue. All failures release the reservation and propagate. No world or
     * business authorization is performed; only this pool's owner thread may call this method.
     */
    public void beginDownload(
            UUID player, UUID session, UUID transfer, int length, long nowTick, Supplier<byte[]> factory) {
        Objects.requireNonNull(factory, "factory");
        Entry entry = reserve(player, session, transfer, length, nowTick, false);
        try {
            inCallback = true;
            byte[] generated = Objects.requireNonNull(factory.get(), "factory bytes");
            if (generated.length != length) throw new IllegalArgumentException("Declared length differs from object");
            entry.bytes = generated.clone();
        } catch (RuntimeException | Error failure) {
            release(player, entry);
            throw failure;
        } finally {
            inCallback = false;
        }
    }

    /** Reserves and allocates on the owner thread; admission failures leave other transfers unchanged. */
    public void beginUpload(UUID player, UUID session, UUID transfer, int length, long nowTick) {
        Entry entry = reserve(player, session, transfer, length, nowTick, true);
        try {
            entry.bytes = new byte[length];
        } catch (RuntimeException | Error failure) {
            release(player, entry);
            throw failure;
        }
    }

    /**
     * Copies one strictly ordered, nonempty fragment on the owner thread. Invalid content for this
     * transfer aborts it; wrong player/session/transfer IDs never abort a different transfer.
     */
    public void upload(UUID player, UUID session, UUID transfer, int offset, byte[] bytes, long nowTick) {
        Entry entry = require(player, session, transfer, nowTick);
        if (!entry.upload
                || bytes == null
                || offset != entry.offset
                || bytes.length == 0
                || bytes.length > MAXIMUM_FRAGMENT_BYTES
                || bytes.length > entry.length - entry.offset) {
            release(player, entry);
            throw new IllegalArgumentException("Invalid upload fragment or direction");
        }
        System.arraycopy(bytes, 0, entry.bytes, entry.offset, bytes.length);
        entry.offset += bytes.length;
    }

    /**
     * Only complete uploads reach validation. On the owner thread, validates the whole scoped view,
     * reauthorizes against current authoritative state, then commits synchronously. Validation and authorization must not mutate authoritative state. The reservation
     * remains held throughout; all exits invalidate the view and release it. Callbacks must not retain
     * decoded bulk data or dispatch background work. A thrown commit is never retried or guessed.
     */
    public void finishUpload(
            UUID player,
            UUID session,
            UUID transfer,
            long nowTick,
            Consumer<WholeObject> validator,
            BooleanSupplier authorize,
            Consumer<WholeObject> commit) {
        Entry entry = require(player, session, transfer, nowTick);
        WholeObject object = new WholeObject(entry.bytes, owner);
        try {
            if (!entry.upload || entry.offset != entry.length)
                throw new IllegalArgumentException("Incomplete upload or wrong direction");
            Objects.requireNonNull(validator, "validator");
            Objects.requireNonNull(authorize, "authorize");
            Objects.requireNonNull(commit, "commit");
            inCallback = true;
            validator.accept(object);
            if (!authorize.getAsBoolean()) throw new IllegalStateException("Management authorization expired");
            commit.accept(object);
        } finally {
            object.bytes = null;
            inCallback = false;
            release(player, entry);
        }
    }

    /**
     * Returns one independent array on demand on the owner thread; no fragments are precomputed.
     * The final fragment releases the reservation immediately (local delivery, not remote ACK).
     * Transport failure must call abort/disconnect; delivered fragments cannot mutate pool storage.
     */
    public byte[] nextDownload(UUID player, UUID session, UUID transfer, long nowTick) {
        Entry entry = require(player, session, transfer, nowTick);
        try {
            if (entry.upload) throw new IllegalArgumentException("Wrong transfer direction");
            int end = entry.offset + Math.min(MAXIMUM_FRAGMENT_BYTES, entry.length - entry.offset);
            byte[] fragment = Arrays.copyOfRange(entry.bytes, entry.offset, end);
            entry.offset = end;
            if (end == entry.length) release(player, entry);
            return fragment;
        } catch (RuntimeException | Error failure) {
            release(player, entry);
            throw failure;
        }
    }

    /** Owner-thread idempotent cancellation of exactly the identified transfer; no authority mutation. */
    public boolean abort(UUID player, UUID session, UUID transfer) {
        checkMutation();
        Entry entry = active.get(player);
        if (entry == null || !entry.session.equals(session) || !entry.transfer.equals(transfer)) return false;
        release(player, entry);
        return true;
    }

    /** Owner-thread leave-page/session cancellation; an old session cannot cancel the current one. */
    public void cancelSession(UUID player, UUID session) {
        checkMutation();
        Entry entry = active.get(player);
        if (entry != null && entry.session.equals(session)) release(player, entry);
    }

    /** Owner-thread connection teardown; safe repeatedly, with no payload-supplied player authority. */
    public void disconnect(UUID player) {
        checkMutation();
        Entry entry = active.get(player);
        if (entry != null) release(player, entry);
    }

    /** Owner-thread tick sweep. There is no heartbeat/progress renewal and expired entries cannot revive. */
    public void expire(long nowTick) {
        checkMutation();
        checkTick(nowTick);
        Iterator<Map.Entry<UUID, Entry>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (nowTick >= entry.expiresTick) {
                reservedBytes -= entry.length;
                entry.bytes = null;
                iterator.remove();
            }
        }
    }

    /** Owner-thread read-only diagnostic of declared reservations, also available during callbacks. */
    public long reservedBytes() {
        checkThread();
        return reservedBytes;
    }

    /** Owner-thread idempotent shutdown. Releases all storage and permanently prevents admission. */
    @Override
    public void close() {
        checkMutation();
        closed = true;
        for (Entry entry : active.values()) entry.bytes = null;
        active.clear();
        reservedBytes = 0;
    }

    private Entry reserve(UUID player, UUID session, UUID transfer, int length, long nowTick, boolean upload) {
        checkMutation();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(transfer, "transfer");
        checkTick(nowTick);
        if (length <= 0 || length > MAXIMUM_OBJECT_BYTES)
            throw new IllegalArgumentException("Invalid managed object length");
        long expiresTick = Math.addExact(nowTick, TIMEOUT_TICKS);
        long next = Math.addExact(reservedBytes, length);
        if (closed || active.containsKey(player) || next > MAXIMUM_SERVER_BYTES)
            throw new IllegalStateException("Management capacity unavailable");
        Entry entry = new Entry(session, transfer, length, expiresTick, upload);
        active.put(player, entry);
        reservedBytes = next;
        return entry;
    }

    private Entry require(UUID player, UUID session, UUID transfer, long nowTick) {
        checkMutation();
        checkTick(nowTick);
        Entry entry = active.get(player);
        if (entry == null || !entry.session.equals(session) || !entry.transfer.equals(transfer))
            throw new IllegalStateException("Unknown management transfer");
        if (nowTick >= entry.expiresTick) {
            release(player, entry);
            throw new IllegalStateException("Expired management transfer");
        }
        return entry;
    }

    private void release(UUID player, Entry entry) {
        if (active.remove(player, entry)) {
            reservedBytes -= entry.length;
            entry.bytes = null;
        }
    }

    private void checkMutation() {
        checkThread();
        if (inCallback) throw new IllegalStateException("Management callback mutation reentry");
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Management transfer used off owner thread");
    }

    private static void checkTick(long nowTick) {
        if (nowTick < 0) throw new IllegalArgumentException("Negative game tick");
    }

    private static final class Entry {
        final UUID session;
        final UUID transfer;
        final int length;
        final long expiresTick;
        final boolean upload;
        int offset;
        byte @Nullable [] bytes;

        Entry(UUID session, UUID transfer, int length, long expiresTick, boolean upload) {
            this.session = session;
            this.transfer = transfer;
            this.length = length;
            this.expiresTick = expiresTick;
            this.upload = upload;
        }
    }

    /**
     * Borrowed read-only whole-object view, usable only synchronously during completion callbacks on
     * the owning thread. No backing-array accessor exists. It is invalidated even on callback failure;
     * reads outside its scope throw instead of exposing unbudgeted retained storage.
     */
    public static final class WholeObject implements ManagementObjectView {
        private byte @Nullable [] bytes;
        private final Thread owner;

        private WholeObject(byte[] bytes, Thread owner) {
            this.bytes = bytes;
            this.owner = owner;
        }

        /** Returns the complete object length while in callback scope, without mutation or allocation. */
        @Override
        public int length() {
            return requireBytes().length;
        }

        /** Reads a byte in callback scope; invalid indices or expired scope throw without mutation. */
        @Override
        public byte byteAt(int index) {
            return requireBytes()[index];
        }

        private byte[] requireBytes() {
            if (Thread.currentThread() != owner || bytes == null)
                throw new IllegalStateException("Expired or off-thread managed object view");
            return bytes;
        }
    }
}
