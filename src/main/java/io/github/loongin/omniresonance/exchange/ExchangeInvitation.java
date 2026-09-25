// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable invitation lifecycle, safe on any thread. This is not the shareable code or its lookup store.
 * Reads and transitions do no I/O, simulation or resource mutation. Callers must use authoritative metadata,
 * atomically publish approval/consumption together, and never treat knowing an invitation ID as authorization.
 */
public record ExchangeInvitation(
        UUID id, UUID targetNetwork, UUID targetOwner, long issuedTick, long expiresTick, long revision, State state) {
    public static final long LIFETIME_TICKS = 12000;

    public enum State {
        OPEN,
        CONSUMED,
        REVOKED
    }

    /** Validates exact ten-minute game-time lifetime; invalid identities, revisions or overflow reject construction. */
    public ExchangeInvitation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(targetNetwork);
        Objects.requireNonNull(targetOwner);
        Objects.requireNonNull(state);
        if (issuedTick < 0 || revision < 0 || expiresTick != Math.addExact(issuedTick, LIFETIME_TICKS))
            throw new IllegalArgumentException("Invalid exchange invitation lifetime or revision");
    }

    /** Pure owner-only issuance. The caller generates the identity; overflow fails before a value is returned. */
    public static ExchangeInvitation issue(UUID id, UUID actor, NetworkMetadata target, long tick) {
        Objects.requireNonNull(actor);
        if (!target.ownerId().equals(actor))
            throw new SecurityException("Only the receiving owner may issue invitations");
        return new ExchangeInvitation(
                id, target.id(), target.ownerId(), tick, Math.addExact(tick, LIFETIME_TICKS), 0, State.OPEN);
    }

    /** Pure time/state check, not an authority grant. Expiration is exclusive and a regressed clock cannot admit use. */
    public boolean usable(long tick) {
        if (tick < 0) throw new IllegalArgumentException("Negative invitation tick");
        return state == State.OPEN && tick >= issuedTick && tick < expiresTick;
    }

    /**
     * Consumes a matching, live invitation only after both sides approved. Returns a new value; failure leaves this
     * invitation intact. The caller must recheck both networks and atomically store agreement plus consumption.
     */
    public ExchangeInvitation consumeAfterApproval(
            UUID actor, NetworkMetadata currentTarget, long expectedRevision, long tick, ExchangeConsent consent) {
        authorize(actor, currentTarget, expectedRevision);
        Objects.requireNonNull(consent);
        if (!id.equals(consent.invitationId())
                || !targetNetwork.equals(consent.targetNetwork())
                || !targetOwner.equals(consent.targetOwner()))
            throw new IllegalArgumentException("Approval belongs to another invitation");
        if (!usable(tick) || !consent.approvedByBoth())
            throw new IllegalStateException("Invitation or approval is not usable");
        return transition(State.CONSUMED);
    }

    /** Pure owner revocation of an open invitation. A consumed code cannot revoke an already approved agreement. */
    public ExchangeInvitation revoke(UUID actor, NetworkMetadata currentTarget, long expectedRevision) {
        authorize(actor, currentTarget, expectedRevision);
        if (state != State.OPEN) throw new IllegalStateException("Invitation is already closed");
        return transition(State.REVOKED);
    }

    private ExchangeInvitation transition(State next) {
        return new ExchangeInvitation(
                id, targetNetwork, targetOwner, issuedTick, expiresTick, Math.incrementExact(revision), next);
    }

    private void authorize(UUID actor, NetworkMetadata currentTarget, long expectedRevision) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(currentTarget);
        if (!targetNetwork.equals(currentTarget.id())
                || !targetOwner.equals(currentTarget.ownerId())
                || !targetOwner.equals(actor)) throw new SecurityException("Invitation receiving owner changed");
        if (revision != expectedRevision) throw new IllegalStateException("Stale exchange invitation");
    }
}
