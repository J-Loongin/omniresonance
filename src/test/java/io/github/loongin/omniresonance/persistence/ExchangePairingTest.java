// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangePairingTest {
    private static final UUID A = new UUID(1, 1),
            B = new UUID(1, 2),
            CODE = new UUID(2, 1),
            PAIR = new UUID(3, 1),
            CHANNEL = new UUID(4, 1);
    private static final NetworkMetadata NA = new NetworkMetadata(new UUID(5, 1), A, new ManagedName("A"), 0, Set.of());
    private static final NetworkMetadata NB = new NetworkMetadata(new UUID(5, 2), B, new ManagedName("B"), 0, Set.of());

    private static ExchangeTerms terms() {
        return new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 1, Map.of(), 1);
    }

    @Test
    void pairingDoesNotCreateAChannelAndBothDirectionsRequireSeparateConsent() {
        var d = ExchangeSavedData.create(new ExchangeSavedData.Limits(20, 20));
        d.issue(CODE, B, NB, 0);
        d.proposePair(PAIR, CODE, A, NA, NB, 0, 1);
        assertTrue(d.agreementsFor(NA.id()).isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> d.createChannel(CHANNEL, PAIR, A, NA, NB, 0, new ManagedName("Iron"), false, terms()));
        d.approvePair(PAIR, B, NB, NA, 0, 2);
        d.createChannel(CHANNEL, PAIR, A, NA, NB, 1, new ManagedName("Iron"), false, terms());
        var agreement = d.agreement(CHANNEL).orElseThrow();
        assertEquals(NB.id(), agreement.consent().sourceNetwork());
        assertTrue(agreement.consent().target().approved());
        assertFalse(agreement.consent().source().approved());
        d.approve(CHANNEL, ExchangeConsent.Side.SOURCE, B, NB, NA, 0, 3);
        assertTrue(d.agreement(CHANNEL).orElseThrow().consent().approvedByBoth());
        d.act(CHANNEL, ExchangeSavedData.Action.PAUSE, ExchangeConsent.Side.TARGET, A, NB, NA, 1);
        d.reviseChannel(CHANNEL, A, NA, NB, 2, new ManagedName("Iron return"), true, terms());
        var reversed = d.agreement(CHANNEL).orElseThrow().consent();
        assertEquals(NA.id(), reversed.sourceNetwork());
        assertTrue(reversed.source().paused());
        assertFalse(reversed.target().approved());
        d.closePair(PAIR, A, NA, 1);
        assertTrue(d.agreement(CHANNEL).orElseThrow().consent().revoked());
        assertTrue(d.tunnel(PAIR).orElseThrow().consent().revoked());
    }

    @Test
    void legacyMigrationPreservesTermsDirectionsAndApprovalsWithoutEditingInput() {
        var d = ExchangeSavedData.create(new ExchangeSavedData.Limits(20, 20));
        d.issue(CODE, B, NB, 0);
        d.propose(CHANNEL, CODE, A, NA, NB, 0, 1, terms());
        d.approve(CHANNEL, ExchangeConsent.Side.TARGET, B, NA, NB, 0, 2);
        var old = d.save(new net.minecraft.nbt.CompoundTag(), null);
        old.putInt("schema_version", 2);
        old.remove("pairing");
        var untouched = old.copy();
        var migrated = ExchangeSavedData.load(old, new ExchangeSavedData.Limits(20, 20));
        assertEquals(untouched, old);
        assertEquals(d.agreement(CHANNEL), migrated.agreement(CHANNEL));
        assertEquals(1, migrated.tunnels().size());
        var link = migrated.channel(CHANNEL).orElseThrow();
        assertTrue(migrated.tunnel(link.tunnel()).orElseThrow().consent().approvedByBoth());
        assertTrue(migrated.isDirty());
        var again = ExchangeSavedData.load(
                migrated.save(new net.minecraft.nbt.CompoundTag(), null), new ExchangeSavedData.Limits(20, 20));
        assertFalse(again.isDirty());
        assertEquals(link, again.channel(CHANNEL).orElseThrow());
    }

    @Test
    void pairingAndChannelQuotasAreIndependentAndFailedNameChangesAreAtomic() {
        var d = ExchangeSavedData.create(new ExchangeSavedData.Limits(20, 20));
        d.configurePolicy(new io.github.loongin.omniresonance.config.ServerSettings.Exchange(1, 8, 1, 0, 1, 1));
        d.issue(CODE, B, NB, 0);
        d.proposePair(PAIR, CODE, A, NA, NB, 0, 1);
        d.approvePair(PAIR, B, NB, NA, 0, 2);
        d.createChannel(CHANNEL, PAIR, A, NA, NB, 1, new ManagedName("Iron"), true, terms());
        var before = d.save(new net.minecraft.nbt.CompoundTag(), null);
        d.setDirty(false);
        assertThrows(
                IllegalStateException.class,
                () -> d.createChannel(new UUID(7, 1), PAIR, A, NA, NB, 1, new ManagedName("Gold"), true, terms()));
        assertEquals(before, d.save(new net.minecraft.nbt.CompoundTag(), null));
        assertFalse(d.isDirty());
        d.closePair(PAIR, A, NA, 1);
        d.maintainHistory(3, 20, 20);
        d.maintainPairings(3, 20, 20);
        assertTrue(d.tunnels().isEmpty());
        d.cleanUnusedInvitations(3, 20);
        assertTrue(d.invitations().isEmpty());
    }

    @Test
    void expiredPairingClosesWithoutCreatingOrActivatingAChannel() {
        var d = ExchangeSavedData.create(new ExchangeSavedData.Limits(20, 20));
        d.issue(CODE, B, NB, 0);
        d.proposePair(PAIR, CODE, A, NA, NB, 0, 1);
        d.maintainPairings(12000, 1, 0);
        assertTrue(d.tunnel(PAIR).orElseThrow().consent().revoked());
        assertTrue(d.agreementsFor(NA.id()).isEmpty());
    }
}
