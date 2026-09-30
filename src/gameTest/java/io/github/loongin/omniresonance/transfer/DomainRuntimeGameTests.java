// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.NodePersistentState;
import io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.registry.ModBlocks;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** World scenarios for DomainRuntimeGameTests. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DomainRuntimeGameTests {
    private DomainRuntimeGameTests() {}

    @GameTest(template = "bootstrap")
    public static void configuredDomainInputRunsAllNativeTypesAndLegacyPendingDoesNotRun(GameTestHelper helper)
            throws Exception {
        try (TransferWorldFixture f = new TransferWorldFixture(helper)) {
            UUID input = f.node(1, TransferDirection.INPUT);
            var node = f.data.findNode(input).orElseThrow();
            node = f.data.setNodeMode(input, node.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            node = f.data.setDomainConfiguration(input, node.revision(), TransferDirection.INPUT, false);
            var source = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var items = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
            items.stacks[0] = new ItemStack(Items.IRON_INGOT, 64);
            source.items = items;
            source.fluid.setFluid(new net.neoforged.neoforge.fluids.FluidStack(
                    net.minecraft.world.level.material.Fluids.WATER, 3000));
            source.energy.receiveEnergy(50000, false);
            var iron = ItemVariant.from(items.stacks[0], helper.getLevel().registryAccess());
            var water =
                    FluidVariant.from(source.fluid.getFluid(), helper.getLevel().registryAccess());
            f.sync();
            f.start();
            f.tick(0);
            helper.assertTrue(
                    items.stacks[0].getCount() == 64 && source.itemCalls == 0,
                    "Pending legacy domain input ran before full save");
            helper.assertTrue(
                    f.repository.domainStorage(f.network).state()
                            == io.github.loongin.omniresonance.persistence.DomainStorage.State.NOT_LOADED,
                    "Pending legacy domain activated storage");
            f.data.saveDomainConfiguration(
                    input,
                    node.revision(),
                    new StoredResourcePolicy(
                            ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false);
            f.sync();
            f.tick(1);
            var ledger = f.repository.domainStorage(f.network).activate().orElseThrow();
            helper.assertTrue(
                    ledger.amount(iron.key()) == 64
                            && ledger.amount(water.key()) == 3000
                            && ledger.amount(EnergyVariant.INSTANCE.key()) == 50000,
                    "Live domain input did not transfer all native resources");
            helper.assertTrue(
                    items.stacks[0].isEmpty() && source.fluid.isEmpty() && source.energy.getEnergyStored() == 0,
                    "Live domain input did not conserve source quantities");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void directInputPrecedesDomainFallbackAtTheSamePhysicalSource(GameTestHelper helper)
            throws Exception {
        for (boolean crossNetwork : new boolean[] {false, true}) {
            try (TransferWorldFixture f = new TransferWorldFixture(helper)) {
                UUID domainNetwork = f.network;
                NetworkSavedData domainData = f.data;
                if (crossNetwork) {
                    domainNetwork = new UUID(713, 1);
                    f.repository.createNetwork(new NetworkMetadata(
                            domainNetwork, f.owner, new ManagedName("Domain fallback"), 1, Set.of()));
                    domainData = f.repository.findLoadedNetwork(domainNetwork).orElseThrow();
                }
                UUID direct = f.node(1, TransferDirection.INPUT);
                f.node(4, TransferDirection.OUTPUT);
                f.policy(
                        direct,
                        new ItemTransferPolicy.Input(1, 64, RedstoneCondition.IGNORE, null, FilterMode.WHITELIST, 0));
                BlockPos sourcePosition = f.pos(1).below();
                f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
                f.chest(1).setItem(1, new ItemStack(Items.IRON_INGOT, 36));
                var iron = ItemVariant.from(
                        f.chest(1).getItem(0), helper.getLevel().registryAccess());
                BlockPos domainPosition = sourcePosition.east();
                f.positions.add(domainPosition);
                helper.getLevel()
                        .setBlock(
                                domainPosition,
                                ModBlocks.RESONANCE_TRANSFER_NODE
                                        .get()
                                        .defaultBlockState()
                                        .setValue(AbstractResonanceNodeBlock.FACING, Direction.WEST),
                                3);
                var entity = (ResonanceNodeBlockEntity) helper.getLevel().getBlockEntity(domainPosition);
                UUID id = entity.state().orElseThrow().nodeId();
                CompoundTag tag = new CompoundTag();
                NodePersistentState.linked(id).writeOwnedFields(tag);
                entity.loadCustomOnly(tag, helper.getLevel().registryAccess());
                var node = domainData.createNode(
                        id,
                        new ManagedName("Domain input"),
                        GlobalPos.of(helper.getLevel().dimension(), domainPosition),
                        NodeForm.BLOCK,
                        Direction.WEST);
                node = domainData
                        .setNodeMode(id, node.revision(), NodeMode.DOMAIN, false)
                        .orElseThrow();
                node = domainData.saveDomainConfiguration(
                        id,
                        node.revision(),
                        new StoredResourcePolicy(
                                ResourceTransferPolicy.defaults(TransferDirection.INPUT), java.util.Map.of()),
                        WorkingFaces.explicit(1 << Direction.WEST.get3DDataValue()),
                        false);
                f.nodes.add(new NetworkNodeDirectory.Entry(domainNetwork, node));
                f.start();
                f.tick(0);
                var ledger =
                        f.repository.domainStorage(domainNetwork).activate().orElseThrow();
                helper.assertTrue(
                        f.chest(4).getItem(0).getCount() == 64, "Domain input stole direct input's due allowance");
                helper.assertTrue(
                        ledger.amount(iron.key()) == 36
                                && f.chest(1).getItem(0).isEmpty()
                                && f.chest(1).getItem(1).isEmpty(),
                        "Domain did not receive the remaining source resources");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void domainInputFilterBlocksThenWakesAfterOwnerLibraryCreation(GameTestHelper helper)
            throws Exception {
        try (TransferWorldFixture f = new TransferWorldFixture(helper)) {
            UUID id = f.node(1, TransferDirection.INPUT);
            var node = f.data.findNode(id).orElseThrow();
            node = f.data.setNodeMode(id, node.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            UUID preset = new UUID(710, 1);
            var policy = new ResourceTransferPolicy.Input(
                    1,
                    ResourceScope.all(),
                    RedstoneCondition.IGNORE,
                    preset,
                    FilterMode.WHITELIST,
                    java.util.Map.of(),
                    0);
            f.data.saveDomainConfiguration(
                    id,
                    node.revision(),
                    new StoredResourcePolicy(policy, java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false);
            f.chest(1).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            var key = ItemVariant.from(f.chest(1).getItem(0), helper.getLevel().registryAccess())
                    .key();
            f.sync();
            f.start();
            for (int tick = 0; tick < 5; tick++) f.tick(tick);
            helper.assertTrue(f.chest(1).getItem(0).getCount() == 64, "Missing domain filter was ignored");
            var library = f.repository.createOwner(f.owner, null);
            library.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset,
                            new ManagedName("Iron"),
                            0,
                            Set.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    0,
                    -1,
                    -1);
            f.runtime.ownerLibraryChanged(f.owner);
            for (int tick = 5; tick < 15; tick++) f.tick(tick);
            helper.assertTrue(
                    f.chest(1).getItem(0).isEmpty()
                            && f.repository
                                            .domainStorage(f.network)
                                            .activate()
                                            .orElseThrow()
                                            .amount(key)
                                    == 64,
                    "Domain filter did not resume after valid owner preset appeared");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void liveDomainOutputsAllocateByPriorityAndTransferAllNativeTypes(GameTestHelper helper)
            throws Exception {
        try (TransferWorldFixture f = new TransferWorldFixture(helper)) {
            UUID high = f.node(1, TransferDirection.OUTPUT), low = f.node(4, TransferDirection.OUTPUT);
            UUID preset = new UUID(720, 1);
            var owner = f.repository.createOwner(f.owner, null);
            owner.putPreset(
                    new io.github.loongin.omniresonance.filter.ItemFilterPreset(
                            preset,
                            new ManagedName("Iron"),
                            0,
                            Set.of(net.minecraft.resources.ResourceLocation.parse("minecraft:iron_ingot"))),
                    0,
                    -1,
                    -1);
            for (UUID id : List.of(high, low)) {
                var node = f.data.findNode(id).orElseThrow();
                node = f.data.setNodeMode(id, node.revision(), NodeMode.DOMAIN, true)
                        .orElseThrow();
                var policy = new ResourceTransferPolicy.Output(
                        1,
                        ResourceScope.customSet(Set.of(ResourceTypes.ITEM)),
                        RedstoneCondition.IGNORE,
                        preset,
                        FilterMode.WHITELIST,
                        java.util.Map.of(ResourceTypes.ITEM, new ResourceTransferPolicy.OutputOverride(100)),
                        id.equals(high) ? 10 : 0);
                f.data.saveDomainConfiguration(
                        id,
                        node.revision(),
                        new StoredResourcePolicy(policy, java.util.Map.of()),
                        WorkingFaces.explicit(1),
                        false);
            }
            var highTarget = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var lowTarget = ResourceEndpointGameTests.place(helper, f.pos(4).below());
            var highItems = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
            var lowItems = new io.github.loongin.omniresonance.transfer.fixtures.FakeItemHandler(1);
            highItems.capacity = 60;
            highTarget.items = highItems;
            lowTarget.items = lowItems;
            var iron = ItemVariant.from(
                    new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
            var ledger = f.repository.domainStorage(f.network).activate().orElseThrow();
            try (var deposit = ledger.reserveDeposit(iron.key(), 100, -1).orElseThrow()) {
                deposit.commit(100);
            }
            f.sync();
            f.start();
            for (int tick = 0; tick < 10; tick++) f.tick(tick);
            helper.assertTrue(
                    highItems.stacks[0].getCount() == 60 && lowItems.stacks[0].getCount() == 40,
                    "Live domain output priority did not allocate 60/40");
            helper.assertTrue(
                    ledger.amount(iron.key()) == 0 && f.data.recovery().isEmpty(),
                    "Live domain output lost known quantities");
        }
        try (TransferWorldFixture f = new TransferWorldFixture(helper)) {
            UUID id = f.node(1, TransferDirection.OUTPUT), preset = new UUID(720, 2);
            var node = f.data.findNode(id).orElseThrow();
            node = f.data.setNodeMode(id, node.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            var rules = new java.util.ArrayList<io.github.loongin.omniresonance.filter.ResourceFilterRule>();
            for (net.minecraft.resources.ResourceLocation type :
                    List.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY))
                rules.add(new io.github.loongin.omniresonance.filter.ResourceFilterRule.Match(
                        new UUID(721, rules.size()),
                        type,
                        io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType(),
                        io.github.loongin.omniresonance.filter.ComponentCondition.idOnly()));
            f.repository
                    .createOwner(f.owner, null)
                    .putPreset(
                            new io.github.loongin.omniresonance.filter.ResourceFilterPreset(
                                    preset, new ManagedName("All resources"), 0, rules),
                            0,
                            -1,
                            -1);
            f.data.saveDomainConfiguration(
                    id,
                    node.revision(),
                    new StoredResourcePolicy(
                            new ResourceTransferPolicy.Output(
                                    1,
                                    ResourceScope.all(),
                                    RedstoneCondition.IGNORE,
                                    preset,
                                    FilterMode.WHITELIST,
                                    java.util.Map.of(),
                                    0),
                            java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    false);
            var target = ResourceEndpointGameTests.place(helper, f.pos(1).below());
            var iron = ItemVariant.from(
                    new ItemStack(Items.IRON_INGOT), helper.getLevel().registryAccess());
            var water = FluidVariant.from(
                    new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1),
                    helper.getLevel().registryAccess());
            var ledger = f.repository.domainStorage(f.network).activate().orElseThrow();
            ResourceVariant[] variants = {iron, water, EnergyVariant.INSTANCE};
            long[] amounts = {64, 3000, 50000};
            for (int i = 0; i < variants.length; i++)
                try (var deposit =
                        ledger.reserveDeposit(variants[i].key(), amounts[i], -1).orElseThrow()) {
                    deposit.commit(amounts[i]);
                }
            f.sync();
            f.start();
            for (int tick = 0; tick < 10; tick++) f.tick(tick);
            helper.assertTrue(
                    target.items.getStackInSlot(0).getCount() == 64
                            && target.fluid.getFluidAmount() == 3000
                            && target.energy.getEnergyStored() == 50000
                            && ledger.variantCount() == 0,
                    "Live output did not transfer all native types");
        }
        helper.succeed();
    }
}
