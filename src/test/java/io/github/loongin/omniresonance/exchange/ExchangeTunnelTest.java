// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeTunnelTest {
    @Test
    void pairingRequiresBothOwnersAndNeverCarriesTransferTerms() {
        var pair = ExchangeTunnel.propose(
                new UUID(1, 1),
                ExchangeConsentTest.INVITE,
                ExchangeConsentTest.A,
                ExchangeConsentTest.SOURCE,
                ExchangeConsentTest.TARGET);
        assertFalse(pair.permitsChannels(ExchangeConsentTest.SOURCE, ExchangeConsentTest.TARGET));
        assertThrows(
                SecurityException.class,
                () -> pair.approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.A, ExchangeConsentTest.TARGET, 0));
        var approved = pair.approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        assertTrue(approved.permitsChannels(ExchangeConsentTest.SOURCE, ExchangeConsentTest.TARGET));
        assertEquals(ExchangeConsentTest.TARGET.id(), approved.peer(ExchangeConsentTest.SOURCE.id()));
        assertEquals(ExchangeConsentTest.SOURCE.id(), approved.peer(ExchangeConsentTest.TARGET.id()));
        assertThrows(SecurityException.class, () -> approved.peer(new UUID(3, 4)));
        assertFalse(pair.consent().approvedByBoth());
    }

    @Test
    void eitherSigningOwnerCanCloseWithoutReadingThePeerAndOldRevisionsFail() {
        var pair = ExchangeTunnel.propose(
                        new UUID(1, 1),
                        ExchangeConsentTest.INVITE,
                        ExchangeConsentTest.A,
                        ExchangeConsentTest.SOURCE,
                        ExchangeConsentTest.TARGET)
                .approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        assertThrows(
                IllegalStateException.class,
                () -> pair.close(ExchangeConsent.Side.SOURCE, ExchangeConsentTest.A, ExchangeConsentTest.SOURCE, 0));
        var ended = pair.close(ExchangeConsent.Side.SOURCE, ExchangeConsentTest.A, ExchangeConsentTest.SOURCE, 1);
        assertFalse(ended.permitsChannels(ExchangeConsentTest.SOURCE, ExchangeConsentTest.TARGET));
        assertThrows(
                IllegalStateException.class,
                () -> ended.approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 2));
    }

    @Test
    void reversingAChannelInvalidatesApprovalsAndKeepsEachNetworksPause() {
        var before = ExchangeConsent.propose(
                        ExchangeConsentTest.INVITE,
                        ExchangeConsentTest.A,
                        ExchangeConsentTest.SOURCE,
                        ExchangeConsentTest.TARGET)
                .approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0)
                .pause(ExchangeConsent.Side.SOURCE, ExchangeConsentTest.A, ExchangeConsentTest.SOURCE, 1);
        var reversed =
                before.reverse(ExchangeConsent.Side.SOURCE, ExchangeConsentTest.A, ExchangeConsentTest.SOURCE, 2);
        assertEquals(ExchangeConsentTest.TARGET.id(), reversed.sourceNetwork());
        assertEquals(ExchangeConsentTest.SOURCE.id(), reversed.targetNetwork());
        assertFalse(reversed.source().approved());
        assertTrue(reversed.target().approved());
        assertFalse(reversed.source().paused());
        assertTrue(reversed.target().paused());
        assertFalse(reversed.approvedByBoth());
        assertEquals(3, reversed.revision());
        assertEquals(1, reversed.termsRevision());
        assertThrows(
                SecurityException.class,
                () -> before.reverse(
                        ExchangeConsent.Side.SOURCE, ExchangeConsentTest.B, ExchangeConsentTest.SOURCE, 2));
    }
}
