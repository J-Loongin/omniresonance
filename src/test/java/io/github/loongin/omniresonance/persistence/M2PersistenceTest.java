// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class M2PersistenceTest {
    static final UUID NETWORK = new UUID(1, 1), OWNER = new UUID(2, 2);

    @Test
    void realV5NetworkMigratesToV6WithEmptyRecoveryWithoutMutatingSource() {
        CompoundTag tag = NetworkSavedData.create(
                        new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()))
                .save(new CompoundTag(), RegistryAccess.EMPTY);
        tag.putInt("schema_version", 5);
        tag.remove("audit_entries");
        tag.remove("bucket_created_mask");
        tag.remove("recovery");
        CompoundTag before = tag.copy();
        NetworkSavedData loaded = NetworkSavedData.load(NETWORK, tag);
        CompoundTag saved = loaded.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(11, saved.getInt("schema_version"));
        assertEquals(new ListTag(), saved.get("recovery"));
        assertFalse(loaded.isDirty());
        assertEquals(before, tag);
    }

    @Test
    void realV1OwnerMigratesWithPointerAndEmptyLibrary() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", 1);
        tag.remove("audit_entries");
        tag.putUUID("owner_id", OWNER);
        tag.putUUID("default_network_id", NETWORK);
        CompoundTag before = tag.copy();
        OwnerSavedData loaded = OwnerSavedData.load(OWNER, tag);
        CompoundTag saved = loaded.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertEquals(4, saved.getInt("schema_version"));
        assertEquals(NETWORK, loaded.defaultNetworkId().orElseThrow());
        assertEquals(new ListTag(), saved.get("filter_presets"));
        assertEquals(0L, saved.getLong("preset_library_revision"));
        assertFalse(loaded.isDirty());
        assertEquals(before, tag);
    }

    @Test
    void recoveryRoundTripRetainsUnknownRawBytesAndDirtyCallback() {
        NetworkSavedData data =
                NetworkSavedData.create(new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()));
        data.setDirty(false);
        io.github.loongin.omniresonance.transfer.ResourceVariantKey key =
                new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                        net.minecraft.resources.ResourceLocation.parse("unknown:raw"), new byte[] {0, 1, -1});
        try (var reservation =
                data.recovery().reserve(key, Long.MAX_VALUE, 64, 1048576).orElseThrow()) {
            reservation.commit(Long.MAX_VALUE);
        }
        org.junit.jupiter.api.Assertions.assertTrue(data.isDirty());
        CompoundTag saved = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        NetworkSavedData restored = NetworkSavedData.load(NETWORK, saved);
        assertEquals(Long.MAX_VALUE, restored.recovery().amount(key));
        assertFalse(restored.isDirty());
        assertEquals(saved, restored.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    @Test
    void malformedRecoveryRejectsEntireShardAndLeavesInputUnchanged() {
        CompoundTag valid = NetworkSavedData.create(
                        new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()))
                .save(new CompoundTag(), RegistryAccess.EMPTY);
        valid.putInt("schema_version", 6);
        valid.remove("audit_entries");
        valid.remove("bucket_created_mask");
        CompoundTag entry = new CompoundTag();
        entry.putString("type_id", "unknown:raw");
        entry.putByteArray("canonical_bytes", new byte[0]);
        entry.putLong("amount", 3);
        ListTag entries = new ListTag();
        entries.add(entry);
        entries.add(entry.copy());
        valid.put("recovery", entries);
        CompoundTag before = valid.copy();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, valid));
        assertEquals(before, valid);
        entries.remove(1);
        entry.putLong("amount", -1);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, valid));
    }

    @Test
    void recoveryRequiresCanonicalFullTypeIdAndRetainsRestoredOverQuotaData() {
        NetworkSavedData data =
                NetworkSavedData.create(new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()));
        CompoundTag tag = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        CompoundTag entry = new CompoundTag();
        entry.putString("type_id", "raw");
        entry.putByteArray("canonical_bytes", new byte[0]);
        entry.putLong("amount", 1);
        ListTag list = new ListTag();
        list.add(entry);
        tag.put("recovery", list);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, tag));
        entry.putString("type_id", "unknown:raw");
        NetworkSavedData restored = NetworkSavedData.load(NETWORK, tag);
        var key = new io.github.loongin.omniresonance.transfer.ResourceVariantKey(
                net.minecraft.resources.ResourceLocation.parse("unknown:raw"), new byte[0]);
        assertEquals(1, restored.recovery().amount(key));
        org.junit.jupiter.api.Assertions.assertTrue(
                restored.recovery().reserve(key, 1, 0, 0).isEmpty());
        assertEquals(1, restored.recovery().amount(key));
        assertFalse(restored.isDirty());
    }

    @Test
    void currentSchemasRejectEveryMissingRequiredOuterFieldAndWrongRecoveryTypes() {
        NetworkSavedData data =
                NetworkSavedData.create(new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()));
        CompoundTag network = data.save(new CompoundTag(), RegistryAccess.EMPTY);
        for (String key : network.getAllKeys()) {
            CompoundTag broken = network.copy();
            broken.remove(key);
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, broken), key);
        }
        CompoundTag owner = OwnerSavedData.create(OWNER, null).save(new CompoundTag(), RegistryAccess.EMPTY);
        for (String key : owner.getAllKeys()) {
            CompoundTag broken = owner.copy();
            broken.remove(key);
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> OwnerSavedData.load(OWNER, broken), key);
        }
        ListTag list = new ListTag();
        list.add(net.minecraft.nbt.StringTag.valueOf("bad"));
        network.put("recovery", list);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> NetworkSavedData.load(NETWORK, network));
    }

    @Test
    void ownerPresetAndRecoveryEntrypointsRejectWrongThreadBeforeAnyMutation() throws Exception {
        OwnerSavedData owner = OwnerSavedData.create(OWNER, null);
        NetworkSavedData network =
                NetworkSavedData.create(new NetworkMetadata(NETWORK, OWNER, new ManagedName("Network"), 0, Set.of()));
        owner.setDirty(false);
        network.setDirty(false);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, owner::presets);
                        org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalStateException.class, owner::presetLibraryRevision);
                        org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalStateException.class, () -> owner.findPreset(NETWORK));
                        org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalStateException.class, () -> owner.removePreset(NETWORK, 0));
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, network::recovery);
                    })
                    .get();
        }
        assertFalse(owner.isDirty());
        assertFalse(network.isDirty());
    }
}
