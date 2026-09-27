// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ars;

import io.github.loongin.omniresonance.bootstrap.ResourceAdapters;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
public final class SourceGameTests {
    private SourceGameTests() {}

    @GameTest(template = "bootstrap")
    public static void sourceRegistrationTracksInstalledMod(GameTestHelper h) {
        boolean installed = net.neoforged.fml.ModList.get().isLoaded("ars_nouveau");
        h.assertTrue(
                ResourceAdapters.create()
                                .find(ResourceLocation.parse("ars_nouveau:source"))
                                .isPresent()
                        == installed,
                "Source adapter registration does not match installed Ars Nouveau");
        h.succeed();
    }

    @GameTest(template = "bootstrap", timeoutTicks = 160)
    public static void realJarsConserveSourceThroughDirectDomainAndExchange(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("ars_nouveau")) {
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
            var network = new java.util.UUID(113, id);
            return new io.github.loongin.omniresonance.storage.DomainLedger(
                    network,
                    java.util.Map.of(),
                    i -> io.github.loongin.omniresonance.persistence.StorageBucketData.create(network, i));
        }

        static io.github.loongin.omniresonance.transfer.ResourceTransferEngine.Handle handle(SourceResourcePort port) {
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
            var from = new net.minecraft.core.BlockPos(1, 2, 1);
            var to = new net.minecraft.core.BlockPos(3, 2, 1);
            var jar = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    ResourceLocation.parse("ars_nouveau:source_jar"));
            h.setBlock(from, jar);
            h.setBlock(to, jar);
            h.runAfterDelay(3, () -> {
                var source = h.getLevel()
                        .getCapability(ArsResources.BLOCK, h.absolutePos(from), net.minecraft.core.Direction.UP);
                var target = h.getLevel()
                        .getCapability(ArsResources.BLOCK, h.absolutePos(to), net.minecraft.core.Direction.UP);
                h.assertTrue(
                        source != null && target != null, "Source jars did not expose the public sided capability");
                var variant = SourceVariant.INSTANCE;
                var a = new SourceResourcePort(source);
                var b = new SourceResourcePort(target);
                h.assertTrue(a.insert(0, variant, 5000, false, budget()) == 5000, "Jar refused initial Source");
                h.assertTrue(
                        a.extract(0, variant, Long.MAX_VALUE, true, budget()) == 5000 && source.getSource() == 5000,
                        "Source extraction simulation mutated or narrowed a long request");
                h.assertTrue(
                        b.insert(0, variant, Long.MAX_VALUE, true, budget()) == target.getSourceCapacity()
                                && target.getSource() == 0,
                        "Source insertion simulation mutated or ignored capacity");
                var recovery = new io.github.loongin.omniresonance.recovery.RecoveryBuffer(() -> {});
                var limits = io.github.loongin.omniresonance.config.ServerSettings.RecoveryLimits.defaults();
                var direct = new io.github.loongin.omniresonance.transfer.ResourceTransferEngine()
                        .commitGreedy(handle(a), 0, handle(b), 0, variant, 1000, recovery, limits, budget());
                h.assertTrue(
                        direct.moved() == 1000 && source.getSource() == 4000 && target.getSource() == 1000,
                        "Direct Source transfer did not conserve quantities");
                var ledger = ledger(1);
                var engine = new io.github.loongin.omniresonance.transfer.DomainTransferEngine();
                var calls = budget();
                var deposit = engine.depositExact(
                        handle(a), 0, ledger, variant, 1500, 3000, 500, -1, () -> true, recovery, limits, calls);
                h.assertTrue(
                        deposit.moved() == 1000 && source.getSource() == 3000 && ledger.amount(variant.key()) == 1000,
                        "Exact Source batches did not respect the keep amount");
                h.assertTrue(calls.calls() < 20, "Source transfer calls grew with quantity");
                var withdrawal = engine.withdrawGreedy(
                        ledger, handle(b), 0, variant, 700, () -> true, recovery, limits, budget());
                h.assertTrue(
                        withdrawal.moved() == 700 && ledger.amount(variant.key()) == 300 && target.getSource() == 1700,
                        "Domain Source output lost quantities");
                var receiver = ledger(2);
                h.assertTrue(
                        ledger.transferTo(receiver, variant.key(), 300, -1) == 300
                                && ledger.amount(variant.key()) == 0
                                && receiver.amount(variant.key()) == 300,
                        "Domain exchange lost Source identity or quantity");
                var adapters = ResourceAdapters.create();
                h.assertTrue(
                        adapters.decode(variant.key(), h.getLevel().registryAccess())
                                        .orElseThrow()
                                == variant,
                        "Source identity did not round trip");
                h.assertTrue(
                        adapters.decode(
                                        new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                                                SourceVariant.TYPE, new byte[] {1}),
                                        h.getLevel().registryAccess())
                                .isEmpty(),
                        "Malformed Source key was accepted");
                h.assertTrue(
                        io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory.nativeDefaults()
                                .decode(variant.key(), h.getLevel().registryAccess())
                                .isEmpty(),
                        "Absent optional adapter discarded opacity");
                h.assertTrue(
                        !adapters.carrierTypes().contains(SourceVariant.TYPE),
                        "Guessed a nonexistent Source item capability");
                var network = new java.util.UUID(113, 3);
                int bucketIndex = io.github.loongin.omniresonance.storage.StorageBucketHash.bucket(variant.key());
                var bucket = io.github.loongin.omniresonance.persistence.StorageBucketData.create(network, bucketIndex);
                bucket.setAmount(variant.key(), Long.MAX_VALUE);
                var reloaded = io.github.loongin.omniresonance.persistence.StorageBucketData.load(
                        network,
                        bucketIndex,
                        bucket.save(
                                new net.minecraft.nbt.CompoundTag(),
                                h.getLevel().registryAccess()));
                h.assertTrue(reloaded.amount(variant.key()) == Long.MAX_VALUE, "Source persistence lost long quantity");
                var tiny = new io.github.loongin.omniresonance.transfer.TransferWorkBudget(
                        1, Long.MAX_VALUE, Long.MAX_VALUE, () -> 0);
                var waiting = engine.depositExact(
                        handle(a), 0, ledger, variant, 500, 0, 500, -1, () -> true, recovery, limits, tiny);
                h.assertTrue(
                        waiting.moved() == 0 && source.getSource() == 3000 && ledger.amount(variant.key()) == 0,
                        "Source exact batch modified inventory without sufficient budget");
                verifyEndpoint(h, from);
                h.succeed();
            });
        }

        static void verifyEndpoint(GameTestHelper h, net.minecraft.core.BlockPos jar) {
            var side = net.minecraft.core.Direction.NORTH;
            var pos = h.absolutePos(jar).relative(side);
            var face = side.getOpposite();
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
                    new io.github.loongin.omniresonance.network.ManagedName("Source"),
                    net.minecraft.core.GlobalPos.of(h.getLevel().dimension(), pos),
                    io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                    face);
            try (var cache = new io.github.loongin.omniresonance.transfer.ResourceEndpointCache(
                    h.getLevel().getServer(), ResourceAdapters.create(), 8)) {
                var endpoint = cache.resolve(node, face, SourceVariant.TYPE, budget());
                h.assertTrue(endpoint != null && endpoint.valid(), "Node work face did not discover Source jar");
                h.assertTrue(
                        endpoint.port().peek(0, budget()).orElseThrow().quantity() == 3000,
                        "Node observed wrong Source balance");
                h.getLevel().invalidateCapabilities(h.absolutePos(jar));
                h.assertTrue(!endpoint.valid(), "Source capability cache ignored invalidation");
            }
        }
    }
}
