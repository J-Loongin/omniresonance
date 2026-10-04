// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeInvitation;
import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class ExchangeSavedDataTest {
    @Test
    void schemaFourMigratesToNodeBatchMetadataWithoutChangingApprovedTermsOrInput() {
        var data = pending();
        data.revise(
                AGREEMENT,
                ExchangeConsent.Side.SOURCE,
                OWNER_A,
                SOURCE,
                TARGET,
                0,
                new ExchangeTerms(
                        ResourceScope.all(),
                        FilterMode.WHITELIST,
                        null,
                        Long.MAX_VALUE,
                        Map.of(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM, 17L),
                        1));
        var legacy = data.save(new CompoundTag(), null);
        legacy.putInt("schema_version", 4);
        var agreements = (ListTag) legacy.get("agreements");
        for (var row : agreements) {
            var rates = (ListTag) ((CompoundTag) row).getCompound("terms").get("rates");
            for (var rate : rates) {
                ((CompoundTag) rate).remove("batch_mode");
                ((CompoundTag) rate).remove("batch_size");
            }
        }
        var original = legacy.copy();
        var migrated = ExchangeSavedData.load(legacy, LIMITS);
        assertTrue(migrated.isDirty());
        assertEquals(5, migrated.save(new CompoundTag(), null).getInt("schema_version"));
        assertEquals(original, legacy);
        var terms = migrated.agreement(AGREEMENT).orElseThrow().terms();
        assertEquals(Long.MAX_VALUE, terms.defaultRate());
        assertEquals(17, terms.rate(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM));
        assertEquals(
                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.BatchMode.GREEDY,
                terms.parameter(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)
                        .batchMode());
    }

    static final UUID OWNER_A = new UUID(1, 1),
            OWNER_B = new UUID(1, 2),
            INVITE = new UUID(2, 1),
            AGREEMENT = new UUID(3, 1);
    static final NetworkMetadata SOURCE =
            new NetworkMetadata(new UUID(4, 1), OWNER_A, new ManagedName("Source"), 0, Set.of());
    static final NetworkMetadata TARGET =
            new NetworkMetadata(new UUID(4, 2), OWNER_B, new ManagedName("Target"), 1, Set.of());
    static final ExchangeSavedData.Limits LIMITS = new ExchangeSavedData.Limits(8, 8);
    static final ExchangeTerms TERMS =
            new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 64, Map.of(), 1);

    static ExchangeSavedData pending() {
        ExchangeSavedData data = ExchangeSavedData.create(LIMITS);
        data.issue(INVITE, OWNER_B, TARGET, 0);
        data.propose(AGREEMENT, INVITE, OWNER_A, SOURCE, TARGET, 0, 1, TERMS);
        return data;
    }

    @Test
    void livePolicyCountsBothSidesAndShrinkingDoesNotRevokeExistingRules() {
        ExchangeSavedData data = pending();
        NetworkMetadata otherTarget =
                new NetworkMetadata(new UUID(4, 3), OWNER_B, new ManagedName("Other target"), 2, Set.of());
        NetworkMetadata otherSource =
                new NetworkMetadata(new UUID(4, 4), OWNER_A, new ManagedName("Other source"), 3, Set.of());
        UUID invitation = new UUID(2, 2);
        data.configurePolicy(new io.github.loongin.omniresonance.config.ServerSettings.Exchange(1, 1, 3, 2));
        data.issue(invitation, OWNER_B, otherTarget, 0);
        assertThrows(IllegalStateException.class, () -> data.issue(new UUID(2, 3), OWNER_B, otherTarget, 0));
        assertThrows(
                IllegalStateException.class,
                () -> data.propose(new UUID(3, 2), invitation, OWNER_A, SOURCE, otherTarget, 0, 1, TERMS));
        assertThrows(
                IllegalStateException.class,
                () -> data.propose(new UUID(3, 2), INVITE, OWNER_A, otherSource, TARGET, 0, 1, TERMS));
        data.configurePolicy(new io.github.loongin.omniresonance.config.ServerSettings.Exchange(3, 1, 1, 2));
        assertThrows(
                IllegalStateException.class,
                () -> data.propose(new UUID(3, 2), invitation, OWNER_A, otherSource, otherTarget, 0, 1, TERMS));
        data.setDirty(false);
        data.configurePolicy(new io.github.loongin.omniresonance.config.ServerSettings.Exchange(0, 0, 0, 0));
        assertFalse(data.isDirty());
        data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 1);
        assertTrue(data.agreement(AGREEMENT).orElseThrow().consent().permitsExecution(SOURCE, TARGET));
        assertEquals(1, data.activeRuleCount());
    }

    @Test
    void expiredApplicationsReleaseActiveQuotaButApprovedRevisionsDoNotExpire() {
        ExchangeSavedData data = pending();
        data.configurePolicy(new io.github.loongin.omniresonance.config.ServerSettings.Exchange(1, 1, 1, 2));
        data.issue(new UUID(2, 2), OWNER_B, TARGET, 12000);
        assertThrows(
                IllegalStateException.class,
                () -> data.propose(new UUID(3, 2), new UUID(2, 2), OWNER_A, SOURCE, TARGET, 0, 12000, TERMS));
        assertEquals(1, data.maintainHistory(12000, 1, 1).expired());
        assertEquals(0, data.activeRuleCount());
        data.propose(new UUID(3, 2), new UUID(2, 2), OWNER_A, SOURCE, TARGET, 0, 12000, TERMS);
        data.approve(new UUID(3, 2), ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 12001);
        data.revise(new UUID(3, 2), ExchangeConsent.Side.SOURCE, OWNER_A, SOURCE, TARGET, 1, TERMS);
        for (int i = 0; i < 4; i++) data.maintainHistory(99999, 1, 1);
        assertFalse(data.agreement(new UUID(3, 2)).orElseThrow().consent().revoked());
        assertEquals(1, data.activeRuleCount());
        assertEquals(1, data.activeRuleCount(SOURCE.id()));
    }

    @Test
    void historyPruningUsesTerminationOrderAndCannotReuseConsumedInvitation() {
        ExchangeSavedData data = pending();
        UUID second = new UUID(3, 0);
        data.propose(second, INVITE, OWNER_A, SOURCE, TARGET, 0, 1, TERMS);
        data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 2);
        data.act(AGREEMENT, ExchangeSavedData.Action.REVOKE, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 1);
        data.act(second, ExchangeSavedData.Action.REVOKE, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0);
        CompoundTag before = data.save(new CompoundTag(), null);
        data = ExchangeSavedData.load(before, LIMITS);
        data.configurePolicy(new io.github.loongin.omniresonance.config.ServerSettings.Exchange(1, 1, 1, 1));
        assertEquals(1, data.maintainHistory(3, 0, 1).removed());
        assertTrue(data.agreement(AGREEMENT).isEmpty());
        assertTrue(data.agreement(second).isPresent());
        assertEquals(Set.of(second), data.agreementsFor(SOURCE.id()));
        assertTrue(data.findUsableInvitation(
                        io.github.loongin.omniresonance.exchange.ExchangeInvitationCode.encode(INVITE), 3)
                .isEmpty());
        ExchangeSavedData.load(data.save(new CompoundTag(), null), LIMITS);
        data.configurePolicy(new io.github.loongin.omniresonance.config.ServerSettings.Exchange(1, 1, 1, 0));
        data.maintainHistory(3, 0, 1);
        assertEquals(1, data.cleanUnusedInvitations(3, 10).removed());
        assertTrue(data.invitation(INVITE).isEmpty());
    }

    @Test
    void legacyHistoryMigratesWithoutMutatingInputAndInvalidOrderRejects() {
        ExchangeSavedData data = pending();
        data.act(AGREEMENT, ExchangeSavedData.Action.REVOKE, ExchangeConsent.Side.SOURCE, OWNER_A, SOURCE, TARGET, 0);
        CompoundTag legacy = data.save(new CompoundTag(), null);
        legacy.putInt("schema_version", 1);
        legacy.remove("pairing");
        legacy.remove("terminated_revisions");
        CompoundTag original = legacy.copy();
        ExchangeSavedData migrated = ExchangeSavedData.load(legacy, LIMITS);
        assertEquals(original, legacy);
        assertTrue(migrated.isDirty());
        CompoundTag current = migrated.save(new CompoundTag(), null);
        assertEquals(5, current.getInt("schema_version"));
        current.getList("terminated_revisions", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0)
                .putLong("revision", Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> ExchangeSavedData.load(current, LIMITS));
    }

    @Test
    void liveAdmissionChangesKeepAuthorityCleanAndSnapshotsStable() {
        ExchangeSavedData data = pending();
        var snapshot = data.agreementsSnapshot();
        data.setDirty(false);
        data.configureLimits(new ExchangeSavedData.Limits(1, 1));
        assertFalse(data.isDirty());
        assertEquals(snapshot.revision(), data.agreementsSnapshot().revision());
        assertThrows(IllegalStateException.class, () -> data.issue(new UUID(2, 2), OWNER_B, TARGET, 0));
        assertThrows(
                IllegalStateException.class,
                () -> data.propose(new UUID(3, 2), INVITE, OWNER_A, SOURCE, TARGET, 0, 1, TERMS));
        assertFalse(data.isDirty());
        data.configureLimits(new ExchangeSavedData.Limits(2, 2));
        data.propose(new UUID(3, 2), INVITE, OWNER_A, SOURCE, TARGET, 0, 1, TERMS);
        assertEquals(1, snapshot.agreements().size());
        assertEquals(2, data.agreementsSnapshot().agreements().size());
        assertEquals(snapshot.revision() + 1, data.agreementsSnapshot().revision());
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.agreements().clear());
    }

    @Test
    void invitationOnlyChangesReuseImmutableAgreementMap() {
        ExchangeSavedData data = pending();
        var before = data.agreementsSnapshot();
        data.issue(new UUID(2, 2), OWNER_B, TARGET, 0);
        var after = data.agreementsSnapshot();
        org.junit.jupiter.api.Assertions.assertSame(before.agreements(), after.agreements());
        assertEquals(before.revision() + 1, after.revision());
    }

    @Test
    void approvalConsumesOnceAndReloadsBothNetworkIndices() {
        ExchangeSavedData data = pending();
        UUID second = new UUID(3, 2);
        data.propose(second, INVITE, OWNER_A, SOURCE, TARGET, 0, 2, TERMS);
        data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 3);
        assertEquals(
                ExchangeInvitation.State.CONSUMED,
                data.invitation(INVITE).orElseThrow().state());
        assertTrue(data.agreement(AGREEMENT).orElseThrow().consent().permitsExecution(SOURCE, TARGET));
        CompoundTag before = data.save(new CompoundTag(), null);
        data.setDirty(false);
        assertThrows(
                IllegalStateException.class,
                () -> data.approve(second, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 4));
        assertEquals(before, data.save(new CompoundTag(), null));
        assertFalse(data.isDirty());
        ExchangeSavedData loaded = ExchangeSavedData.load(before, LIMITS);
        assertFalse(loaded.isDirty());
        assertEquals(Set.of(AGREEMENT, second), loaded.agreementsFor(SOURCE.id()));
        assertEquals(loaded.agreementsFor(SOURCE.id()), loaded.agreementsFor(TARGET.id()));
        assertEquals(before, loaded.save(new CompoundTag(), null));
        assertThrows(
                UnsupportedOperationException.class,
                () -> loaded.agreementsFor(SOURCE.id()).clear());
    }

    @Test
    void reapprovalAfterTermsChangeDoesNotReuseExpiredInvitation() {
        ExchangeSavedData data = pending();
        data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 2);
        data.revise(
                AGREEMENT,
                ExchangeConsent.Side.SOURCE,
                OWNER_A,
                SOURCE,
                TARGET,
                1,
                new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 128, Map.of(), 2));
        data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 2, 99999);
        assertTrue(data.agreement(AGREEMENT).orElseThrow().consent().permitsExecution(SOURCE, TARGET));
        assertEquals(1, data.invitation(INVITE).orElseThrow().revision());
        var changedOwner = new NetworkMetadata(SOURCE.id(), OWNER_B, SOURCE.name(), 0, Set.of());
        assertThrows(
                SecurityException.class,
                () -> data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, changedOwner, TARGET, 3, 100000));
    }

    @Test
    void invalidCapacityExpiryAndSchemaLeaveOriginalUntouched() {
        ExchangeSavedData limited = ExchangeSavedData.create(new ExchangeSavedData.Limits(1, 1));
        limited.issue(INVITE, OWNER_B, TARGET, 0);
        limited.setDirty(false);
        assertThrows(IllegalStateException.class, () -> limited.issue(new UUID(2, 2), OWNER_B, TARGET, 0));
        assertFalse(limited.isDirty());
        ExchangeSavedData data = pending();
        assertThrows(
                IllegalStateException.class,
                () -> data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 12000));
        assertEquals(
                ExchangeInvitation.State.OPEN,
                data.invitation(INVITE).orElseThrow().state());
        CompoundTag invalid = data.save(new CompoundTag(), null);
        invalid.putInt("schema_version", 6);
        assertThrows(IllegalArgumentException.class, () -> ExchangeSavedData.load(invalid, LIMITS));
        CompoundTag duplicate = data.save(new CompoundTag(), null);
        ListTag rows = (ListTag) duplicate.get("agreements");
        rows.add(rows.get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ExchangeSavedData.load(duplicate, LIMITS));
        CompoundTag orphan = data.save(new CompoundTag(), null);
        orphan.put("invitations", new ListTag());
        assertThrows(IllegalArgumentException.class, () -> ExchangeSavedData.load(orphan, LIMITS));
    }

    @Test
    void eitherOwnerMayProposeRevisedTermsBeforeFirstAcceptance() {
        ExchangeSavedData data = pending();
        data.revise(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, TERMS);
        assertFalse(data.agreement(AGREEMENT).orElseThrow().consent().source().approved());
        data.approve(AGREEMENT, ExchangeConsent.Side.SOURCE, OWNER_A, SOURCE, TARGET, 1, 2);
        assertTrue(data.agreement(AGREEMENT).orElseThrow().consent().permitsExecution(SOURCE, TARGET));
        assertEquals(
                ExchangeInvitation.State.CONSUMED,
                data.invitation(INVITE).orElseThrow().state());
    }

    @Test
    void loweredLimitsRetainExistingAgreementsAndRevisionOverflowDoesNotPublish() {
        ExchangeSavedData data = pending();
        UUID second = new UUID(3, 2);
        data.propose(second, INVITE, OWNER_A, SOURCE, TARGET, 0, 1, TERMS);
        CompoundTag saved = data.save(new CompoundTag(), null);
        ExchangeSavedData lowered = ExchangeSavedData.load(saved, new ExchangeSavedData.Limits(1, 1));
        assertEquals(2, lowered.agreementsFor(SOURCE.id()).size());
        assertThrows(
                IllegalStateException.class,
                () -> lowered.propose(new UUID(3, 3), INVITE, OWNER_A, SOURCE, TARGET, 0, 1, TERMS));
        lowered.act(AGREEMENT, ExchangeSavedData.Action.PAUSE, ExchangeConsent.Side.SOURCE, OWNER_A, SOURCE, TARGET, 0);
        assertTrue(lowered.agreement(AGREEMENT).orElseThrow().consent().source().paused());
        saved.putLong("revision", Long.MAX_VALUE);
        ExchangeSavedData exhausted = ExchangeSavedData.load(saved, LIMITS);
        assertThrows(
                ArithmeticException.class,
                () -> exhausted.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 2));
        assertEquals(saved, exhausted.save(new CompoundTag(), null));
        assertFalse(exhausted.isDirty());
        assertEquals(
                ExchangeInvitation.State.OPEN,
                exhausted.invitation(INVITE).orElseThrow().state());
    }

    @Test
    void rejectionKeepsInvitationUsableAndSourceCannotResumeTargetPause() {
        ExchangeSavedData data = pending();
        data.act(AGREEMENT, ExchangeSavedData.Action.REVOKE, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0);
        assertTrue(data.invitation(INVITE).orElseThrow().usable(2));
        UUID second = new UUID(3, 2);
        data.propose(second, INVITE, OWNER_A, SOURCE, TARGET, 0, 2, TERMS);
        data.approve(second, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 3);
        data.act(second, ExchangeSavedData.Action.PAUSE, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 1);
        assertThrows(
                SecurityException.class,
                () -> data.act(
                        second,
                        ExchangeSavedData.Action.RESUME,
                        ExchangeConsent.Side.TARGET,
                        OWNER_A,
                        SOURCE,
                        TARGET,
                        2));
        assertFalse(data.agreement(second).orElseThrow().consent().permitsExecution(SOURCE, TARGET));
        assertThrows(IllegalStateException.class, () -> data.revokeInvitation(INVITE, OWNER_B, TARGET, 1));
    }

    @Test
    void codeLookupIsReadOnlyAndRejectsExpiredRevokedOrConsumedInvitations() {
        ExchangeSavedData data = pending();
        String code = io.github.loongin.omniresonance.exchange.ExchangeInvitationCode.encode(INVITE);
        data.setDirty(false);
        assertTrue(data.findUsableInvitation(code, 11999).isPresent());
        assertTrue(data.findUsableInvitation(code, 12000).isEmpty());
        assertFalse(data.isDirty());
        data.approve(AGREEMENT, ExchangeConsent.Side.TARGET, OWNER_B, SOURCE, TARGET, 0, 2);
        assertTrue(data.findUsableInvitation(code, 3).isEmpty());
        UUID spare = new UUID(2, 2);
        data.issue(spare, OWNER_B, TARGET, 0);
        data.revokeInvitation(spare, OWNER_B, TARGET, 0);
        assertTrue(data.findUsableInvitation(
                        io.github.loongin.omniresonance.exchange.ExchangeInvitationCode.encode(spare), 3)
                .isEmpty());
    }

    @Test
    void boundedCleanupRetainsReferencedAuthorityAndReleasesUnusedInvitationCapacity() {
        ExchangeSavedData data = pending();
        UUID spare = new UUID(2, 2);
        data.issue(spare, OWNER_B, TARGET, 0);
        data.setDirty(false);
        assertEquals(0, data.cleanUnusedInvitations(12000, 0).scanned());
        assertFalse(data.isDirty());
        int removed = 0;
        for (int n = 0; n < 4; n++) {
            var progress = data.cleanUnusedInvitations(12000, 1);
            assertTrue(progress.scanned() <= 1);
            removed += progress.removed();
        }
        assertEquals(1, removed);
        assertTrue(data.invitation(spare).isEmpty());
        assertTrue(data.invitation(INVITE).isPresent());
        assertTrue(data.agreement(AGREEMENT).isPresent());
        assertEquals(
                1,
                ExchangeSavedData.load(data.save(new CompoundTag(), null), LIMITS)
                        .agreementsFor(SOURCE.id())
                        .size());
    }

    @Test
    void owningThreadGuardsReadsAndDirtyState() throws InterruptedException {
        ExchangeSavedData data = pending();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                data.isDirty();
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        other.start();
        other.join();
        assertTrue(error.get() instanceof IllegalStateException);
    }
}
