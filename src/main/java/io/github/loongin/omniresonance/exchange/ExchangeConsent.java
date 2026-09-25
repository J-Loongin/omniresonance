// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.security.NetworkPermissions;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, thread-safe consent state, with no world access, simulation or resource mutation. Callers supply
 * current authoritative metadata, publish successful transitions atomically, and separately validate sessions,
 * invitation admission, policy snapshots and storage. Exceptions leave the original value unchanged.
 */
public record ExchangeConsent(
        UUID invitationId,
        UUID sourceNetwork,
        UUID targetNetwork,
        UUID sourceOwner,
        UUID targetOwner,
        long revision,
        long termsRevision,
        Approval source,
        Approval target,
        boolean revoked) {
    public enum Side {
        SOURCE,
        TARGET
    }

    public record Approval(boolean approved, boolean paused) {}

    /** Pure validation of a caller-owned snapshot; negative or inconsistent revisions and self-exchange are rejected. */
    public ExchangeConsent {
        Objects.requireNonNull(invitationId);
        Objects.requireNonNull(sourceNetwork);
        Objects.requireNonNull(targetNetwork);
        Objects.requireNonNull(sourceOwner);
        Objects.requireNonNull(targetOwner);
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        if (sourceNetwork.equals(targetNetwork) || termsRevision < 0 || revision < termsRevision)
            throw new IllegalArgumentException("Invalid exchange consent identity or revision");
    }

    /** Pure source-owner proposal. Target approval is always explicit, including when both owners are the same. */
    public static ExchangeConsent propose(UUID invitation, UUID actor, NetworkMetadata source, NetworkMetadata target) {
        Objects.requireNonNull(actor);
        if (!source.ownerId().equals(actor)) throw new SecurityException("Only the source owner may propose exchange");
        return new ExchangeConsent(
                invitation,
                source.id(),
                target.id(),
                source.ownerId(),
                target.ownerId(),
                0,
                0,
                new Approval(true, false),
                new Approval(false, false),
                false);
    }

    /** Pure approval check for this terms revision, independent of manual pause flags. */
    public boolean approvedByBoth() {
        return !revoked && source.approved() && target.approved();
    }

    /** Pure consent gate using current identities; callers must still check policy, admission, storage and budgets. */
    public boolean permitsExecution(NetworkMetadata currentSource, NetworkMetadata currentTarget) {
        return approvedByBoth()
                && !source.paused()
                && !target.paused()
                && matches(Side.SOURCE, currentSource)
                && matches(Side.TARGET, currentTarget);
    }

    /** Returns owner approval of current terms. Stale or revoked transitions fail; repeated current approval is a no-op. */
    public ExchangeConsent approve(Side side, UUID actor, NetworkMetadata current, long expectedRevision) {
        authorize(side, actor, current, expectedRevision, true);
        var old = approval(side);
        return old.approved() ? this : replace(side, new Approval(true, old.paused()));
    }

    /** Returns a new terms revision signed only by its proposing owner, retaining both manual pause flags. */
    public ExchangeConsent revise(Side proposer, UUID actor, NetworkMetadata current, long expectedRevision) {
        authorize(proposer, actor, current, expectedRevision, true);
        return new ExchangeConsent(
                invitationId,
                sourceNetwork,
                targetNetwork,
                sourceOwner,
                targetOwner,
                Math.incrementExact(revision),
                Math.incrementExact(termsRevision),
                new Approval(proposer == Side.SOURCE, source.paused()),
                new Approval(proposer == Side.TARGET, target.paused()),
                false);
    }

    /**
     * Pure direction reversal for a paired channel. Revalidates the old signing side, invalidates the other
     * owner's approval and keeps pause flags attached to networks, not positional labels. The store must validate
     * pair membership before publishing; this transition neither pairs networks nor moves resources.
     */
    public ExchangeConsent reverse(Side proposer, UUID actor, NetworkMetadata current, long expectedRevision) {
        authorize(proposer, actor, current, expectedRevision, true);
        return new ExchangeConsent(
                invitationId,
                targetNetwork,
                sourceNetwork,
                targetOwner,
                sourceOwner,
                Math.incrementExact(revision),
                Math.incrementExact(termsRevision),
                new Approval(proposer == Side.TARGET, target.paused()),
                new Approval(proposer == Side.SOURCE, source.paused()),
                false);
    }

    /** Returns a pause of this side only; current owners or administrators may pause. No external state is changed. */
    public ExchangeConsent pause(Side side, UUID actor, NetworkMetadata current, long expectedRevision) {
        authorize(side, actor, current, expectedRevision, false);
        var old = approval(side);
        return old.paused() ? this : replace(side, new Approval(old.approved(), true));
    }

    /** Returns the owning side's resumed intent only; it never restores approvals or resumes the counterparty. */
    public ExchangeConsent resume(Side side, UUID actor, NetworkMetadata current, long expectedRevision) {
        authorize(side, actor, current, expectedRevision, true);
        var old = approval(side);
        return !old.paused() ? this : replace(side, new Approval(old.approved(), false));
    }

    /** Permanently withdraws consent by either owner. Revoked values reject all further transitions. */
    public ExchangeConsent revoke(Side side, UUID actor, NetworkMetadata current, long expectedRevision) {
        authorize(side, actor, current, expectedRevision, true);
        return new ExchangeConsent(
                invitationId,
                sourceNetwork,
                targetNetwork,
                sourceOwner,
                targetOwner,
                Math.incrementExact(revision),
                termsRevision,
                source,
                target,
                true);
    }

    private ExchangeConsent replace(Side side, Approval value) {
        return new ExchangeConsent(
                invitationId,
                sourceNetwork,
                targetNetwork,
                sourceOwner,
                targetOwner,
                Math.incrementExact(revision),
                termsRevision,
                side == Side.SOURCE ? value : source,
                side == Side.TARGET ? value : target,
                false);
    }

    private Approval approval(Side side) {
        return side == Side.SOURCE ? source : target;
    }

    private boolean matches(Side side, NetworkMetadata current) {
        Objects.requireNonNull(side);
        Objects.requireNonNull(current);
        return current.id().equals(side == Side.SOURCE ? sourceNetwork : targetNetwork)
                && current.ownerId().equals(side == Side.SOURCE ? sourceOwner : targetOwner);
    }

    private void authorize(Side side, UUID actor, NetworkMetadata current, long expectedRevision, boolean ownerOnly) {
        Objects.requireNonNull(actor);
        if (!matches(side, current)
                || (ownerOnly
                        ? !current.ownerId().equals(actor)
                        : !NetworkPermissions.canManage(actor, current.ownerId(), current.administrators())))
            throw new SecurityException("Exchange role or network identity changed");
        if (revoked || revision != expectedRevision)
            throw new IllegalStateException("Closed or stale exchange consent");
    }
}
