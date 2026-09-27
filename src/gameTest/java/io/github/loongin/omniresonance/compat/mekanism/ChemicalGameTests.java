// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.mekanism;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalGameTests {
    private ChemicalGameTests() {}

    @GameTest(template = "bootstrap")
    public static void chemicalAdapterIsOptionalAndPreservesLongIdentity(GameTestHelper h) {
        var adapters = io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create();
        var id = net.minecraft.resources.ResourceLocation.parse("mekanism:chemical");
        if (!net.neoforged.fml.ModList.get().isLoaded("mekanism")) {
            h.assertTrue(adapters.find(id).isEmpty(), "Chemical registered without Mekanism");
            h.succeed();
        } else Present.run(h);
    }

    @GameTest(template = "bootstrap", timeoutTicks = 160)
    public static void realChemicalTankCarrierAndDomainRoundTrip(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("mekanism")) {
            h.succeed();
            return;
        }
        Flow.run(h);
    }

    private static final class Flow {
        static void run(GameTestHelper h) {
            var pos = new net.minecraft.core.BlockPos(2, 2, 2);
            var tankId = net.minecraft.resources.ResourceLocation.parse("mekanism:basic_chemical_tank");
            h.setBlock(pos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(tankId));
            h.getLevel()
                    .getBlockEntity(h.absolutePos(pos))
                    .applyComponentsFromItemStack(new net.minecraft.world.item.ItemStack(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(tankId)));
            h.runAfterDelay(5, () -> {
                var hydrogen = mekanism.api.MekanismAPI.CHEMICAL_REGISTRY
                        .getHolder(net.minecraft.resources.ResourceLocation.parse("mekanism:hydrogen"))
                        .orElseThrow();
                var variant = ChemicalVariant.from(new mekanism.api.chemical.ChemicalStack(hydrogen, 1));
                mekanism.api.chemical.IChemicalHandler input = null;
                for (var side : net.minecraft.core.Direction.values()) {
                    var candidate = h.getLevel().getCapability(MekanismResources.BLOCK, h.absolutePos(pos), side);
                    if (candidate != null
                            && candidate
                                    .insertChemical(variant.stack(1), mekanism.api.Action.SIMULATE)
                                    .isEmpty()) {
                        input = candidate;
                        break;
                    }
                }
                h.assertTrue(input != null, "Real tank has no insertion face");
                var inputPort = new ChemicalResourcePort(input);
                inputPort.targetViews(budget());
                h.assertTrue(inputPort.insert(0, variant, 1, false, budget()) == 1, "Real tank refused hydrogen");
                mekanism.api.chemical.IChemicalHandler output = null;
                net.minecraft.core.Direction outputSide = null;
                for (var side : net.minecraft.core.Direction.values()) {
                    var candidate = h.getLevel().getCapability(MekanismResources.BLOCK, h.absolutePos(pos), side);
                    if (candidate != null
                            && candidate
                                            .extractChemical(0, 1, mekanism.api.Action.SIMULATE)
                                            .getAmount()
                                    == 1) {
                        output = candidate;
                        outputSide = side;
                        break;
                    }
                }
                h.assertTrue(output != null, "Real tank has no extraction face");
                var nativeTank = output;
                var port = new ChemicalResourcePort(nativeTank);
                var id = java.util.UUID.randomUUID();
                var ledger = new io.github.loongin.omniresonance.storage.DomainLedger(
                        id,
                        java.util.Map.of(),
                        index -> io.github.loongin.omniresonance.persistence.StorageBucketData.create(id, index));
                var recovery = new io.github.loongin.omniresonance.recovery.RecoveryBuffer(() -> {});
                var nodePos = h.absolutePos(pos).relative(outputSide);
                var facing = outputSide.getOpposite();
                h.getLevel()
                        .setBlock(
                                nodePos,
                                io.github.loongin.omniresonance.registry.ModBlocks.RESONANCE_TRANSFER_NODE
                                        .get()
                                        .defaultBlockState()
                                        .setValue(
                                                io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock.FACING,
                                                facing),
                                18);
                var nodeEntity = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        h.getLevel().getBlockEntity(nodePos);
                var nodeId = nodeEntity.state().orElseThrow().nodeId();
                var tag = new net.minecraft.nbt.CompoundTag();
                io.github.loongin.omniresonance.node.NodePersistentState.linked(nodeId)
                        .writeOwnedFields(tag);
                nodeEntity.loadCustomOnly(tag, h.getLevel().registryAccess());
                var node = io.github.loongin.omniresonance.node.NetworkNodeRecord.fresh(
                        nodeId,
                        1,
                        new io.github.loongin.omniresonance.network.ManagedName("Chemical node"),
                        net.minecraft.core.GlobalPos.of(h.getLevel().dimension(), nodePos),
                        io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                        facing);
                var cache = new io.github.loongin.omniresonance.transfer.ResourceEndpointCache(
                        h.getLevel().getServer(),
                        io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create(),
                        8);
                try (cache) {
                    var handle = cache.resolve(node, facing, ChemicalVariant.TYPE, budget());
                    h.assertTrue(
                            handle != null && handle.valid(),
                            "Selected node face did not discover real chemical capability");
                    var moved = new io.github.loongin.omniresonance.transfer.DomainTransferEngine()
                            .depositGreedy(
                                    handle,
                                    0,
                                    ledger,
                                    variant,
                                    1,
                                    -1,
                                    () -> true,
                                    recovery,
                                    io.github.loongin.omniresonance.config.ServerSettings.RecoveryLimits.defaults(),
                                    budget());
                    h.assertTrue(
                            moved.moved() == 1
                                    && ledger.amount(variant.key()) == 1
                                    && nativeTank.getChemicalInTank(0).isEmpty(),
                            "Tank/domain transfer did not conserve hydrogen");
                    h.getLevel().invalidateCapabilities(h.absolutePos(pos));
                    h.assertTrue(!handle.valid(), "Chemical endpoint survived native capability invalidation");
                }

                var adapters = io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create();
                h.assertTrue(
                        adapters.decode(variant.key(), h.getLevel().registryAccess())
                                .orElseThrow()
                                .key()
                                .equals(variant.key()),
                        "Runtime directory lost chemical identity");
                h.assertTrue(
                        io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults()
                                .decode(variant.key(), h.getLevel().registryAccess())
                                .isEmpty(),
                        "Missing adapter silently decoded a chemical");
                h.assertTrue(
                        io.github.loongin.omniresonance.filter.FilterResourceSample.idOnly(variant)
                                .resourceId()
                                .equals("mekanism:hydrogen"),
                        "Chemical exact selector lost its ID");
                var player = new net.neoforged.neoforge.common.util.FakePlayer(
                        h.getLevel(),
                        new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ChemicalCarrier"));
                player.inventoryMenu.setCarried(new net.minecraft.world.item.ItemStack(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.get(tankId)));
                var result = new io.github.loongin.omniresonance.transfer.TerminalStorageOperation(
                                -1, ledger.sequence(variant.key()), 0, false)
                        .step(
                                player,
                                ledger,
                                recovery,
                                io.github.loongin.omniresonance.config.ServerSettings.defaults(),
                                () -> true,
                                budget());
                h.assertTrue(
                        result.moved() == 1 && ledger.amount(variant.key()) == 0,
                        "Terminal failed to fill chemical carrier: " + result.status());
                var held = player.inventoryMenu.getCarried().getCapability(MekanismResources.ITEM);
                h.assertTrue(
                        held != null && held.getChemicalInTank(0).getAmount() == 1,
                        "Carrier settlement lost chemical contents");
                var returned = new io.github.loongin.omniresonance.transfer.TerminalStorageOperation(-1, 0, 1, true)
                        .step(
                                player,
                                ledger,
                                recovery,
                                io.github.loongin.omniresonance.config.ServerSettings.defaults(),
                                () -> true,
                                budget());
                h.assertTrue(
                        returned.moved() == 1 && ledger.amount(variant.key()) == 1,
                        "First chemical carrier deposit failed: " + returned.status());
                h.assertTrue(
                        player.inventoryMenu
                                .getCarried()
                                .getCapability(MekanismResources.ITEM)
                                .getChemicalInTank(0)
                                .isEmpty(),
                        "Chemical carrier deposit duplicated inventory");
                if (net.neoforged.fml.ModList.get().isLoaded("ae2")) Bridge.verify(h, ledger, variant);
                h.succeed();
            });
        }

        static io.github.loongin.omniresonance.transfer.TransferWorkBudget budget() {
            return new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                    1000, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
        }
    }

    private static final class Bridge {
        static void verify(
                GameTestHelper h,
                io.github.loongin.omniresonance.storage.DomainLedger ledger,
                ChemicalVariant variant) {
            var storage = new io.github.loongin.omniresonance.compat.ae2.Ae2DomainStorage(
                    new io.github.loongin.omniresonance.compat.ae2.Ae2DomainAccess(() -> ledger, () -> -1),
                    () -> h.getLevel().registryAccess());
            var key = io.github.loongin.omniresonance.compat.ae2.AeResourceKeys.canonical(variant.key());
            var player = new net.neoforged.neoforge.common.util.FakePlayer(
                    h.getLevel(), new com.mojang.authlib.GameProfile(new java.util.UUID(216, 1), "AeChemicalCarrier"));
            player.initInventoryMenu();
            var tank = new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.parse("mekanism:basic_chemical_tank")));
            player.inventoryMenu.setCarried(tank);
            var context = appeng.api.behaviors.ContainerItemStrategies.findCarriedContextForKey(
                    key, player, player.inventoryMenu);
            h.assertTrue(context != null, "AE chemical carrier strategy missing");
            var snapshot = tank.copy();
            long capacity = context.insert(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE);
            h.assertTrue(
                    capacity > 0 && net.minecraft.world.item.ItemStack.matches(snapshot, tank),
                    "AE chemical simulation changed tank");
            h.assertTrue(
                    context.insert(key, capacity, appeng.api.config.Actionable.MODULATE) == capacity,
                    "AE chemical carrier fill failed");
            h.assertTrue(
                    context.getExtractableContent().what().equals(key)
                            && context.getExtractableContent().amount() == capacity,
                    "AE chemical carrier reported wrong independent identity or quantity");
            h.assertTrue(
                    context.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE) == capacity
                            && context.getExtractableContent().amount() == capacity,
                    "AE chemical extraction simulation mutated tank");
            h.assertTrue(
                    context.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.MODULATE) == capacity
                            && context.getExtractableContent() == null
                            && player.inventoryMenu.getCarried().getCount() == 1,
                    "AE chemical drain lost empty tank or failed to settle");
            var selection = new appeng.api.util.KeyTypeSelection(() -> {}, type -> true);
            var saved = new net.minecraft.nbt.CompoundTag();
            var enabled = new net.minecraft.nbt.ListTag();
            enabled.add(net.minecraft.nbt.StringTag.valueOf("ae2:i"));
            enabled.add(net.minecraft.nbt.StringTag.valueOf("ae2:f"));
            saved.put("enabledKeyTypes", enabled);
            selection.readFromNBT(saved, h.getLevel().registryAccess());
            h.assertTrue(
                    !selection.isEnabled(key.getType()),
                    "Legacy terminal selection unexpectedly enabled chemical keys");
            selection.setEnabled(key.getType(), true);
            h.assertTrue(selection.isEnabled(key.getType()), "Chemical terminal type could not be enabled");
            long amount = (long) Integer.MAX_VALUE + 17;
            var before = ledger.amount(variant.key());
            h.assertTrue(
                    storage.insert(
                                            key,
                                            amount,
                                            appeng.api.config.Actionable.SIMULATE,
                                            appeng.api.networking.security.IActionSource.empty())
                                    == amount
                            && ledger.amount(variant.key()) == before,
                    "AE chemical simulation mutated inventory");
            h.assertTrue(
                    storage.insert(
                                    key,
                                    amount,
                                    appeng.api.config.Actionable.MODULATE,
                                    appeng.api.networking.security.IActionSource.empty())
                            == amount,
                    "AE chemical insert truncated long amount");
            var counter = new appeng.api.stacks.KeyCounter();
            storage.getAvailableStacks(counter);
            h.assertTrue(counter.get(key) == amount + before, "AE chemical enumeration lost quantity");
            h.assertTrue(
                    storage.extract(
                                            key,
                                            amount,
                                            appeng.api.config.Actionable.MODULATE,
                                            appeng.api.networking.security.IActionSource.empty())
                                    == amount
                            && ledger.amount(variant.key()) == before,
                    "AE chemical extraction lost identity or quantity");
        }
    }

    private static final class Present {
        static void run(GameTestHelper h) {
            var chemical = mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.get(
                    net.minecraft.resources.ResourceLocation.parse("mekanism:hydrogen"));
            var variant = ChemicalVariant.from(new mekanism.api.chemical.ChemicalStack(
                    mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(chemical), Long.MAX_VALUE));
            h.assertTrue(
                    variant.key().equals(ChemicalVariant.from(variant.stack(1)).key()),
                    "Amount changed chemical identity");
            h.assertTrue(
                    ChemicalVariant.restore(variant.key()).stack(Long.MAX_VALUE).getAmount() == Long.MAX_VALUE,
                    "Chemical codec truncated quantity");
            var nuclear = mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.get(
                    net.minecraft.resources.ResourceLocation.parse("mekanism:nuclear_waste"));
            var waste = ChemicalVariant.from(new mekanism.api.chemical.ChemicalStack(
                    mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(nuclear), 1));
            var context = net.minecraft.world.item.Item.TooltipContext.of(h.getLevel());
            var nativeLines = new java.util.ArrayList<net.minecraft.network.chat.Component>();
            waste.stack(1).appendHoverText(context, nativeLines, net.minecraft.world.item.TooltipFlag.Default.NORMAL);
            h.assertTrue(!nativeLines.isEmpty(), "Native nuclear waste radiation tooltip is missing");
            h.assertTrue(
                    waste.tooltipLines(context).equals(nativeLines),
                    "Chemical tooltip lost native radiation details or styles");
            var source = new Tank(chemical, Long.MAX_VALUE);
            var port = new ChemicalResourcePort(source);
            var budget = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                    100, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
            port.sourceViews(budget);
            port.targetViews(budget);
            h.assertTrue(
                    port.extract(0, variant, Long.MAX_VALUE, true, budget) == Long.MAX_VALUE
                            && source.amount == Long.MAX_VALUE,
                    "Chemical simulation mutated or truncated source");
            h.assertTrue(
                    port.extract(0, variant, Long.MAX_VALUE, false, budget) == Long.MAX_VALUE && source.amount == 0,
                    "Chemical long extraction failed");
            h.assertTrue(
                    port.insert(0, variant, Long.MAX_VALUE, false, budget) == Long.MAX_VALUE
                            && source.amount == Long.MAX_VALUE,
                    "Chemical remainder was mistaken for acceptance");
            h.assertTrue(budget.calls() <= 6, "Chemical work grew with quantity");
            h.assertTrue(
                    port.insert(0, variant, 1, true, budget) == 0 && source.amount == Long.MAX_VALUE,
                    "Full chemical tank did not refuse without mutation");
            long calls = budget.calls();
            boolean invalidView = false;
            try {
                port.extract(1, variant, 1, false, budget);
            } catch (IllegalArgumentException expected) {
                invalidView = true;
            }
            h.assertTrue(invalidView && budget.calls() == calls, "Invalid tank reached a native mutation");
            var malformed = new net.minecraft.nbt.CompoundTag();
            malformed.putString("id", "omniresonance:missing_chemical");
            var missing = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                    ChemicalVariant.TYPE,
                    io.github.loongin.omniresonance.transfer.CanonicalResourceNbt.encode(malformed));
            h.assertTrue(
                    io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create()
                            .decode(missing, h.getLevel().registryAccess())
                            .isEmpty(),
                    "Missing chemical silently became the registry default");
            var tags = io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create();
            h.assertTrue(
                    tags.openTag(
                                    ChemicalVariant.TYPE,
                                    net.minecraft.resources.ResourceLocation.parse("omniresonance:missing_tag"),
                                    h.getLevel().registryAccess())
                            == null,
                    "Missing chemical tag was treated as present");
            for (var tag : variant.tags()) {
                var members =
                        tags.openTag(ChemicalVariant.TYPE, tag, h.getLevel().registryAccess());
                boolean found = false;
                if (members != null)
                    while (members.hasNext()) if (members.next().equals(variant.resourceId())) found = true;
                h.assertTrue(found, "Chemical tag membership did not match native registry");
            }

            h.succeed();
        }

        private static final class Tank implements mekanism.api.chemical.IChemicalHandler {
            final mekanism.api.chemical.Chemical chemical;
            long amount;

            Tank(mekanism.api.chemical.Chemical chemical, long amount) {
                this.chemical = chemical;
                this.amount = amount;
            }

            public int getChemicalTanks() {
                return 1;
            }

            public mekanism.api.chemical.ChemicalStack getChemicalInTank(int tank) {
                return amount == 0
                        ? mekanism.api.chemical.ChemicalStack.EMPTY
                        : new mekanism.api.chemical.ChemicalStack(
                                mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(chemical), amount);
            }

            public void setChemicalInTank(int tank, mekanism.api.chemical.ChemicalStack stack) {
                throw new UnsupportedOperationException("Direct writes are forbidden");
            }

            public long getChemicalTankCapacity(int tank) {
                return Long.MAX_VALUE;
            }

            public boolean isValid(int tank, mekanism.api.chemical.ChemicalStack stack) {
                return stack.is(chemical);
            }

            public mekanism.api.chemical.ChemicalStack insertChemical(
                    int tank, mekanism.api.chemical.ChemicalStack stack, mekanism.api.Action action) {
                if (!stack.is(chemical)) return stack;
                long accepted = Math.min(stack.getAmount(), Long.MAX_VALUE - amount);
                if (action.execute()) amount += accepted;
                return stack.copyWithAmount(stack.getAmount() - accepted);
            }

            public mekanism.api.chemical.ChemicalStack extractChemical(
                    int tank, long maximum, mekanism.api.Action action) {
                long moved = Math.min(maximum, amount);
                if (action.execute()) amount -= moved;
                return new mekanism.api.chemical.ChemicalStack(
                        mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(chemical), moved);
            }
        }
    }
}
