// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeConsentTest {
    static final UUID INVITE = new UUID(1, 1), A = new UUID(2, 1), B = new UUID(2, 2), ADMIN = new UUID(3, 1);
    static final NetworkMetadata SOURCE =
            new NetworkMetadata(new UUID(4, 1), A, new ManagedName("Source"), 0, Set.of(ADMIN));
    static final NetworkMetadata TARGET =
            new NetworkMetadata(new UUID(4, 2), B, new ManagedName("Target"), 1, Set.of());

    @Test
    void termsRevisionRetainsPausesAndExhaustedRevisionFailsWithoutMutation() {
        var active =
                ExchangeConsent.propose(INVITE, A, SOURCE, TARGET).approve(ExchangeConsent.Side.TARGET, B, TARGET, 0);
        var paused = active.pause(ExchangeConsent.Side.SOURCE, ADMIN, SOURCE, active.revision());
        var revised = paused.revise(ExchangeConsent.Side.TARGET, B, TARGET, paused.revision());
        var approved = revised.approve(ExchangeConsent.Side.SOURCE, A, SOURCE, revised.revision());
        assertTrue(approved.approvedByBoth());
        assertFalse(approved.permitsExecution(SOURCE, TARGET));
        assertTrue(approved.resume(ExchangeConsent.Side.SOURCE, A, SOURCE, approved.revision())
                .permitsExecution(SOURCE, TARGET));
        var exhausted = new ExchangeConsent(
                INVITE,
                SOURCE.id(),
                TARGET.id(),
                A,
                B,
                Long.MAX_VALUE,
                0,
                new ExchangeConsent.Approval(true, false),
                new ExchangeConsent.Approval(true, false),
                false);
        assertThrows(
                ArithmeticException.class,
                () -> exhausted.pause(ExchangeConsent.Side.SOURCE, A, SOURCE, Long.MAX_VALUE));
        assertTrue(exhausted.permitsExecution(SOURCE, TARGET));
        assertThrows(
                SecurityException.class,
                () -> active.revise(ExchangeConsent.Side.SOURCE, ADMIN, SOURCE, active.revision()));
    }

    @Test
    void onlyOwnersApproveAndTermsChangesInvalidateTheOtherSidesApproval() {
        var pending = ExchangeConsent.propose(INVITE, A, SOURCE, TARGET);
        assertFalse(pending.permitsExecution(SOURCE, TARGET));
        assertThrows(SecurityException.class, () -> pending.approve(ExchangeConsent.Side.TARGET, ADMIN, TARGET, 0));
        var active = pending.approve(ExchangeConsent.Side.TARGET, B, TARGET, 0);
        assertTrue(active.permitsExecution(SOURCE, TARGET));
        var changed = active.revise(ExchangeConsent.Side.TARGET, B, TARGET, active.revision());
        assertEquals(1, changed.termsRevision());
        assertFalse(changed.permitsExecution(SOURCE, TARGET));
        assertThrows(
                IllegalStateException.class,
                () -> changed.approve(ExchangeConsent.Side.SOURCE, A, SOURCE, active.revision()));
        assertTrue(changed.approve(ExchangeConsent.Side.SOURCE, A, SOURCE, changed.revision())
                .permitsExecution(SOURCE, TARGET));
        assertTrue(active.permitsExecution(SOURCE, TARGET), "Transitions never mutate prior snapshots");
    }

    @Test
    void independentPausesAndCurrentRolesCannotBeOverriddenByTheOtherSide() {
        var active =
                ExchangeConsent.propose(INVITE, A, SOURCE, TARGET).approve(ExchangeConsent.Side.TARGET, B, TARGET, 0);
        var paused = active.pause(ExchangeConsent.Side.SOURCE, ADMIN, SOURCE, active.revision());
        assertFalse(paused.permitsExecution(SOURCE, TARGET));
        assertThrows(
                SecurityException.class,
                () -> paused.resume(ExchangeConsent.Side.SOURCE, ADMIN, SOURCE, paused.revision()));
        assertThrows(
                SecurityException.class,
                () -> paused.resume(ExchangeConsent.Side.SOURCE, B, TARGET, paused.revision()));
        var other = paused.pause(ExchangeConsent.Side.TARGET, B, TARGET, paused.revision());
        var sourceResumed = other.resume(ExchangeConsent.Side.SOURCE, A, SOURCE, other.revision());
        assertFalse(sourceResumed.permitsExecution(SOURCE, TARGET));
        var resumed = sourceResumed.resume(ExchangeConsent.Side.TARGET, B, TARGET, sourceResumed.revision());
        assertTrue(resumed.permitsExecution(SOURCE, TARGET));
        var removedAdmin = new NetworkMetadata(SOURCE.id(), A, SOURCE.name(), 0, Set.of());
        assertThrows(
                SecurityException.class,
                () -> resumed.pause(ExchangeConsent.Side.SOURCE, ADMIN, removedAdmin, resumed.revision()));
        var newOwner = new NetworkMetadata(SOURCE.id(), new UUID(9, 9), SOURCE.name(), 0, Set.of());
        assertFalse(resumed.permitsExecution(newOwner, TARGET));
    }

    @Test
    void revocationIsTerminalAndSameOwnerStillConfirmsBothSides() {
        var target = new NetworkMetadata(TARGET.id(), A, TARGET.name(), 1, Set.of());
        var pending = ExchangeConsent.propose(INVITE, A, SOURCE, target);
        assertFalse(pending.approvedByBoth());
        var active = pending.approve(ExchangeConsent.Side.TARGET, A, target, 0);
        var revoked = active.revoke(ExchangeConsent.Side.TARGET, A, target, active.revision());
        assertFalse(revoked.permitsExecution(SOURCE, target));
        assertThrows(
                IllegalStateException.class,
                () -> revoked.approve(ExchangeConsent.Side.TARGET, A, target, revoked.revision()));
        assertThrows(IllegalArgumentException.class, () -> ExchangeConsent.propose(INVITE, A, SOURCE, SOURCE));
        assertThrows(SecurityException.class, () -> ExchangeConsent.propose(INVITE, ADMIN, SOURCE, TARGET));
    }
}
