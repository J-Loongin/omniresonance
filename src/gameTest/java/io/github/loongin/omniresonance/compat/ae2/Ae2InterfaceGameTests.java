// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runs absence assertions in core CI and actual grid/storage assertions when optional AE2 is installed. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Ae2InterfaceGameTests {
    private Ae2InterfaceGameTests() {}

    @GameTest(template = "bootstrap", timeoutTicks = 240)
    public static void interfaceIsOptionalAndNativeGridRejectsDuplicateDomains(GameTestHelper helper) {
        var id = net.minecraft.resources.ResourceLocation.parse("omniresonance:ae_domain_interface");
        if (!net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            helper.assertTrue(
                    !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id),
                    "AE interface block registered without AE2");
            helper.assertTrue(
                    !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id),
                    "AE interface item registered without AE2");
            helper.succeed();
            return;
        }
        Present.run(helper);
    }

    @GameTest(template = "bootstrap", timeoutTicks = 400)
    public static void nativeGridMergeSplitRebindAndReloadPreserveInventory(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            helper.succeed();
            return;
        }
        Lifecycle.run(helper);
    }

    private static final class Lifecycle {
        static void run(GameTestHelper h) {
            var runtime = Ae2InterfaceRuntime.find(h.getLevel().getServer());
            var owner = java.util.UUID.randomUUID();
            var domain = network(runtime, owner, "Original");
            var other = network(runtime, owner, "Other");
            var actor = new net.neoforged.neoforge.common.util.FakePlayer(
                    h.getLevel(), new com.mojang.authlib.GameProfile(owner, "AE_Lifecycle"));
            var a = new net.minecraft.core.BlockPos(1, 2, 1);
            var b = new net.minecraft.core.BlockPos(3, 2, 1);
            var bridge = new net.minecraft.core.BlockPos(2, 2, 2);
            var energy = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.parse("ae2:creative_energy_cell"));
            actor.setPos(net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(a)));
            h.setBlock(a, Ae2InterfaceContent.BLOCK.get());
            h.setBlock(b, Ae2InterfaceContent.BLOCK.get());
            h.setBlock(new net.minecraft.core.BlockPos(1, 2, 2), energy);
            h.setBlock(new net.minecraft.core.BlockPos(3, 2, 2), energy);
            var old = new Ae2DomainStorage[2];
            h.runAfterDelay(5, () -> {
                runtime.bind(actor, entity(h, a), domain, "Interface");
                runtime.bind(actor, entity(h, b), domain, "Interface");
            });
            h.runAfterDelay(45, () -> {
                var first = runtime.host(entity(h, a));
                var second = runtime.host(entity(h, b));
                h.assertTrue(
                        first.node.getGrid() != second.node.getGrid(), "Separate physical grids merged unexpectedly");
                h.assertTrue(
                        first.status().equals("ready") && second.status().equals("ready"),
                        "Separate grids denied shared domain");
                old[0] = first.storage;
                old[1] = second.storage;
                h.assertTrue(
                        insert(old[0], 64) == 64 && extract(old[1], 16) == 16,
                        "Separate grids did not share authoritative inventory");
                h.setBlock(bridge, energy);
            });
            h.runAfterDelay(85, () -> {
                var first = runtime.host(entity(h, a));
                var second = runtime.host(entity(h, b));
                h.assertTrue(
                        first.node.getGrid() == second.node.getGrid(), "Physical bridge did not merge native grids");
                h.assertTrue(
                        first.status().equals("conflict") && second.status().equals("conflict"),
                        "Grid merge did not block duplicates");
                h.assertTrue(
                        extract(old[0], 1) == 0 && insert(old[1], 1) == 0, "Merged grid retained writable old facade");
                h.setBlock(bridge, net.minecraft.world.level.block.Blocks.AIR);
            });
            h.runAfterDelay(125, () -> {
                var first = runtime.host(entity(h, a));
                var second = runtime.host(entity(h, b));
                h.assertTrue(
                        first.node.getGrid() != second.node.getGrid(), "Removing bridge did not split native grids");
                h.assertTrue(
                        first.status().equals("ready") && second.status().equals("ready"),
                        "Split grid failed to restore mounts");
                h.assertTrue(
                        extract(first.storage, 48) == 48 && extract(second.storage, 1) == 0,
                        "Merge/split duplicated or lost inventory");
                old[0] = first.storage;
                runtime.bind(actor, entity(h, a), other, "Interface");
                h.assertTrue(insert(old[0], 1) == 0, "Rebinding left previous facade writable");
            });
            h.runAfterDelay(165, () -> {
                var first = runtime.host(entity(h, a));
                h.assertTrue(
                        first.status().equals("ready") && insert(first.storage, 9) == 9, "New binding failed to mount");
                h.assertTrue(
                        extract(runtime.host(entity(h, b)).storage, 1) == 0,
                        "Rebinding redirected original domain contents");
                old[1] = first.storage;
                runtime.bind(actor, entity(h, a), domain, "Interface");
            });
            h.runAfterDelay(205, () -> {
                var first = runtime.host(entity(h, a));
                h.assertTrue(first.status().equals("ready"), "Returning binding failed to mount");
                h.assertTrue(
                        insert(old[0], 1) == 0 && extract(old[1], 1) == 0,
                        "Old binding generation revived after round trip");
                h.assertTrue(insert(first.storage, 23) == 23, "Pre-reload insertion failed");
                old[0] = first.storage;
                var saved = entity(h, a).saveWithFullMetadata(h.getLevel().registryAccess());
                var chunk = h.getLevel().getChunkAt(h.absolutePos(a));
                runtime.unload(h.getLevel().dimension(), chunk.getPos());
                h.assertTrue(extract(old[0], 1) == 0, "Unloaded host retained inventory access");
                var originalEntity = entity(h, a);
                h.getLevel().removeBlockEntity(h.absolutePos(a));
                var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                        h.absolutePos(a),
                        h.getBlockState(a),
                        saved,
                        h.getLevel().registryAccess());
                h.assertTrue(restored != null && restored != originalEntity, "Reload reused previous block entity");
                h.getLevel().setBlockEntity(restored);
                restored.onLoad();
                entity(h, b).onLoad();
            });
            h.runAfterDelay(255, () -> {
                var first = runtime.host(entity(h, a));
                h.assertTrue(first.status().equals("ready"), "Saved interface did not recover after host reload");
                h.assertTrue(entity(h, a).interfaceOwner().equals(owner), "Reload lost owner authorization");
                h.assertTrue(extract(old[0], 1) == 0, "Reload resurrected destroyed facade");
                h.assertTrue(
                        extract(first.storage, 23) == 23 && extract(first.storage, 1) == 0,
                        "Reload duplicated or lost inventory");
                h.succeed();
            });
        }

        static java.util.UUID network(Ae2InterfaceRuntime runtime, java.util.UUID owner, String name) {
            var id = java.util.UUID.randomUUID();
            var metadata = new io.github.loongin.omniresonance.network.NetworkMetadata(
                    id, owner, new io.github.loongin.omniresonance.network.ManagedName(name), 0, java.util.Set.of());
            runtime.repository.createNetwork(metadata);
            runtime.networks.add(metadata);
            return id;
        }

        static io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity entity(
                GameTestHelper h, net.minecraft.core.BlockPos pos) {
            return (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                    h.getLevel().getBlockEntity(h.absolutePos(pos));
        }

        static long insert(Ae2DomainStorage storage, long count) {
            return storage.insert(
                    appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT),
                    count,
                    appeng.api.config.Actionable.MODULATE,
                    appeng.api.networking.security.IActionSource.empty());
        }

        static long extract(Ae2DomainStorage storage, long count) {
            return storage.extract(
                    appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT),
                    count,
                    appeng.api.config.Actionable.MODULATE,
                    appeng.api.networking.security.IActionSource.empty());
        }
    }

    private static final class Present {
        static void run(GameTestHelper h) {
            var a = new net.minecraft.core.BlockPos(1, 2, 1);
            var b = new net.minecraft.core.BlockPos(2, 2, 1);
            var runtime = Ae2InterfaceRuntime.find(h.getLevel().getServer());
            h.assertTrue(runtime != null, "Optional runtime was not installed");
            var owner = java.util.UUID.randomUUID();
            var network = java.util.UUID.randomUUID();
            var metadata = new io.github.loongin.omniresonance.network.NetworkMetadata(
                    network,
                    owner,
                    new io.github.loongin.omniresonance.network.ManagedName("AE Test"),
                    0,
                    java.util.Set.of());
            runtime.repository.createNetwork(metadata);
            runtime.networks.add(metadata);
            var actor = new net.neoforged.neoforge.common.util.FakePlayer(
                    h.getLevel(), new com.mojang.authlib.GameProfile(owner, "AE_Test"));
            var absolute = h.absolutePos(a);
            actor.setPos(absolute.getX() + 0.5, absolute.getY() + 1, absolute.getZ() + 0.5);
            h.setBlock(a, Ae2InterfaceContent.BLOCK.get());
            h.setBlock(
                    new net.minecraft.core.BlockPos(1, 2, 2),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            net.minecraft.resources.ResourceLocation.parse("ae2:creative_energy_cell")));
            h.runAfterDelay(5, () -> {
                var first = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        h.getLevel().getBlockEntity(h.absolutePos(a));
                runtime.bind(actor, first, network, "Interface");
            });
            final Ae2DomainStorage[] original = new Ae2DomainStorage[1];
            h.runAfterDelay(55, () -> {
                var first = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        h.getLevel().getBlockEntity(h.absolutePos(a));
                var host = runtime.host(first);
                h.assertTrue(
                        host != null && host.node.isActive(),
                        "AE interface did not become powered/channel-active: "
                                + (host == null
                                        ? "missing host"
                                        : host.status() + " powered=" + host.node.isPowered() + " grid="
                                                + host.node.getGrid() + " connections="
                                                + host.node
                                                        .getNode()
                                                        .getConnections()
                                                        .size() + " channels="
                                                + host.node.getNode().getUsedChannels() + " booting="
                                                + host.node
                                                        .getGrid()
                                                        .getPathingService()
                                                        .isNetworkBooting()));
                h.assertTrue(
                        host.node.getNode().getUsedChannels() == 1, "Interface did not consume exactly one channel");
                var storage = host.node.getGrid().getStorageService().getInventory();
                var key = appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT);
                long amount = (long) Integer.MAX_VALUE + 7;
                h.assertTrue(
                        storage.insert(
                                        key,
                                        amount,
                                        appeng.api.config.Actionable.SIMULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == amount,
                        "Native simulation refused long amount");
                h.assertTrue(
                        storage.insert(
                                        key,
                                        amount,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == amount,
                        "Native insert truncated long amount");
                h.assertTrue(
                        storage.extract(
                                        key,
                                        amount,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == amount,
                        "Native extract truncated long amount");
                var fluid = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
                h.assertTrue(
                        storage.insert(
                                        fluid,
                                        amount,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == amount,
                        "Native fluid insert truncated long amount");
                h.assertTrue(
                        storage.extract(
                                        fluid,
                                        amount,
                                        appeng.api.config.Actionable.SIMULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == amount,
                        "Native fluid simulation changed units");
                h.assertTrue(
                        storage.extract(
                                        fluid,
                                        amount,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == amount,
                        "Native fluid extraction changed units");
                var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
                stack.set(
                        net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        net.minecraft.network.chat.Component.literal("Distinct iron"));
                var named = appeng.api.stacks.AEItemKey.of(stack);
                h.assertTrue(
                        storage.insert(
                                        named,
                                        7,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == 7,
                        "Native component identity insert failed");
                h.assertTrue(
                        storage.extract(
                                        key,
                                        7,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == 0,
                        "Native plain key consumed component variant");
                h.assertTrue(
                        storage.extract(
                                        named,
                                        7,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == 7,
                        "Native component variant was lost");
                var intruder = new net.neoforged.neoforge.common.util.FakePlayer(
                        h.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "AE_Intruder"));
                intruder.setPos(actor.position());
                boolean denied = false;
                try {
                    runtime.bind(intruder, first, network, "Interface");
                } catch (SecurityException expected) {
                    denied = true;
                }
                h.assertTrue(denied, "Non-owner changed the interface binding");
                original[0] = host.storage;
                h.assertTrue(original[0] != null, "Mounted facade was missing");
                h.setBlock(b, Ae2InterfaceContent.BLOCK.get());
            });
            h.runAfterDelay(60, () -> {
                var second = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        h.getLevel().getBlockEntity(h.absolutePos(b));
                runtime.bind(actor, second, network, "Interface");
            });
            h.runAfterDelay(110, () -> {
                var first = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        h.getLevel().getBlockEntity(h.absolutePos(a));
                var second = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        h.getLevel().getBlockEntity(h.absolutePos(b));
                h.assertTrue(
                        runtime.host(first).status().equals("conflict")
                                && runtime.host(second).status().equals("conflict"),
                        "Duplicate domain did not stop both interfaces");
                h.assertTrue(
                        h.getBlockState(a).getValue(Ae2InterfaceBlock.CONFLICT)
                                && h.getBlockState(b).getValue(Ae2InterfaceBlock.CONFLICT),
                        "Conflict indicator was not red on both interfaces");
                h.assertTrue(
                        original[0].insert(
                                        appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT),
                                        1,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == 0,
                        "Previously mounted facade bypassed duplicate conflict");
                h.setBlock(b, net.minecraft.world.level.block.Blocks.AIR);
            });
            h.runAfterDelay(165, () -> {
                var first = (io.github.loongin.omniresonance.node.ResonanceNodeBlockEntity)
                        h.getLevel().getBlockEntity(h.absolutePos(a));
                h.assertTrue(runtime.host(first).status().equals("ready"), "Surviving interface failed to recover");
                var host = runtime.host(first);
                var live = host.storage;
                h.assertTrue(live != null, "Recovered facade missing");
                var ownerTag = first.interfaceData();
                var invalid = ownerTag.copy();
                invalid.putUUID("domain_owner", java.util.UUID.randomUUID());
                first.interfaceData(invalid);
                h.assertTrue(
                        live.insert(
                                        appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT),
                                        1,
                                        appeng.api.config.Actionable.MODULATE,
                                        appeng.api.networking.security.IActionSource.empty())
                                == 0,
                        "Cached facade ignored revoked owner authorization");
                first.interfaceData(ownerTag);
                h.succeed();
            });
        }
    }
}
