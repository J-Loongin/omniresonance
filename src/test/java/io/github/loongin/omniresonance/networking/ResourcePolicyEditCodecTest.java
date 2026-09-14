// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ResourcePolicyEditCodecTest {
    static ResourcePolicyEdit edit() {
        return new ResourcePolicyEdit(
                7,
                ResourcePolicyEdit.Scope.all(),
                RedstoneCondition.NO_SIGNAL,
                new UUID(1, 2),
                FilterMode.BLACKLIST,
                new ResourcePolicyEdit.InputFields(Long.MAX_VALUE),
                List.of(
                        new ResourcePolicyEdit.Row(
                                ResourceTypes.ITEM,
                                new ResourceTransferPolicy.InputOverride(
                                        Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, 999)),
                        new ResourcePolicyEdit.Row(
                                ResourceTypes.FLUID,
                                new ResourceTransferPolicy.InputOverride(
                                        1, ResourceTransferPolicy.BatchMode.EXACT, Long.MAX_VALUE))),
                List.of(ResourceLocation.parse("example:missing")),
                true);
    }

    static byte[] raw(CompoundTag tag) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeByte(10);
        tag.write(output);
        return bytes.toByteArray();
    }

    static ResourcePolicyEdit empty() {
        return new ResourcePolicyEdit(
                1,
                ResourcePolicyEdit.Scope.all(),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                new ResourcePolicyEdit.InputFields(0),
                List.of(),
                List.of(),
                false);
    }

    @Test
    void preservesUnnormalizedOrderedRowsAndExclusiveFields() {
        assertEquals(edit(), ResourcePolicyEditCodec.decode(ResourcePolicyEditCodec.encode(edit())));
        var output = new ResourcePolicyEdit(
                1,
                ResourcePolicyEdit.Scope.all(),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                new ResourcePolicyEdit.OutputFields(Integer.MIN_VALUE),
                List.of(new ResourcePolicyEdit.Row(
                        ResourceTypes.ITEM, new ResourceTransferPolicy.OutputOverride(Integer.MAX_VALUE))),
                List.of(),
                false);
        assertEquals(output, ResourcePolicyEditCodec.decode(ResourcePolicyEditCodec.encode(output)));
        var defaults = new ResourcePolicyEdit(
                1,
                ResourcePolicyEdit.Scope.all(),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                new ResourcePolicyEdit.InputFields(0),
                List.of(new ResourcePolicyEdit.Row(
                        ResourceTypes.ITEM,
                        new ResourceTransferPolicy.InputOverride(
                                Integer.MAX_VALUE, ResourceTransferPolicy.BatchMode.GREEDY, 1))),
                List.of(),
                false);
        assertEquals(defaults, ResourcePolicyEditCodec.decode(ResourcePolicyEditCodec.encode(defaults)));
    }

    @Test
    void rejectsTruncatedTrailingUnknownAndMixedFields() {
        byte[] bytes = ResourcePolicyEditCodec.encode(edit());
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePolicyEditCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePolicyEditCodec.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        for (int index : new int[] {0, 4, 5, 10}) {
            byte[] bad = ResourcePolicyEditCodec.encode(empty());
            bad[index] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(bad));
        }
        byte[] mixed = ResourcePolicyEditCodec.encode(empty());
        mixed[5] = 1;
        assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(mixed));
    }

    static DataOutputStream header(ByteArrayOutputStream bytes, int flags) throws Exception {
        var data = new DataOutputStream(bytes);
        data.writeInt(0x4f525045);
        data.writeByte(1);
        data.writeByte(flags);
        data.writeInt(1);
        data.writeByte(0);
        if ((flags & 1) == 0) data.writeLong(0);
        else data.writeInt(0);
        return data;
    }

    static void id(DataOutputStream data, String id) throws Exception {
        data.writeByte(id.length());
        data.writeBytes(id);
    }

    @Test
    void rejectsDuplicateRowsIdsAndBadDiscriminants() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            var bytes = new ByteArrayOutputStream();
            var data = header(bytes, kind == 0 ? 2 : 0);
            if (kind == 0) {
                data.writeInt(2);
                id(data, "x:a");
                id(data, "x:a");
            }
            data.writeInt(kind == 1 ? 2 : 0);
            if (kind == 1) {
                id(data, "x:a");
                data.writeByte(0);
                id(data, "x:a");
                data.writeByte(0);
            }
            data.writeInt(kind == 2 ? 2 : 0);
            if (kind == 2) {
                id(data, "x:a");
                id(data, "x:a");
            }
            assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(bytes.toByteArray()));
        }
        for (int flags : new int[] {8, 128, 255}) {
            var bytes = new ByteArrayOutputStream();
            var data = header(bytes, 0);
            data.writeInt(1);
            id(data, "x:a");
            data.writeByte(flags);
            data.writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(bytes.toByteArray()));
        }
        var bytes = new ByteArrayOutputStream();
        var data = header(bytes, 1);
        data.writeInt(1);
        id(data, "x:a");
        data.writeByte(2);
        data.writeInt(0);
        assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(bytes.toByteArray()));
    }

    @Test
    void rejectsImpossibleLengthsBeforeAllocation() throws Exception {
        for (int count : new int[] {-1, 262145, Integer.MAX_VALUE}) {
            var bytes = new ByteArrayOutputStream();
            var data = header(bytes, 0);
            data.writeInt(count);
            data.writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(bytes.toByteArray()));
        }
        for (int length : new int[] {0, 129, 255}) {
            var bytes = new ByteArrayOutputStream();
            var data = header(bytes, 0);
            data.writeInt(1);
            data.writeByte(length);
            data.writeLong(0);
            assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(bytes.toByteArray()));
        }
    }

    @Test
    void acceptsActualSixteenMiBAndRejectsOneMore() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var data = header(bytes, 2);
        int remaining = ResourcePolicyEditCodec.MAX_BYTES - bytes.size() - 12;
        List<ResourceLocation> ids = new ArrayList<>();
        int index = 0;
        while (remaining > 0) {
            int size = remaining > 258 ? 129 : remaining > 129 ? remaining / 2 : remaining;
            String prefix = "x:" + index++ + "_";
            ids.add(ResourceLocation.parse(prefix + "a".repeat(size - 1 - prefix.length())));
            remaining -= size;
        }
        data.writeInt(ids.size());
        for (ResourceLocation type : ids) id(data, type.toString());
        data.writeInt(0);
        data.writeInt(0);
        byte[] exact = bytes.toByteArray();
        assertEquals(ResourcePolicyEditCodec.MAX_BYTES, exact.length);
        ResourcePolicyEdit decoded = ResourcePolicyEditCodec.decode(exact);
        assertEquals(exact.length, ResourcePolicyEditCodec.encode(decoded).length);
        assertEquals(ids, decoded.scope().ids());
        int last = ids.size() - 1;
        ids.set(last, ResourceLocation.parse(ids.get(last) + "a"));
        var tooLarge = new ResourcePolicyEdit(
                1,
                ResourcePolicyEdit.Scope.custom(ids),
                RedstoneCondition.IGNORE,
                null,
                FilterMode.WHITELIST,
                new ResourcePolicyEdit.InputFields(0),
                List.of(),
                List.of(),
                false);
        assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.encode(tooLarge));
        assertThrows(
                IllegalArgumentException.class,
                () -> ResourcePolicyEditCodec.decode(Arrays.copyOf(exact, exact.length + 1)));
    }

    @Test
    void poolViewDecodesWithoutOwningBackingBytes() {
        byte[] encoded = ResourcePolicyEditCodec.encode(edit());
        UUID player = new UUID(0, 1), session = new UUID(0, 2), transfer = new UUID(0, 3);
        try (var pool = new ManagementTransferPool()) {
            pool.beginUpload(player, session, transfer, encoded.length, 0);
            pool.upload(player, session, transfer, 0, encoded, 0);
            pool.finishUpload(
                    player,
                    session,
                    transfer,
                    0,
                    whole -> assertEquals(edit(), ResourcePolicyEditCodec.decode(whole)),
                    () -> true,
                    whole -> assertEquals(edit(), ResourcePolicyEditCodec.decode(whole)));
            assertEquals(0, pool.reservedBytes());
        }
    }

    @Test
    void rejectsUnknownFlagsBeforeAttemptingToReadTheirPayload() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var data = new DataOutputStream(bytes);
        data.writeInt(0x4f525045);
        data.writeByte(1);
        data.writeByte(128);
        var failure =
                assertThrows(IllegalArgumentException.class, () -> ResourcePolicyEditCodec.decode(bytes.toByteArray()));
        assertEquals("Unknown edit flags", failure.getMessage());
    }

    @Test
    void maximumPersistedScopeOnlyPolicyHasEncodableSeed() throws Exception {
        maximumPersistedSeed(true);
    }

    @Test
    void maximumPersistedGreedyRatePolicyHasEncodableSeed() throws Exception {
        maximumPersistedSeed(false);
    }

    private static void maximumPersistedSeed(boolean scopeOnly) throws Exception {
        var defaults = new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                ResourceTransferPolicy.defaults(io.github.loongin.omniresonance.network.TransferDirection.INPUT),
                java.util.Map.of());
        CompoundTag tag = io.github.loongin.omniresonance.persistence.ResourcePolicyNbt.encode(defaults);
        ListTag values;
        if (scopeOnly) {
            CompoundTag scope = new CompoundTag();
            scope.putString("kind", "custom_set");
            values = new ListTag();
            scope.put("types", values);
            tag.put("resource_scope", scope);
        } else values = tag.getList("resource_policy_overrides", 10);
        int remaining = ResourcePolicyEditCodec.MAX_BYTES - raw(tag).length;
        int overhead = scopeOnly ? 2 : 24;
        int maximum = overhead + 128;
        java.util.Set<ResourceLocation> registered = new java.util.HashSet<>();
        int index = 0;
        while (remaining > 0) {
            int size = remaining > maximum * 2 ? maximum : remaining > maximum ? remaining / 2 : remaining;
            String prefix = "x:" + index++ + "_";
            String id = prefix + "a".repeat(size - overhead - prefix.length());
            if (scopeOnly) values.add(StringTag.valueOf(id));
            else {
                CompoundTag row = new CompoundTag();
                row.putString("type_id", id);
                row.putInt("rate", 1);
                values.add(row);
            }
            registered.add(ResourceLocation.parse(id));
            remaining -= size;
        }
        assertEquals(ResourcePolicyEditCodec.MAX_BYTES, raw(tag).length);
        var stored = io.github.loongin.omniresonance.persistence.ResourcePolicyNbt.decode(
                tag, io.github.loongin.omniresonance.network.TransferDirection.INPUT, registered);
        ResourcePolicyEdit seed = ResourcePolicyEdit.fromStored(stored);
        byte[] encoded = ResourcePolicyEditCodec.encode(seed);
        assertEquals(scopeOnly ? 16648011 : 14790314, encoded.length);
        assertEquals(encoded.length, ResourcePolicyEditCodec.encodedSize(seed));
        assertTrue(encoded.length <= raw(tag).length);
        assertEquals(seed, ResourcePolicyEditCodec.decode(encoded));
        assertEquals(stored, ResourcePolicyEditCodec.decode(encoded).reconcile(stored, registered));
        int last = values.size() - 1;
        if (scopeOnly) values.set(last, StringTag.valueOf(values.getString(last) + "a"));
        else {
            CompoundTag row = values.getCompound(last);
            row.putString("type_id", row.getString("type_id") + "a");
        }
        assertEquals(ResourcePolicyEditCodec.MAX_BYTES + 1, raw(tag).length);
        assertThrows(
                IllegalArgumentException.class,
                () -> io.github.loongin.omniresonance.persistence.ResourcePolicyNbt.decode(
                        tag, io.github.loongin.omniresonance.network.TransferDirection.INPUT, registered));
    }

    @Test
    void countOnlyAdmissionMatchesBytesAndSupportsEveryStoredRowForm() throws Exception {
        var missingId = ResourceLocation.parse("x:");
        var missingRaw =
                new io.github.loongin.omniresonance.transfer.StoredResourcePolicy.RawOverride(null, null, null);
        for (var direction : io.github.loongin.omniresonance.network.TransferDirection.values()) {
            for (var scope : List.of(
                    io.github.loongin.omniresonance.transfer.ResourceScope.all(),
                    io.github.loongin.omniresonance.transfer.ResourceScope.customSet(
                            List.of(ResourceTypes.ITEM, missingId)))) {
                for (var mode : ResourceTransferPolicy.BatchMode.values()) {
                    for (int rate : new int[] {1, Integer.MAX_VALUE}) {
                        ResourceTransferPolicy policy = direction
                                        == io.github.loongin.omniresonance.network.TransferDirection.INPUT
                                ? new ResourceTransferPolicy.Input(
                                        1,
                                        scope,
                                        RedstoneCondition.SIGNAL,
                                        new UUID(1, 2),
                                        FilterMode.BLACKLIST,
                                        java.util.Map.of(
                                                ResourceTypes.ITEM,
                                                new ResourceTransferPolicy.InputOverride(rate, mode, Long.MAX_VALUE)),
                                        0)
                                : new ResourceTransferPolicy.Output(
                                        1,
                                        scope,
                                        RedstoneCondition.SIGNAL,
                                        new UUID(1, 2),
                                        FilterMode.BLACKLIST,
                                        java.util.Map.of(
                                                ResourceTypes.ITEM, new ResourceTransferPolicy.OutputOverride(rate)),
                                        0);
                        var stored = new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                                policy, java.util.Map.of(missingId, missingRaw));
                        var seed = ResourcePolicyEdit.fromStored(stored);
                        int counted = ResourcePolicyEditCodec.encodedSize(seed);
                        assertTrue(counted
                                <= raw(io.github.loongin.omniresonance.persistence.ResourcePolicyNbt.encode(stored))
                                        .length);
                        assertEquals(counted, ResourcePolicyEditCodec.encode(seed).length);
                        assertEquals(seed, ResourcePolicyEditCodec.decode(ResourcePolicyEditCodec.encode(seed)));
                    }
                }
            }
        }
    }
}
