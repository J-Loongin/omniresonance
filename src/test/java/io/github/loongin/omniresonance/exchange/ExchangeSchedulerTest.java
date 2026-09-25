// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import io.github.loongin.omniresonance.transfer.ResourceVariant;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.github.loongin.omniresonance.transfer.TransferWorkBudget;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeSchedulerTest {
    private static final ResourceFilterCompiler.Tags TAGS =
            (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0);

    private static TransferWorkBudget budget() {
        return new TransferWorkBudget(1, 100000, 1000, () -> 0);
    }

    private static final class Env implements ExchangeTypeWork.Environment {
        final Map<UUID, ExchangeAgreement> agreements = new HashMap<>();
        final Map<UUID, DomainLedger> ledgers = new HashMap<>();
        boolean failDecode;
        int decodeCalls;

        public boolean active(ExchangeAgreement agreement) {
            return agreements.get(agreement.id()) == agreement;
        }

        public DomainLedger source(ExchangeAgreement agreement) {
            return ledger(agreement.consent().sourceNetwork());
        }

        public DomainLedger target(ExchangeAgreement agreement) {
            return ledger(agreement.consent().targetNetwork());
        }

        public ResourceVariant decode(ResourceVariantKey key) {
            decodeCalls++;
            if (failDecode) throw new IllegalStateException("Injected decoder failure");
            return key.equals(EnergyVariant.INSTANCE.key()) ? EnergyVariant.INSTANCE : () -> key;
        }

        DomainLedger ledger(UUID id) {
            return ledgers.computeIfAbsent(
                    id, network -> new DomainLedger(network, Map.of(), i -> StorageBucketData.create(network, i)));
        }

        ExchangeAgreement agreement(int index, long rate) {
            UUID id = new UUID(100, index), source = new UUID(101, index), target = new UUID(102, index);
            ExchangeAgreement agreement = new ExchangeAgreement(
                    id,
                    new ExchangeConsent(
                            new UUID(103, index),
                            source,
                            target,
                            new UUID(104, 1),
                            new UUID(104, 2),
                            1,
                            0,
                            new ExchangeConsent.Approval(true, false),
                            new ExchangeConsent.Approval(true, false),
                            false),
                    new ExchangeTerms(
                            ResourceScope.all(),
                            FilterMode.WHITELIST,
                            io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources(),
                            rate,
                            Map.of(),
                            5));
            agreements.put(id, agreement);
            try (var d = ledger(source)
                    .reserveDeposit(EnergyVariant.INSTANCE.key(), 1000, -1)
                    .orElseThrow()) {
                d.commit(1000);
            }
            ledger(target);
            return agreement;
        }

        long received(ExchangeAgreement agreement) {
            return ledger(agreement.consent().targetNetwork()).amount(EnergyVariant.INSTANCE.key());
        }
    }

    private static ExchangeAgreement revised(ExchangeAgreement original, long revision, ResourceScope scope) {
        var c = original.consent();
        return new ExchangeAgreement(
                original.id(),
                new ExchangeConsent(
                        c.invitationId(),
                        c.sourceNetwork(),
                        c.targetNetwork(),
                        c.sourceOwner(),
                        c.targetOwner(),
                        revision,
                        revision - 1,
                        c.source(),
                        c.target(),
                        false),
                new ExchangeTerms(
                        scope,
                        FilterMode.WHITELIST,
                        original.terms().filter(),
                        original.terms().defaultRate(),
                        Map.of(),
                        5));
    }

    @Test
    void allTypesShareOneProtocolFilterPreparationAndTagInvalidation() {
        Env env = new Env();
        var original = env.agreement(1, 1);
        var presetId = new UUID(200, 1);
        var tagId = net.minecraft.resources.ResourceLocation.parse("c:shared");
        var rules = List.<io.github.loongin.omniresonance.filter.ResourceFilterRule>of(
                new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                        new UUID(200, 2),
                        ResourceTypes.ENERGY,
                        io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                        io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()),
                new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                        new UUID(200, 3),
                        ResourceTypes.ITEM,
                        io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.tag(tagId),
                        io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()));
        var preset = new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                presetId, new io.github.loongin.omniresonance.network.ManagedName("Shared"), 0, rules);
        var filter = ExchangeFilterSnapshot.capture(presetId, Map.of(presetId, preset), 1, 2);
        var agreement = new ExchangeAgreement(
                original.id(),
                original.consent(),
                new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, filter, 1, Map.of(), 5));
        env.agreements.put(agreement.id(), agreement);
        var resolutions = new java.util.concurrent.atomic.AtomicInteger();
        var scheduler = new ExchangeScheduler(env, List.of(ResourceTypes.ENERGY, ResourceTypes.ITEM), 1, 2);
        scheduler.publish(
                agreement,
                (type, tag) -> {
                    resolutions.incrementAndGet();
                    return new ResourceFilterCompiler.TagSnapshot(
                            true, 0, List.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot")));
                },
                0);
        scheduler.run(0, 200, budget(), -1);
        assertEquals(1, resolutions.get());
        assertEquals(1, env.received(agreement));
        scheduler.invalidateTags(
                agreement.id(),
                (type, tag) -> {
                    resolutions.incrementAndGet();
                    return ResourceFilterCompiler.TagSnapshot.missing(1);
                },
                5);
        scheduler.run(5, 200, budget(), -1);
        assertEquals(2, resolutions.get());
        assertEquals(1, env.received(agreement));
    }

    @Test
    void changingScopeRetainsRemovedTypesQuotaAndWindowBound() {
        Env env = new Env();
        var other = net.minecraft.resources.ResourceLocation.parse("example:gas");
        var scheduler = new ExchangeScheduler(env, List.of(ResourceTypes.ENERGY, other), 2, 2);
        var first = env.agreement(1, 7);
        scheduler.publish(first, TAGS, 0);
        scheduler.run(0, 100, budget(), -1);
        assertEquals(7, env.received(first));
        var narrow = revised(first, 2, ResourceScope.customSet(List.of(other)));
        env.agreements.put(first.id(), narrow);
        scheduler.publish(narrow, TAGS, 0);
        var restored = revised(first, 3, ResourceScope.all());
        env.agreements.put(first.id(), restored);
        scheduler.publish(restored, TAGS, 0);
        scheduler.run(0, 100, budget(), -1);
        assertEquals(7, env.received(first));
        assertEquals(2, scheduler.windowCount());
        assertThrows(IllegalStateException.class, () -> scheduler.publish(env.agreement(2, 1), TAGS, 0));
        assertEquals(1, scheduler.protocolCount());
    }

    @Test
    void failedWorkRetainsEvidenceWithoutAutomaticRetry() {
        Env env = new Env();
        env.failDecode = true;
        var scheduler = new ExchangeScheduler(env, List.of(ResourceTypes.ENERGY), 1, 1);
        var first = env.agreement(1, 1);
        scheduler.publish(first, TAGS, 0);
        scheduler.run(0, 100, budget(), -1);
        assertTrue(scheduler.failure(first.id(), ResourceTypes.ENERGY) != null);
        var source = scheduler.telemetrySnapshot(first.consent().sourceNetwork(), 0);
        var target = scheduler.telemetrySnapshot(first.consent().targetNetwork(), 0);
        assertEquals(source.incident(), target.incident());
        assertEquals(first.id(), source.incident().channel());
        assertEquals(ExchangeTelemetry.Reason.DECODE_FAILED, source.incident().reason());
        assertEquals(ExchangeTelemetry.Stage.DECODE, source.incident().stage());
        assertEquals(0, source.sent());
        assertEquals(1, env.decodeCalls);
        assertEquals(0, scheduler.run(100, 1000, budget(), -1).used());
        scheduler.invalidateTags(first.id(), TAGS, 100);
        assertEquals(0, scheduler.run(101, 1000, budget(), -1).used());
        assertEquals(1, env.decodeCalls);
    }

    @Test
    void protocolsWithDifferentTypeCountsBothProgress() {
        Env env = new Env();
        var other = net.minecraft.resources.ResourceLocation.parse("example:gas");
        var key = new ResourceVariantKey(other, new byte[] {1});
        var scheduler = new ExchangeScheduler(env, List.of(ResourceTypes.ENERGY, other), 2, 4);
        var original = env.agreement(1, 1);
        var first = new ExchangeAgreement(
                original.id(),
                original.consent(),
                new ExchangeTerms(
                        ResourceScope.all(),
                        FilterMode.WHITELIST,
                        io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.resources(
                                ResourceTypes.ENERGY, other),
                        1,
                        Map.of(),
                        5));
        env.agreements.put(first.id(), first);
        var second = revised(env.agreement(2, 1), 2, ResourceScope.customSet(List.of(ResourceTypes.ENERGY)));
        env.agreements.put(second.id(), second);
        try (var d = env.source(first).reserveDeposit(key, 100, -1).orElseThrow()) {
            d.commit(100);
        }
        scheduler.publish(first, TAGS, 0);
        scheduler.publish(second, TAGS, 0);
        for (int tick = 0; tick < 100; tick++) scheduler.run(tick, 1, budget(), -1);
        assertTrue(env.received(first) > 0);
        assertTrue(env.received(second) > 0);
        assertTrue(env.target(first).amount(key) > 0);
    }

    @Test
    void oneUnitBudgetsReachEveryProtocolAndRemovalLeavesNoQueuedWork() {
        Env env = new Env();
        var scheduler = new ExchangeScheduler(env, List.of(ResourceTypes.ENERGY), 128, 128);
        for (int i = 0; i < 128; i++) scheduler.publish(env.agreement(i, 1), TAGS, 0);
        for (int tick = 0; tick < 128 * 64; tick++)
            assertTrue(scheduler.run(tick, 1, budget(), -1).used() <= 1);
        for (var agreement : env.agreements.values()) {
            assertTrue(env.received(agreement) > 0);
            scheduler.remove(agreement.id());
        }
        assertEquals(0, scheduler.protocolCount());
        assertEquals(0, scheduler.windowCount());
        assertEquals(0, scheduler.run(128 * 64 + 1, 1000, budget(), -1).used());
    }

    @Test
    void publicationRetainsSpentCreditAndStaleRevisionCannotReplaceCurrent() {
        Env env = new Env();
        var scheduler = new ExchangeScheduler(env, List.of(ResourceTypes.ENERGY), 2, 2);
        var first = env.agreement(1, 7);
        scheduler.publish(first, TAGS, 0);
        scheduler.run(0, 100, budget(), -1);
        assertEquals(7, env.received(first));
        var c = first.consent();
        var revised = new ExchangeAgreement(
                first.id(),
                new ExchangeConsent(
                        c.invitationId(),
                        c.sourceNetwork(),
                        c.targetNetwork(),
                        c.sourceOwner(),
                        c.targetOwner(),
                        2,
                        1,
                        c.source(),
                        c.target(),
                        false),
                first.terms());
        env.agreements.put(revised.id(), revised);
        scheduler.publish(revised, TAGS, 0);
        scheduler.run(0, 100, budget(), -1);
        assertEquals(7, env.received(first));
        assertThrows(IllegalStateException.class, () -> scheduler.publish(first, TAGS, 0));
        scheduler.run(5, 100, budget(), -1);
        assertEquals(14, env.received(first));
    }

    @Test
    void pendingProtocolsHaveNoWorkAndCapacityFailureKeepsExistingQueue() {
        Env env = new Env();
        var scheduler = new ExchangeScheduler(env, List.of(ResourceTypes.ENERGY), 1, 1);
        var first = env.agreement(1, 1);
        scheduler.publish(first, TAGS, 0);
        assertThrows(IllegalStateException.class, () -> scheduler.publish(env.agreement(2, 1), TAGS, 0));
        scheduler.run(0, 100, budget(), -1);
        assertEquals(1, env.received(first));
        var c = first.consent();
        var pending = new ExchangeAgreement(
                first.id(),
                new ExchangeConsent(
                        c.invitationId(),
                        c.sourceNetwork(),
                        c.targetNetwork(),
                        c.sourceOwner(),
                        c.targetOwner(),
                        2,
                        1,
                        c.source(),
                        new ExchangeConsent.Approval(false, false),
                        false),
                first.terms());
        env.agreements.put(first.id(), pending);
        scheduler.publish(pending, TAGS, 1);
        assertEquals(0, scheduler.run(100, 1000, budget(), -1).used());
        assertEquals(1, scheduler.windowCount());
        scheduler.close();
        assertThrows(IllegalStateException.class, () -> scheduler.run(101, 1, budget(), -1));
    }
}
