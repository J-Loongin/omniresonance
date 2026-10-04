// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.DomainTransferWindow;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeTypeWorkTest {
    private static ExchangeAgreement exactAgreement(
            long rate, long batch, net.minecraft.resources.ResourceLocation type) {
        var original =
                agreement(rate, io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources());
        return new ExchangeAgreement(
                original.id(),
                original.consent(),
                new ExchangeTerms(
                        ResourceScope.all(),
                        FilterMode.WHITELIST,
                        original.terms().filter(),
                        ExchangeTerms.DEFAULT_RATE,
                        Map.of(),
                        5,
                        Map.of(
                                type,
                                new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.InputOverride(
                                        rate,
                                        io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.BatchMode.EXACT,
                                        batch))));
    }

    @Test
    void exactBatchRoundsTheWindowAndTargetCapacityBeforeMoving() {
        var env = new Environment(1000);
        try (var work = new ExchangeTypeWork(
                exactAgreement(130, 64, ResourceTypes.ENERGY),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                env,
                TAGS)) {
            assertEquals(128, work.step(0, 100, budget(), -1).moved());
            assertEquals(872, env.source.amount(KEY));
            assertEquals(128, env.target.amount(KEY));
        }
        var full = new Environment(1000);
        try (var deposit =
                full.target.reserveDeposit(KEY, Long.MAX_VALUE - 31, -1).orElseThrow()) {
            deposit.commit(Long.MAX_VALUE - 31);
        }
        try (var work = new ExchangeTypeWork(
                exactAgreement(130, 64, ResourceTypes.ENERGY),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                full,
                TAGS)) {
            assertEquals(0, work.step(0, 100, budget(), -1).moved());
            assertEquals(1000, full.source.amount(KEY));
            assertEquals(Long.MAX_VALUE - 31, full.target.amount(KEY));
        }
    }

    @Test
    void exactBatchCannotCombineDifferentVariantsOrAccumulatePartialWindows() {
        var env = new Environment(1);
        var first = item(env, "minecraft:stone");
        var second = item(env, "minecraft:dirt");
        try (var work = new ExchangeTypeWork(
                exactAgreement(64, 16, ResourceTypes.ITEM),
                ResourceTypes.ITEM,
                new DomainTransferWindow(),
                env,
                TAGS)) {
            assertEquals(0, work.step(0, 100, budget(), -1).moved());
            assertEquals(0, work.step(5, 100, budget(), -1).moved());
            assertEquals(10, env.source.amount(first));
            assertEquals(10, env.source.amount(second));
            assertEquals(0, env.target.amount(first));
            assertEquals(0, env.target.amount(second));
        }
    }

    @Test
    void subBatchRateNeverAccumulatesAcrossWindowsAndSmallBudgetsResumeWholeBatches() {
        var tooSmall = new Environment(1000);
        try (var work = new ExchangeTypeWork(
                exactAgreement(3, 4, ResourceTypes.ENERGY),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                tooSmall,
                TAGS)) {
            for (int tick = 0; tick < 30; tick += 5)
                assertEquals(0, work.step(tick, 100, budget(), -1).moved());
            assertEquals(1000, tooSmall.source.amount(KEY));
            assertEquals(0, tooSmall.target.amount(KEY));
        }
        var resumed = new Environment(1000);
        try (var work = new ExchangeTypeWork(
                exactAgreement(130, 64, ResourceTypes.ENERGY),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                resumed,
                TAGS)) {
            for (int step = 0; step < 100; step++) {
                var progress = work.step(0, 1, budget(), -1);
                assertTrue(progress.used() <= 1);
                assertEquals(0, progress.moved() % 64);
            }
            assertEquals(128, resumed.target.amount(KEY));
            assertEquals(0, work.step(4, 100, budget(), -1).moved());
            assertEquals(128, work.step(5, 100, budget(), -1).moved());
        }
    }

    private static final ResourceVariantKey KEY = EnergyVariant.INSTANCE.key();
    private static final ResourceFilterCompiler.Tags TAGS =
            (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0);

    private static TransferWorkBudget budget() {
        return new TransferWorkBudget(1, 100000, 1000, () -> 0);
    }

    private static ExchangeAgreement agreement(long rate, ExchangeFilterSnapshot filter) {
        var consent = ExchangeConsent.propose(
                        ExchangeConsentTest.INVITE,
                        ExchangeConsentTest.A,
                        ExchangeConsentTest.SOURCE,
                        ExchangeConsentTest.TARGET)
                .approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        return new ExchangeAgreement(
                new UUID(80, 1),
                consent,
                new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, filter, rate, Map.of(), 5));
    }

    private static final class Environment implements ExchangeTypeWork.Environment {
        final DomainLedger source = new DomainLedger(
                ExchangeConsentTest.SOURCE.id(),
                Map.of(),
                i -> StorageBucketData.create(ExchangeConsentTest.SOURCE.id(), i));
        final DomainLedger target = new DomainLedger(
                ExchangeConsentTest.TARGET.id(),
                Map.of(),
                i -> StorageBucketData.create(ExchangeConsentTest.TARGET.id(), i));
        boolean active = true;
        boolean revokeOnDecode;
        boolean swapped;
        int ledgerLookups;
        final Map<ResourceVariantKey, ResourceVariant> samples = new java.util.HashMap<>();

        Environment(long amount) {
            samples.put(KEY, EnergyVariant.INSTANCE);
            try (var d = source.reserveDeposit(KEY, amount, -1).orElseThrow()) {
                d.commit(amount);
            }
        }

        public boolean active(ExchangeAgreement agreement) {
            return active;
        }

        public DomainLedger source(ExchangeAgreement agreement) {
            ledgerLookups++;
            return swapped ? target : source;
        }

        public DomainLedger target(ExchangeAgreement agreement) {
            ledgerLookups++;
            return swapped ? source : target;
        }

        public ResourceVariant decode(ResourceVariantKey key) {
            if (revokeOnDecode) active = false;
            return samples.get(key);
        }
    }

    private static ResourceVariantKey item(Environment env, String name) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", name);
        tag.put("components", new net.minecraft.nbt.CompoundTag());
        var key = new ResourceVariantKey(
                ResourceTypes.ITEM, io.github.loongin.omniresonance.transfer.CanonicalResourceNbt.encode(tag));
        env.samples.put(key, () -> key);
        try (var deposit = env.source.reserveDeposit(key, 10, -1).orElseThrow()) {
            deposit.commit(10);
        }
        return key;
    }

    @Test
    void missingPresetAndEmptyBlacklistCannotTouchEitherLedger() {
        var empty = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                new UUID(81, 9),
                new io.github.loongin.omniresonance.network.ManagedName("Empty"),
                0,
                java.util.List.of());
        var emptySnapshot = ExchangeFilterSnapshot.capture(empty.id(), Map.of(empty.id(), empty), 1, 0);
        for (var original : java.util.List.of(agreement(10, null), agreement(10, emptySnapshot))) {
            var a = new ExchangeAgreement(
                    original.id(),
                    original.consent(),
                    new ExchangeTerms(
                            ResourceScope.all(),
                            FilterMode.BLACKLIST,
                            original.terms().filter(),
                            10,
                            Map.of(),
                            1));
            var env = new Environment(100);
            try (var work = new ExchangeTypeWork(a, ResourceTypes.ENERGY, new DomainTransferWindow(), env, TAGS)) {
                var progress = work.step(0, 100, budget(), -1);
                assertEquals(ExchangeTypeWork.Status.BLOCKED, progress.status());
                assertEquals(100, env.source.amount(KEY));
                assertEquals(0, env.target.amount(KEY));
                assertEquals(0, env.ledgerLookups);
                assertTrue(ExchangeFilterStatus.of(a.terms(), null).blocked());
            }
        }
    }

    @Test
    void incorrectLedgerMappingCannotReverseTheApprovedDirection() {
        Environment env = new Environment(100);
        try (var deposit = env.target.reserveDeposit(KEY, 50, -1).orElseThrow()) {
            deposit.commit(50);
        }
        env.swapped = true;
        var work = new ExchangeTypeWork(
                agreement(9, io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources()),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                env,
                TAGS);
        work.step(0, 100, budget(), -1);
        assertEquals(100, env.source.amount(KEY));
        assertEquals(50, env.target.amount(KEY));
    }

    @Test
    void unfinishedMatchingAndTagReloadUseCurrentMembership() {
        Environment env = new Environment(1);
        var iron = item(env, "minecraft:iron_ingot");
        var stone = item(env, "minecraft:stone");
        var tagId = net.minecraft.resources.ResourceLocation.parse("c:chosen");
        var rule = new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                new UUID(82, 1),
                ResourceTypes.ITEM,
                io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.tag(tagId),
                io.github.loongin.omniresonance.filter.ComponentCondition.idOnly());
        var preset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                new UUID(82, 2),
                new io.github.loongin.omniresonance.network.ManagedName("Tagged"),
                0,
                java.util.List.of(rule));
        var filter = ExchangeFilterSnapshot.capture(preset.id(), Map.of(preset.id(), preset), 1, 1);
        var work = new ExchangeTypeWork(
                agreement(3, filter),
                ResourceTypes.ITEM,
                new DomainTransferWindow(),
                env,
                (type, tag) -> new ResourceFilterCompiler.TagSnapshot(
                        true,
                        0,
                        java.util.List.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))));
        for (int i = 0; i < 200; i++) assertTrue(work.step(0, 1, budget(), -1).used() <= 1);
        assertEquals(3, env.target.amount(iron));
        assertEquals(0, env.target.amount(stone));
        work.invalidateTags((type, tag) -> new ResourceFilterCompiler.TagSnapshot(
                true, 1, java.util.List.of(net.minecraft.resources.ResourceLocation.parse("minecraft:stone"))));
        work.step(5, 200, budget(), -1);
        assertEquals(3, env.target.amount(iron));
        assertEquals(3, env.target.amount(stone));
    }

    @Test
    void smallRatesRotateVariantsInsteadOfAlwaysTakingFirst() {
        Environment env = new Environment(1);
        var iron = item(env, "minecraft:iron_ingot");
        var stone = item(env, "minecraft:stone");
        var work = new ExchangeTypeWork(
                agreement(1, io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources()),
                ResourceTypes.ITEM,
                new DomainTransferWindow(),
                env,
                TAGS);
        work.step(0, 100, budget(), -1);
        work.step(5, 100, budget(), -1);
        assertEquals(1, env.target.amount(iron));
        assertEquals(1, env.target.amount(stone));
    }

    @Test
    void eligibilityChangeDuringPreparationPreventsCommit() {
        Environment env = new Environment(100);
        env.revokeOnDecode = true;
        var work = new ExchangeTypeWork(
                agreement(9, io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources()),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                env,
                TAGS);
        work.step(0, 100, budget(), -1);
        assertEquals(0, env.target.amount(KEY));
        assertEquals(100, env.source.amount(KEY));
    }

    @Test
    void smallBudgetsResumeWithoutExceedingRateOrResettingInterval() {
        Environment env = new Environment(100);
        var work = new ExchangeTypeWork(
                agreement(7, io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources()),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                env,
                TAGS);
        for (int i = 0; i < 40; i++) assertTrue(work.step(0, 1, budget(), -1).used() <= 1);
        assertEquals(7, env.target.amount(KEY));
        work.step(4, 100, budget(), -1);
        assertEquals(7, env.target.amount(KEY));
        work.step(5, 100, budget(), -1);
        assertEquals(14, env.target.amount(KEY));
        assertFalse(env.source.hasReservations());
        assertFalse(env.target.hasReservations());
    }

    @Test
    void zeroBudgetAndRevokedEligibilityNeverMoveResources() {
        Environment env = new Environment(100);
        var work = new ExchangeTypeWork(
                agreement(9, io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources()),
                ResourceTypes.ENERGY,
                new DomainTransferWindow(),
                env,
                TAGS);
        assertEquals(0, work.step(0, 0, budget(), -1).used());
        env.active = false;
        work.step(0, 100, budget(), -1);
        assertEquals(0, env.target.amount(KEY));
        env.active = true;
        work.step(0, 100, budget(), -1);
        assertEquals(9, env.target.amount(KEY));
    }

    @Test
    void emptyApprovedWhitelistBlocksEveryVariant() {
        var preset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                new UUID(81, 1),
                new io.github.loongin.omniresonance.network.ManagedName("Empty"),
                0,
                java.util.List.of());
        var filter = ExchangeFilterSnapshot.capture(preset.id(), Map.of(preset.id(), preset), 1, 0);
        Environment env = new Environment(100);
        var work =
                new ExchangeTypeWork(agreement(9, filter), ResourceTypes.ENERGY, new DomainTransferWindow(), env, TAGS);
        work.step(0, 100, budget(), -1);
        assertEquals(0, env.target.amount(KEY));
        assertEquals(100, env.source.amount(KEY));
        assertEquals(0, env.ledgerLookups, "Definitively excluded types must not resolve ledgers");
    }

    @Test
    void sharedWindowSupportsLongMaximumWithoutIdleCredit() {
        DomainTransferWindow window = new DomainTransferWindow();
        assertEquals(Long.MAX_VALUE, window.available(0, Long.MAX_VALUE));
        window.moved(0, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(0, window.available(1000, Long.MAX_VALUE));
        window.finish(1000, 5);
        assertEquals(0, window.available(1004, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, window.available(1005, Long.MAX_VALUE));
    }
}
