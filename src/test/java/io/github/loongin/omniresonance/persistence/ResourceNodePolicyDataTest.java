// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class ResourceNodePolicyDataTest {
    @Test
    void savesResourcePolicyAsSchemaEight() {
        CompoundTag saved = ItemPolicyDataTest.data().save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(8, saved.getInt("schema_version"));
        CompoundTag binding = saved.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0);
        assertTrue(binding.contains("resource_policy", Tag.TAG_COMPOUND));
        assertFalse(binding.contains("item_policy"));
    }

    @Test
    void genuinelyNewBindingDefaultsToAllResources() {
        CompoundTag saved = ItemPolicyDataTest.data().save(new CompoundTag(), RegistryAccess.EMPTY);
        CompoundTag binding = saved.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(
                "all",
                binding.getCompound("resource_policy")
                        .getCompound("resource_scope")
                        .getString("kind"));
    }

    @Test
    void unknownRawOverridesSurviveRealShardReloadAndAdapterRecovery() {
        var data = ItemPolicyDataTest.data();
        var id = net.minecraft.resources.ResourceLocation.parse("missing:liquid");
        var policy = new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Input(
                7,
                io.github.loongin.omniresonance.transfer.ResourceScope.customSet(java.util.Set.of(id)),
                io.github.loongin.omniresonance.transfer.RedstoneCondition.NO_SIGNAL,
                null,
                io.github.loongin.omniresonance.filter.FilterMode.BLACKLIST,
                java.util.Map.of(),
                99);
        var raw = new io.github.loongin.omniresonance.transfer.StoredResourcePolicy.RawOverride(
                Integer.MAX_VALUE,
                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.BatchMode.GREEDY,
                900L);
        var stored =
                new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(policy, java.util.Map.of(id, raw));
        data.setDirectBinding(
                ItemPolicyDataTest.NODE,
                2,
                ItemPolicyDataTest.CHANNEL,
                stored,
                io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                false,
                -1);
        var saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        var missing = NetworkSavedData.load(ItemPolicyDataTest.NETWORK, saved, java.util.Set.of());
        assertEquals(
                stored,
                missing.findDirectBinding(ItemPolicyDataTest.NODE, ItemPolicyDataTest.CHANNEL)
                        .orElseThrow()
                        .storedPolicy());
        assertEquals(saved, missing.save(new CompoundTag(), RegistryAccess.EMPTY));
        var recovered = NetworkSavedData.load(ItemPolicyDataTest.NETWORK, saved, java.util.Set.of(id));
        var binding = recovered
                .findDirectBinding(ItemPolicyDataTest.NODE, ItemPolicyDataTest.CHANNEL)
                .orElseThrow();
        assertTrue(binding.storedPolicy().missingTypeOverrides().isEmpty());
        assertTrue(binding.policy().scope().includes(id));
        assertEquals(
                99,
                ((io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Input) binding.policy()).keepCount());
        assertTrue(binding.policy().resourcePolicyOverrides().isEmpty());
        assertFalse(recovered.isDirty());
    }

    @Test
    void trueVersionSevenMigratesOnlyItemsAndKeepsExplicitFaces() {
        var saved = ItemPolicyDataTest.data().save(new CompoundTag(), RegistryAccess.EMPTY);
        saved.putInt("schema_version", 7);
        var old = saved.getList("direct_bindings", Tag.TAG_COMPOUND).getCompound(0);
        old.remove("resource_policy");
        old.put("item_policy", ItemPolicyNbt.encode(ItemPolicyDataTest.policy()));
        old.putInt("working_face_mask", 48);
        old.putBoolean("working_face_attached", false);
        var before = saved.copy();
        var migrated = NetworkSavedData.load(ItemPolicyDataTest.NETWORK, saved);
        var binding = migrated.findDirectBinding(ItemPolicyDataTest.NODE, ItemPolicyDataTest.CHANNEL)
                .orElseThrow();
        assertEquals(
                io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(ItemPolicyDataTest.policy()),
                binding.policy());
        assertEquals(io.github.loongin.omniresonance.network.WorkingFaces.explicit(48), binding.workingFaces());
        assertEquals(before, saved);
        assertFalse(migrated.isDirty());
    }

    @Test
    void oversizeStoredPolicyCannotDirtyOrReviseTheNetwork() {
        var data = ItemPolicyDataTest.data();
        var ids = new java.util.HashSet<net.minecraft.resources.ResourceLocation>();
        for (int index = 0; index < 132000; index++)
            ids.add(net.minecraft.resources.ResourceLocation.parse(
                    "missing:" + "a".repeat(114) + String.format(java.util.Locale.ROOT, "%06d", index)));
        var policy = new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Input(
                1,
                io.github.loongin.omniresonance.transfer.ResourceScope.customSet(ids),
                io.github.loongin.omniresonance.transfer.RedstoneCondition.IGNORE,
                null,
                io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                java.util.Map.of(),
                0);
        var original = data.findDirectBinding(ItemPolicyDataTest.NODE, ItemPolicyDataTest.CHANNEL)
                .orElseThrow();
        data.setDirty(false);
        long revision = data.topologyRevision();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> data.setDirectBinding(
                        ItemPolicyDataTest.NODE,
                        2,
                        ItemPolicyDataTest.CHANNEL,
                        new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(policy, java.util.Map.of()),
                        io.github.loongin.omniresonance.network.WorkingFaces.explicit(48),
                        false,
                        -1));
        assertFalse(data.isDirty());
        assertEquals(revision, data.topologyRevision());
        assertEquals(
                original,
                data.findDirectBinding(ItemPolicyDataTest.NODE, ItemPolicyDataTest.CHANNEL)
                        .orElseThrow());
    }
}
