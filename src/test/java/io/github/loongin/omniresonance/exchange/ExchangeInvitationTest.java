// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeInvitationTest {
    @Test
    void invitationExpiresAtTheExactBoundaryAndOnlySuccessfulApprovalConsumesIt() {
        var invite = ExchangeInvitation.issue(
                ExchangeConsentTest.INVITE, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 100);
        assertEquals(12100, invite.expiresTick());
        assertTrue(invite.usable(100));
        assertTrue(invite.usable(12099));
        assertFalse(invite.usable(12100));
        assertFalse(invite.usable(99));
        var pending = ExchangeConsent.propose(
                invite.id(), ExchangeConsentTest.A, ExchangeConsentTest.SOURCE, ExchangeConsentTest.TARGET);
        assertThrows(
                IllegalStateException.class,
                () -> invite.consumeAfterApproval(ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0, 101, pending));
        assertTrue(invite.usable(101));
        var rejected = pending.revoke(
                ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, pending.revision());
        assertThrows(
                IllegalStateException.class,
                () -> invite.consumeAfterApproval(ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0, 101, rejected));
        assertTrue(invite.usable(101));
        assertThrows(
                IllegalStateException.class, () -> invite.revoke(ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 1));
        var approved =
                pending.approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        var used = invite.consumeAfterApproval(ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0, 101, approved);
        assertEquals(ExchangeInvitation.State.CONSUMED, used.state());
        assertFalse(used.usable(102));
        assertThrows(
                IllegalStateException.class,
                () -> used.consumeAfterApproval(
                        ExchangeConsentTest.B, ExchangeConsentTest.TARGET, used.revision(), 102, approved));
        assertThrows(
                IllegalStateException.class,
                () -> invite.consumeAfterApproval(
                        ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0, 12100, approved));
    }

    @Test
    void wrongInviteRevocationAndOverflowCannotCreateAuthorization() {
        var invite = ExchangeInvitation.issue(
                ExchangeConsentTest.INVITE, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        var wrong = ExchangeConsent.propose(
                        new UUID(8, 8), ExchangeConsentTest.A, ExchangeConsentTest.SOURCE, ExchangeConsentTest.TARGET)
                .approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> invite.consumeAfterApproval(ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0, 1, wrong));
        var revoked = invite.revoke(ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        assertFalse(revoked.usable(1));
        assertThrows(
                SecurityException.class, () -> invite.revoke(ExchangeConsentTest.ADMIN, ExchangeConsentTest.TARGET, 0));
        assertThrows(
                SecurityException.class,
                () -> ExchangeInvitation.issue(
                        new UUID(8, 8), ExchangeConsentTest.ADMIN, ExchangeConsentTest.TARGET, 0));
        assertThrows(
                ArithmeticException.class,
                () -> ExchangeInvitation.issue(
                        new UUID(8, 8), ExchangeConsentTest.B, ExchangeConsentTest.TARGET, Long.MAX_VALUE));
    }
}
