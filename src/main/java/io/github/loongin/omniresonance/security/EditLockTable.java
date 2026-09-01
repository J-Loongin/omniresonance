// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.security;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-owned, in-memory edit coordination for one server session.
 *
 * <p>Construct and invoke this table on its owning server thread; every public instance operation rejects other
 * threads with {@link IllegalStateException} before accessing state. Inputs remain caller-owned and are not retained
 * except for immutable UUIDs and tokens. There is no simulation mode or persistence. This table does not authorize
 * access, validate object existence or revisions, or establish a sender's identity. The service must supply the actual
 * connection sender and current authoritative permission checks before using it.
 *
 * <p>The object UUID index and deadline index each contain exactly one entry per retained lease, including expired
 * leases awaiting cleanup. Capacity follows current editing sessions, not heartbeat history; only authorized existing
 * objects may be admitted by the service, never unchecked client UUIDs. Expiry, replacement, release, logout and server
 * stop remove entries. Checks take O(1) per object; renewal and release take O(log n). Expiry visits only due entries.
 *
 * <p>Required null inputs throw {@link NullPointerException}; negative ticks throw {@link IllegalArgumentException}.
 * Deadline or generation overflow throws {@link ArithmeticException} before changing either index or the generation.
 */
public final class EditLockTable {
    private static final long EDIT_LOCK_TIMEOUT_TICKS = 200;

    private final Thread ownerThread;
    private final HashMap<UUID, Lease> locks = new HashMap<>();
    private final TreeSet<Lease> expirations = new TreeSet<>(Comparator.comparingLong(Lease::expiresAtTick)
            .thenComparingLong(lease -> lease.token().generation()));
    private long generation;

    /**
     * Creates an empty, non-persistent table owned exclusively by the calling server thread. No external state is
     * accessed or modified; subsequent instance calls must remain on this thread.
     */
    public EditLockTable() {
        ownerThread = Thread.currentThread();
    }

    /**
     * Immutable edit identity, safe to pass across threads but meaningful only against its issuing table and session.
     * Possession is not permission. Tokens contain no caller-controlled deadline and expose no mutable state or
     * simulation behavior.
     *
     * @param objectId the edited object's identity
     * @param playerId the holder's identity
     * @param generation the positive, table-issued generation
     */
    public record Token(UUID objectId, UUID playerId, long generation) {
        /**
         * Constructs an immutable value without accessing or mutating table state. Null UUIDs throw
         * {@link NullPointerException}; a nonpositive generation throws {@link IllegalArgumentException}.
         */
        public Token {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(playerId, "playerId");
            if (generation <= 0) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    /**
     * Acquires an unheld or expired object on the owning thread, returning a caller-owned immutable token.
     * An active lease returns empty even for the same player and remains unchanged. Successful acquisition replaces
     * any expired entry; invalid inputs or arithmetic overflow leave all state unchanged. This is mutation, not
     * simulation or access authorization.
     */
    public Optional<Token> tryAcquire(UUID objectId, UUID playerId, long nowTick) {
        checkThread();
        checkTick(nowTick);
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(playerId, "playerId");
        Lease existing = locks.get(objectId);
        if (existing != null && existing.expiresAtTick() > nowTick) {
            return Optional.empty();
        }
        long nextGeneration = Math.incrementExact(generation);
        long expiresAtTick = Math.addExact(nowTick, EDIT_LOCK_TIMEOUT_TICKS);
        Token token = new Token(objectId, playerId, nextGeneration);
        Lease acquired = new Lease(token, expiresAtTick);
        if (existing != null) {
            expirations.remove(existing);
        }
        locks.put(objectId, acquired);
        expirations.add(acquired);
        generation = nextGeneration;
        return Optional.of(token);
    }

    /**
     * Replaces a live lease's deadline with {@code nowTick + 200} on the owning thread. The service schedules
     * heartbeats every 40 ticks. Only an exact token and matching actual sender can renew; false leaves state unchanged.
     * Inputs are immutable and remain caller-owned. Invalid inputs or overflow fail before mutation; no simulation or
     * permission check is performed.
     */
    public boolean renew(Token token, UUID sender, long nowTick) {
        checkThread();
        checkTick(nowTick);
        Lease existing = matchingLease(token, sender);
        if (existing == null || existing.expiresAtTick() <= nowTick) {
            return false;
        }
        long expiresAtTick = Math.addExact(nowTick, EDIT_LOCK_TIMEOUT_TICKS);
        Lease renewed = new Lease(existing.token(), expiresAtTick);
        expirations.remove(existing);
        locks.put(token.objectId(), renewed);
        expirations.add(renewed);
        return true;
    }

    /**
     * Checks a caller-owned token and actual sender on the owning thread without renewing, removing, or simulating
     * state. Returns false for expired, missing or mismatched leases; invalid inputs throw as documented on the class.
     * This check alone does not authorize a save.
     */
    public boolean isHeld(Token token, UUID sender, long nowTick) {
        checkThread();
        checkTick(nowTick);
        Lease lease = matchingLease(token, sender);
        return lease != null && lease.expiresAtTick() > nowTick;
    }

    /**
     * Removes the exact matching lease on the owning thread for save, cancel or close. Returns false without mutation
     * for a missing or mismatched lease; an expired but retained matching lease can also be removed. Inputs remain
     * caller-owned, null inputs throw, and this cleanup neither simulates nor authorizes a save.
     */
    public boolean release(Token token, UUID sender) {
        checkThread();
        Lease lease = matchingLease(token, sender);
        if (lease == null) {
            return false;
        }
        locks.remove(token.objectId());
        expirations.remove(lease);
        return true;
    }

    /**
     * Removes all of a disconnected player's leases on the owning thread, leaving other players untouched. The
     * caller-owned UUID must be nonnull. This mutating logout operation scans O(n) entries and removes k deadlines in
     * O(k log n); it is not a per-tick scan, simulation, or permission operation.
     */
    public void releasePlayer(UUID playerId) {
        checkThread();
        Objects.requireNonNull(playerId, "playerId");
        Iterator<Lease> iterator = locks.values().iterator();
        while (iterator.hasNext()) {
            Lease lease = iterator.next();
            if (lease.token().playerId().equals(playerId)) {
                iterator.remove();
                expirations.remove(lease);
            }
        }
    }

    /**
     * Clears both owned indexes on the owning thread for server stop, retaining the generation so old tokens cannot
     * revive if this instance is reused. Mutates only table state, performs no simulation, and rejects other threads.
     */
    public void clear() {
        checkThread();
        locks.clear();
        expirations.clear();
    }

    /**
     * Checks affected objects on the owning thread without changing either index or any deadline. A live lease
     * conflicts unless its full token equals the caller-owned allowed token; sharing a player is not sufficient.
     * A null allowed token exempts nothing. The caller retains the collection, which must contain nonnull UUIDs.
     * Invalid inputs throw as documented on the class; no simulation or authorization is performed.
     */
    public boolean hasConflictingLocks(Collection<UUID> objectIds, @Nullable Token allowedToken, long nowTick) {
        checkThread();
        checkTick(nowTick);
        Objects.requireNonNull(objectIds, "objectIds");
        for (UUID objectId : objectIds) {
            Objects.requireNonNull(objectId, "objectId");
            Lease lease = locks.get(objectId);
            if (lease != null
                    && lease.expiresAtTick() > nowTick
                    && !lease.token().equals(allowedToken)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes only deadlines at or before the supplied tick on the owning thread. This mutates both owned indexes in
     * O(k log n) for k due leases, without scanning live entries. Negative ticks throw before mutation; no simulation
     * or external state access is performed.
     */
    public void expire(long nowTick) {
        checkThread();
        checkTick(nowTick);
        while (!expirations.isEmpty() && expirations.first().expiresAtTick() <= nowTick) {
            Lease expired = expirations.pollFirst();
            locks.remove(expired.token().objectId(), expired);
        }
    }

    private @Nullable Lease matchingLease(Token token, UUID sender) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(sender, "sender");
        if (!token.playerId().equals(sender)) {
            return null;
        }
        Lease lease = locks.get(token.objectId());
        return lease != null && lease.token().equals(token) ? lease : null;
    }

    private void checkThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Edit locks must be accessed on their owning server thread");
        }
    }

    private static void checkTick(long nowTick) {
        if (nowTick < 0) {
            throw new IllegalArgumentException("nowTick must be nonnegative");
        }
    }

    private record Lease(Token token, long expiresAtTick) {}
}
