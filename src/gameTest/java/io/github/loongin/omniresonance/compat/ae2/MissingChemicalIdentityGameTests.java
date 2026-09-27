// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("omniresonance")
@PrefixGameTestTemplate(false)
public final class MissingChemicalIdentityGameTests {
    private MissingChemicalIdentityGameTests() {}

    @GameTest(template = "bootstrap")
    public static void missingChemicalsKeepExactAndFuzzyIdentityAcrossReconstruction(GameTestHelper h) {
        if (net.neoforged.fml.ModList.get().isLoaded("ae2")) Present.verify(h);
        h.succeed();
    }

    private static final class Present {
        static void verify(GameTestHelper h) {
            var a = new ResonanceKeys.Chemical(net.minecraft.resources.ResourceLocation.parse("missing:first"));
            var b = new ResonanceKeys.Chemical(net.minecraft.resources.ResourceLocation.parse("missing:second"));
            var counter = new appeng.api.stacks.KeyCounter();
            counter.add(a, 1);
            counter.add(b, 2);
            h.assertTrue(counter.size() == 2, "Missing identities merged");
            for (var mode : appeng.api.config.FuzzyMode.values())
                h.assertTrue(
                        counter.findFuzzy(a, mode).size() == 1,
                        "Missing chemical fuzzy query matched another ID in " + mode);
            var restored = appeng.api.stacks.AEKey.fromTagGeneric(
                    h.getLevel().registryAccess(), a.toTagGeneric(h.getLevel().registryAccess()));
            var recreated = new ResonanceKeys.Chemical(net.minecraft.resources.ResourceLocation.parse("missing:first"));
            h.assertTrue(
                    restored != a
                            && restored.equals(a)
                            && recreated.getPrimaryKey() == a.getPrimaryKey()
                            && restored.getPrimaryKey() == a.getPrimaryKey(),
                    "Reconstructed missing key lost stable primary identity");
            h.assertTrue(
                    counter.get(restored) == 1 && counter.get(recreated) == 1,
                    "Reconstructed key missed exact counter entry");
            counter.add(restored, 3);
            h.assertTrue(
                    counter.size() == 2 && counter.get(a) == 4 && counter.get(b) == 2,
                    "Reconstructed identity created duplicate counts");
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
            try {
                appeng.api.stacks.AEKey.writeKey(buffer, a);
                var fromPacket = appeng.api.stacks.AEKey.readKey(buffer);
                h.assertTrue(
                        fromPacket.getPrimaryKey() == a.getPrimaryKey() && counter.get(fromPacket) == 4,
                        "Wire reconstruction lost missing primary identity");
            } finally {
                buffer.release();
            }
            var known = new ResonanceKeys.Chemical(net.minecraft.resources.ResourceLocation.parse("mekanism:hydrogen"));
            var knownReloaded = appeng.api.stacks.AEKey.fromTagGeneric(
                    h.getLevel().registryAccess(),
                    known.toTagGeneric(h.getLevel().registryAccess()));
            h.assertTrue(
                    known.getPrimaryKey() == knownReloaded.getPrimaryKey(),
                    "Adapter presence changed same-session key identity");
            if (net.neoforged.fml.ModList.get().isLoaded("mekanism"))
                h.assertTrue(
                        known.getPrimaryKey()
                                == io.github.loongin.omniresonance.compat.mekanism.MekanismResources.primaryIdentity(
                                        known.getId()),
                        "Available chemical stopped using native registry identity");
        }
    }
}
