// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.recovery.RecoveryBuffer;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.fixtures.FakeEnergyStorage;
import io.github.loongin.omniresonance.transfer.fixtures.SchedulerResourcePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainOutputTypedFilterTest {
    private static final ResourceLocation CHEMICAL = ResourceLocation.parse("mekanism:chemical");
    private static final ResourceLocation HYDROGEN = ResourceLocation.parse("mekanism:hydrogen");
    private static final UUID NETWORK = new UUID(11, 1), NODE = new UUID(11, 2), PRESET = new UUID(11, 3);

    @Test
    void energyAndChemicalsRequireMatchingResourceTypesAndThenReachTheTarget() {
        for (boolean chemical : new boolean[] {false, true}) {
            for (boolean correct : new boolean[] {false, true}) {
                var type = chemical ? CHEMICAL : ResourceTypes.ENERGY;
                ResourceVariant variant = chemical ? new Chemical() : EnergyVariant.INSTANCE;
                var energy = new FakeEnergyStorage(10000, 0);
                var tank = new SchedulerResourcePort(type, 1, 0, new ArrayList<>());
                tank.variant = variant;
                ResourcePort port = chemical ? tank : new EnergyResourcePort(energy);
                var ledger = new DomainLedger(NETWORK, Map.of(), index -> StorageBucketData.create(NETWORK, index));
                try (var d = ledger.reserveDeposit(variant.key(), 1000, -1).orElseThrow()) {
                    d.commit(1000);
                }
                var selector = correct
                        ? chemical
                                ? ResourceFilterRule.Selector.exact(HYDROGEN)
                                : ResourceFilterRule.Selector.wholeType()
                        : chemical
                                ? ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:hydrogen"))
                                : ResourceFilterRule.Selector.exact(ResourceTypes.ENERGY);
                var rule = new ResourceFilterRule.Match(
                        new UUID(11, 4),
                        correct ? type : chemical ? ResourceTypes.FLUID : ResourceTypes.ITEM,
                        selector,
                        ComponentCondition.idOnly());
                var compiled = ResourceFilterCompiler.compile(
                        PRESET,
                        new ResourceFilterCompiler.OwnerSnapshot(
                                NETWORK,
                                Map.of(
                                        PRESET,
                                        new ResourceFilterPreset(PRESET, new ManagedName("Typed"), 0, List.of(rule)))),
                        (a, b) -> ResourceFilterCompiler.TagSnapshot.missing(0));
                var recovery = new RecoveryBuffer(() -> {});
                var handle = new ResourceTransferEngine.Handle() {
                    public ResourcePort port() {
                        return port;
                    }

                    public Object physicalIdentity() {
                        return port;
                    }

                    public boolean valid() {
                        return true;
                    }
                };
                var environment = new DomainOutputScheduler.Environment() {
                    public List<ResourceLocation> types() {
                        return List.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY, CHEMICAL);
                    }

                    public boolean active(DomainOutputScheduler.Configuration c) {
                        return true;
                    }

                    public int faces(DomainOutputScheduler.Configuration c) {
                        return 1;
                    }

                    public ResourceTransferEngine.Handle resolve(
                            DomainOutputScheduler.Configuration c,
                            ResourceLocation t,
                            Direction f,
                            TransferWorkBudget b) {
                        return handle;
                    }

                    public DomainLedger ledger(UUID id) {
                        return ledger;
                    }

                    public ResourceVariant decode(ResourceVariantKey key) {
                        return variant.key().equals(key) ? variant : null;
                    }

                    public RecoveryBuffer recovery(UUID id) {
                        return recovery;
                    }

                    public ResourceDirectScheduler.FilterView filter(DomainOutputScheduler.Configuration c) {
                        return new ResourceDirectScheduler.FilterView(compiled, compiled);
                    }

                    public int advanceFilter(DomainOutputScheduler.Configuration c, int units) {
                        return 0;
                    }
                };
                var scheduler = new DomainOutputScheduler(environment);
                var policy = new ResourceTransferPolicy.Output(
                        1, ResourceScope.all(), RedstoneCondition.IGNORE, PRESET, FilterMode.WHITELIST, Map.of(), 0);
                scheduler.replaceNetwork(
                        NETWORK,
                        List.of(new DomainOutputScheduler.Configuration(
                                NETWORK, NODE, 0, policy, WorkingFaces.explicit(1))),
                        0);
                for (int tick = 0; tick < 10; tick++) {
                    var budget = new TransferWorkBudget(1000, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
                    for (int step = 0; step < 200 && budget.canStart(); step++)
                        if (scheduler.step(NODE, tick, ServerSettings.defaults(), budget) > tick) break;
                }
                long expected = correct ? 1000 : 0;
                assertEquals(expected, chemical ? tank.amounts[0] : energy.getEnergyStored());
                assertEquals(1000 - expected, ledger.amount(variant.key()));
            }
        }
    }

    private static final class Chemical implements RegisteredResourceVariant {
        public ResourceVariantKey key() {
            return new ResourceVariantKey(CHEMICAL, new byte[] {1});
        }

        public ResourceLocation resourceId() {
            return HYDROGEN;
        }

        public Component displayName() {
            return Component.literal("Hydrogen");
        }

        public ResourceLocation texture() {
            return HYDROGEN;
        }

        public int tint() {
            return 0xFFFFFF;
        }

        public List<ResourceLocation> tags() {
            return List.of();
        }

        public Object recipeIngredient() {
            return this;
        }
    }
}
