// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class ComponentConditionPersistenceTest {
    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    @Test
    void selectedSnapshotEqualityIgnoresKeyOrder() {
        var a = new ComponentCondition.SelectedComponent(id("a:a"), CanonicalResourceNbt.encode(IntTag.valueOf(1)));
        var b = new ComponentCondition.SelectedComponent(id("a:b"), CanonicalResourceNbt.encode(IntTag.valueOf(2)));
        var first = new ComponentCondition.PersistenceSnapshot(
                ComponentCondition.Mode.SELECTED, ResourceTypes.ITEM, null, List.of(a, b));
        var second = new ComponentCondition.PersistenceSnapshot(
                ComponentCondition.Mode.SELECTED, ResourceTypes.ITEM, null, List.of(b, a));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void fullRejectsInvalidComponentKeys() {
        CompoundTag full = new CompoundTag();
        full.putInt("not a component", 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.FULL,
                        ResourceTypes.ITEM,
                        CanonicalResourceNbt.encode(full),
                        List.of()));
        full.remove("not a component");
        full.putInt("implicit_namespace", 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.FULL,
                        ResourceTypes.ITEM,
                        CanonicalResourceNbt.encode(full),
                        List.of()));
    }

    @Test
    void snapshotArraysCollectionsAndSemanticEqualityAreDetached() {
        byte[] bytes = CanonicalResourceNbt.encode(IntTag.valueOf(1));
        var a = new ComponentCondition.SelectedComponent(id("a:b"), bytes);
        var b = new ComponentCondition.SelectedComponent(id("a:b"), bytes.clone());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(
                a,
                new ComponentCondition.SelectedComponent(id("a:b"), CanonicalResourceNbt.encode(LongTag.valueOf(1))));
        var entries = new ArrayList<>(List.of(a));
        var snapshot = new ComponentCondition.PersistenceSnapshot(
                ComponentCondition.Mode.SELECTED, ResourceTypes.ITEM, null, entries);
        var condition = ComponentCondition.fromPersistenceSnapshot(snapshot);
        bytes[0] = 0;
        a.canonicalBytes()[0] = 0;
        entries.clear();
        assertEquals(snapshot, condition.persistenceSnapshot());
        assertEquals(snapshot.hashCode(), condition.persistenceSnapshot().hashCode());
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.selected().clear());
        CompoundTag full = new CompoundTag();
        full.putInt("a:b", 1);
        byte[] fullBytes = CanonicalResourceNbt.encode(full);
        var fullSnapshot = new ComponentCondition.PersistenceSnapshot(
                ComponentCondition.Mode.FULL, ResourceTypes.FLUID, fullBytes, List.of());
        var fullCondition = ComponentCondition.fromPersistenceSnapshot(fullSnapshot);
        fullBytes[0] = 0;
        fullSnapshot.fullBytes()[0] = 0;
        full.putLong("a:b", 1);
        assertEquals(fullSnapshot, fullCondition.persistenceSnapshot());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.SelectedComponent(id("a:b"), new byte[] {0}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.SELECTED, ResourceTypes.ENERGY, null, List.of(a)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.ID_ONLY, ResourceTypes.ITEM, null, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.FULL, ResourceTypes.ITEM, fullSnapshot.fullBytes(), List.of(a)));
    }

    @Test
    void canonicalLengthAndDepthBoundsRejectWithoutNativeAllocation() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream output = new java.io.DataOutputStream(bytes)) {
            output.writeByte(10);
            for (int index = 0; index < 65; index++) {
                output.writeByte(10);
                output.writeUTF("a:b");
            }
            for (int index = 0; index < 66; index++) output.writeByte(0);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.PersistenceSnapshot(
                        ComponentCondition.Mode.FULL, ResourceTypes.ITEM, bytes.toByteArray(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.SelectedComponent(id("a:b"), new byte[262145]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ComponentCondition.SelectedComponent(id("a:b"), new byte[] {9, 3, 127, -1, -1, -1}));
    }

    @Test
    void fullNormalizesCompoundKeyOrder() {
        byte[] reversed = {10, 3, 0, 3, 97, 58, 122, 0, 0, 0, 1, 3, 0, 3, 97, 58, 97, 0, 0, 0, 2, 0};
        CompoundTag compound = new CompoundTag();
        compound.putInt("a:a", 2);
        compound.putInt("a:z", 1);
        var a = new ComponentCondition.PersistenceSnapshot(
                ComponentCondition.Mode.FULL, ResourceTypes.ITEM, reversed, List.of());
        var b = new ComponentCondition.PersistenceSnapshot(
                ComponentCondition.Mode.FULL, ResourceTypes.ITEM, CanonicalResourceNbt.encode(compound), List.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
