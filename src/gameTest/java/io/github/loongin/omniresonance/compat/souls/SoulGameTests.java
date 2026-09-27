// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.souls;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
public final class SoulGameTests {
    private SoulGameTests() {}

    @GameTest(template = "bootstrap")
    public static void soulAdapterIsRegisteredOnlyWithSouls(GameTestHelper h) {
        boolean installed = net.neoforged.fml.ModList.get().isLoaded("industrialforegoingsouls");
        h.assertTrue(
                io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create()
                                .find(net.minecraft.resources.ResourceLocation.parse("industrialforegoingsouls:soul"))
                                .isPresent()
                        == installed,
                "Soul adapter registration does not match installed mod");
        h.succeed();
    }

    @GameTest(template = "bootstrap", timeoutTicks = 200)
    public static void realSoulPipesTransferBothWaysWithoutExposingNativeSelfLoopCapabilities(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("industrialforegoingsouls")) {
            h.succeed();
            return;
        }
        Present.run(h);
    }

    private static final class Present {
        static io.github.loongin.omniresonance.transfer.TransferWorkBudget budget() {
            return new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                    100, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
        }

        static io.github.loongin.omniresonance.storage.DomainLedger ledger(long id) {
            var network = new java.util.UUID(219, id);
            return new io.github.loongin.omniresonance.storage.DomainLedger(
                    network,
                    java.util.Map.of(),
                    i -> io.github.loongin.omniresonance.persistence.StorageBucketData.create(network, i));
        }

        static io.github.loongin.omniresonance.transfer.ResourceTransferEngine.Handle handle(SoulResourcePort port) {
            return new io.github.loongin.omniresonance.transfer.ResourceTransferEngine.Handle() {
                public io.github.loongin.omniresonance.transfer.ResourcePort port() {
                    return port;
                }

                public Object physicalIdentity() {
                    return port;
                }

                public boolean valid() {
                    return true;
                }
            };
        }

        static void run(GameTestHelper h) {
            var aPos = new net.minecraft.core.BlockPos(0, 1, 0);
            var bPos = new net.minecraft.core.BlockPos(2, 1, 0);
            var pipe = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.parse("industrialforegoingsouls:soul_network_pipe"));
            h.setBlock(aPos, pipe);
            h.setBlock(bPos, pipe);
            h.runAfterDelay(5, () -> {
                var level = h.getLevel();
                var laserPos = new net.minecraft.core.BlockPos(0, 0, 2);
                h.setBlock(
                        laserPos,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                net.minecraft.resources.ResourceLocation.parse(
                                        "industrialforegoingsouls:soul_laser_base")));
                var laser = level.getCapability(
                        SoulResources.BLOCK, h.absolutePos(laserPos), net.minecraft.core.Direction.UP);
                h.assertTrue(
                        laser != null
                                && laser.fill(
                                                1,
                                                com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler.Action
                                                        .SIMULATE)
                                        == 0
                                && level.getCapability(
                                                SoulResources.BLOCK,
                                                h.absolutePos(laserPos),
                                                net.minecraft.core.Direction.NORTH)
                                        == null,
                        "Laser base bridge changed native side or insertion rules");
                var a = level.getCapability(SoulResources.BLOCK, h.absolutePos(aPos), net.minecraft.core.Direction.UP);
                var b = level.getCapability(SoulResources.BLOCK, h.absolutePos(bPos), net.minecraft.core.Direction.UP);
                h.assertTrue(a != null && b != null, "Pipe endpoints are missing");
                h.assertTrue(
                        level.getCapability(
                                        com.buuz135.industrialforegoingsouls.capabilities.SoulCapabilities.BLOCK,
                                        h.absolutePos(aPos),
                                        net.minecraft.core.Direction.UP)
                                == null,
                        "Bridge exposed native self-loop capability");
                var source = new SoulResourcePort(a);
                var target = new SoulResourcePort(b);
                source.sourceViews(budget());
                target.sourceViews(budget());
                var soul = SoulVariant.INSTANCE;
                verifyNodeFace(h, aPos);
                var manager = com.hrznstudio.titanium.block_network.NetworkManager.get(level);
                manager.setDirty(false);
                h.assertTrue(
                        source.insert(0, soul, Long.MAX_VALUE, true, budget()) == 4 && a.getSoulInTank(0) == 0,
                        "Pipe simulation did not respect native capacity or changed inventory");
                h.assertTrue(!manager.isDirty(), "Simulation dirtied native network data");
                h.assertTrue(source.insert(0, soul, 4, false, budget()) == 4, "Pipe insertion failed");
                h.assertTrue(manager.isDirty(), "Actual insertion bypassed native persistence dirtying");
                h.assertTrue(
                        source.extract(0, soul, Long.MAX_VALUE, true, budget()) == 4 && a.getSoulInTank(0) == 4,
                        "Pipe extraction simulation changed inventory");
                var recovery = new io.github.loongin.omniresonance.recovery.RecoveryBuffer(() -> {});
                var limits = io.github.loongin.omniresonance.config.ServerSettings.RecoveryLimits.defaults();
                var direct = new io.github.loongin.omniresonance.transfer.ResourceTransferEngine()
                        .commitGreedy(handle(source), 0, handle(target), 0, soul, 2, recovery, limits, budget());
                h.assertTrue(
                        direct.moved() == 2 && a.getSoulInTank(0) == 2 && b.getSoulInTank(0) == 2,
                        "Direct soul transfer did not conserve stock");
                var ledger = ledger(1);
                var receiver = ledger(2);
                var engine = new io.github.loongin.omniresonance.transfer.DomainTransferEngine();
                var calls = budget();
                var deposit = engine.depositExact(
                        handle(source), 0, ledger, soul, 2, 1, 1, -1, () -> true, recovery, limits, calls);
                h.assertTrue(
                        deposit.moved() == 1 && a.getSoulInTank(0) == 1 && ledger.amount(soul.key()) == 1,
                        "Soul exact transfer ignored keep amount");
                h.assertTrue(calls.calls() < 20, "Soul calls scaled with quantity");
                h.assertTrue(
                        engine.withdrawGreedy(
                                                        ledger,
                                                        handle(target),
                                                        0,
                                                        soul,
                                                        1,
                                                        () -> true,
                                                        recovery,
                                                        limits,
                                                        budget())
                                                .moved()
                                        == 1
                                && b.getSoulInTank(0) == 3
                                && ledger.amount(soul.key()) == 0,
                        "Domain soul output failed");
                h.assertTrue(
                        engine.depositGreedy(
                                                handle(source),
                                                0,
                                                ledger,
                                                soul,
                                                1,
                                                -1,
                                                () -> true,
                                                recovery,
                                                limits,
                                                budget())
                                        .moved()
                                == 1,
                        "Domain soul input failed");
                h.assertTrue(
                        ledger.transferTo(receiver, soul.key(), 1, -1) == 1 && receiver.amount(soul.key()) == 1,
                        "Soul domain exchange failed");
                var adapters = io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create();
                h.assertTrue(
                        adapters.decode(soul.key(), level.registryAccess()).orElseThrow() == soul,
                        "Soul key did not round trip");
                h.assertTrue(
                        io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults()
                                .decode(soul.key(), level.registryAccess())
                                .isEmpty(),
                        "Missing Soul adapter lost opacity");
                var id = new java.util.UUID(219, 3);
                int index = io.github.loongin.omniresonance.storage.StorageBucketHash.bucket(soul.key());
                var bucket = io.github.loongin.omniresonance.persistence.StorageBucketData.create(id, index);
                bucket.setAmount(soul.key(), Long.MAX_VALUE);
                h.assertTrue(
                        io.github.loongin.omniresonance.persistence.StorageBucketData.load(
                                                id,
                                                index,
                                                bucket.save(
                                                        new net.minecraft.nbt.CompoundTag(), level.registryAccess()))
                                        .amount(soul.key())
                                == Long.MAX_VALUE,
                        "Soul persistence narrowed quantity");
                h.setBlock(new net.minecraft.core.BlockPos(1, 1, 0), pipe);
                h.runAfterDelay(10, () -> {
                    // Native Souls/Titanium merges can discard stock; compare with the native surviving network,
                    // not an invented repaired balance. The independent native-only reproduction is retained in
                    // diagnostics.
                    var current = (com.buuz135.industrialforegoingsouls.block_network.SoulNetwork)
                            manager.getElement(h.absolutePos(aPos)).getNetwork();
                    int nativeBefore = current.getSoulAmount();
                    h.assertTrue(
                            a.getSoulInTank(0) == nativeBefore && b.getSoulInTank(0) == nativeBefore,
                            "Existing endpoints retained obsolete networks after merge");
                    h.assertTrue(
                            engine.withdrawGreedy(
                                                            receiver,
                                                            handle(source),
                                                            0,
                                                            soul,
                                                            1,
                                                            () -> true,
                                                            recovery,
                                                            limits,
                                                            budget())
                                                    .moved()
                                            == 1
                                    && a.getSoulInTank(0) == nativeBefore + 1
                                    && b.getSoulInTank(0) == nativeBefore + 1,
                            "Merged network could not receive domain output");
                    h.runAfterDelay(10, () -> {
                        h.assertTrue(
                                a.getSoulInTank(0) == nativeBefore + 1 && b.getSoulInTank(0) == nativeBefore + 1,
                                "Native pipe ticks changed bridged stock unexpectedly");
                        var surge = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                net.minecraft.resources.ResourceLocation.parse("industrialforegoingsouls:soul_surge"));
                        h.setBlock(
                                new net.minecraft.core.BlockPos(2, 1, 1),
                                surge.defaultBlockState()
                                        .setValue(
                                                com.buuz135.industrialforegoingsouls.block.SoulSurgeBlock.ENABLED,
                                                true));
                        h.runAfterDelay(5, () -> {
                            h.assertTrue(
                                    b.getSoulInTank(0) < nativeBefore + 1 && b.getSoulInTank(0) >= 0,
                                    "Native Soul Surge did not consume exported souls");
                            h.setBlock(aPos, net.minecraft.world.level.block.Blocks.AIR);
                            h.assertTrue(
                                    a.fill(
                                                            1,
                                                            com.buuz135.industrialforegoingsouls.capabilities
                                                                    .ISoulHandler.Action.EXECUTE)
                                                    == 0
                                            && a.drain(
                                                            1,
                                                            com.buuz135.industrialforegoingsouls.capabilities
                                                                    .ISoulHandler.Action.EXECUTE)
                                                    == 0,
                                    "Removed pipe endpoint remained writable");
                            h.succeed();
                        });
                    });
                });
            });
        }

        static void verifyNodeFace(GameTestHelper h, net.minecraft.core.BlockPos pipe) {
            var face = net.minecraft.core.Direction.NORTH;
            var pos = h.absolutePos(pipe).south();
            h.getLevel()
                    .setBlock(
                            pos,
                            io.github.loongin.omniresonance.registry.ModBlocks.RESONANCE_TRANSFER_NODE
                                    .get()
                                    .defaultBlockState()
                                    .setValue(
                                            io.github.loongin.omniresonance.node.AbstractResonanceNodeBlock.FACING,
                                            face),
                            18);
            var entity = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                    h.getLevel().getBlockEntity(pos);
            var id = entity.state().orElseThrow().nodeId();
            var tag = new net.minecraft.nbt.CompoundTag();
            io.github.loongin.omniresonance.node.NodePersistentState.linked(id).writeOwnedFields(tag);
            entity.loadCustomOnly(tag, h.getLevel().registryAccess());
            var node = io.github.loongin.omniresonance.node.NetworkNodeRecord.fresh(
                    id,
                    1,
                    new io.github.loongin.omniresonance.network.ManagedName("Soul node"),
                    net.minecraft.core.GlobalPos.of(h.getLevel().dimension(), pos),
                    io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                    face);
            try (var cache = new io.github.loongin.omniresonance.transfer.ResourceEndpointCache(
                    h.getLevel().getServer(), io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create(), 8)) {
                var endpoint = cache.resolve(node, face, SoulVariant.TYPE, budget());
                h.assertTrue(
                        endpoint != null
                                && endpoint.valid()
                                && endpoint.port().typeId().equals(SoulVariant.TYPE),
                        "Selected node face did not discover Soul network endpoint");
                h.getLevel().invalidateCapabilities(h.absolutePos(pipe));
                h.assertTrue(!endpoint.valid(), "Soul endpoint ignored capability invalidation");
            }
        }
    }
}
