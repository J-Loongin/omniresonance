// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
public final class OwnResourceGameTests {
    private OwnResourceGameTests() {}

    @GameTest(template = "bootstrap")
    public static void independentKeysExistWithoutAeResourceAddons(GameTestHelper h) {
        if (net.neoforged.fml.ModList.get().isLoaded("ae2")) Present.verify(h);
        h.succeed();
    }

    private static final class Present {
        static void verify(GameTestHelper h) {
            for (String name : new String[] {"energy", "source", "chemical", "soul"}) {
                h.assertTrue(
                        appeng.api.stacks.AEKeyTypes.getAll().stream()
                                .anyMatch(t -> t.getId().toString().equals("omniresonance:" + name)),
                        "Missing independent AE resource type " + name);
            }
            var chemical =
                    new ResonanceKeys.Chemical(net.minecraft.resources.ResourceLocation.parse("mekanism:hydrogen"));
            var keys = java.util.List.of(ResonanceKeys.ENERGY, ResonanceKeys.SOURCE, ResonanceKeys.SOUL, chemical);
            for (var key : keys) {
                for (var other : keys)
                    h.assertTrue(key.getType().contains(other) == (key == other), "AE type filters overlap");
                var restored = appeng.api.stacks.AEKey.fromTagGeneric(
                        h.getLevel().registryAccess(),
                        key.toTagGeneric(h.getLevel().registryAccess()));
                h.assertTrue(key.equals(restored), "AE resource NBT identity changed");
                var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
                try {
                    appeng.api.stacks.AEKey.writeKey(buffer, key);
                    h.assertTrue(
                            key.equals(appeng.api.stacks.AEKey.readKey(buffer)) && !buffer.isReadable(),
                            "AE wire identity changed");
                } finally {
                    buffer.release();
                }
            }
            h.assertTrue(
                    ResonanceKeys.ENERGY.getAmountPerUnit() == 1
                            && ResonanceKeys.SOURCE.getAmountPerUnit() == 1
                            && chemical.getAmountPerUnit() == 1000,
                    "AE display units changed resource quantities");
            var id = new java.util.UUID(205, 1);
            var ledger = new io.github.loongin.omniresonance.storage.DomainLedger(
                    id,
                    java.util.Map.of(),
                    i -> io.github.loongin.omniresonance.persistence.StorageBucketData.create(id, i));
            var access = new Ae2DomainAccess(() -> ledger, () -> -1);
            var storage = new Ae2DomainStorage(access, () -> h.getLevel().registryAccess());
            var adapters = io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create();
            var action = appeng.api.networking.security.IActionSource.empty();
            for (var key : keys) {
                if (adapters.decode(key.resourceKey(), h.getLevel().registryAccess())
                        .isEmpty()) {
                    h.assertTrue(
                            storage.insert(key, 1, appeng.api.config.Actionable.MODULATE, action) == 0,
                            "Absent resource adapter accepted stock");
                    continue;
                }
                long revision = ledger.revision();
                h.assertTrue(
                        storage.insert(key, Long.MAX_VALUE, appeng.api.config.Actionable.SIMULATE, action)
                                        == Long.MAX_VALUE
                                && ledger.revision() == revision
                                && ledger.variantCount() == 0,
                        "AE simulation changed domain");
                h.assertTrue(
                        storage.insert(key, Long.MAX_VALUE, appeng.api.config.Actionable.MODULATE, action)
                                == Long.MAX_VALUE,
                        "AE insert narrowed amount");
                var counts = new appeng.api.stacks.KeyCounter();
                storage.getAvailableStacks(counts);
                h.assertTrue(
                        counts.size() == 1 && counts.get(AeResourceKeys.canonical(key.resourceKey())) == Long.MAX_VALUE,
                        "AE enumeration duplicated or narrowed resource");
                h.assertTrue(
                        storage.extract(key, Long.MAX_VALUE, appeng.api.config.Actionable.MODULATE, action)
                                        == Long.MAX_VALUE
                                && ledger.amount(key.resourceKey()) == 0,
                        "AE extraction lost resources");
            }
            var unknown = new ResonanceKeys.Chemical(net.minecraft.resources.ResourceLocation.parse("unknown:missing"));
            h.assertTrue(
                    storage.insert(unknown, 1, appeng.api.config.Actionable.MODULATE, action) == 0,
                    "Missing chemical accepted");
        }
    }
}
