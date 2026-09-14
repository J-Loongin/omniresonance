// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import org.junit.jupiter.api.Test;

final class CanonicalResourceNbtTest {
    @Test
    void compoundOrderIsIrrelevantButListOrderAndNumberTypesMatter() {
        CompoundTag a = new CompoundTag();
        a.putInt("a", 1);
        a.putLong("z", 2);
        CompoundTag b = new CompoundTag();
        b.putLong("z", 2);
        b.putInt("a", 1);
        assertArrayEquals(CanonicalResourceNbt.encode(a), CanonicalResourceNbt.encode(b));
        ListTag x = new ListTag();
        x.add(IntTag.valueOf(1));
        x.add(IntTag.valueOf(2));
        ListTag y = new ListTag();
        y.add(IntTag.valueOf(2));
        y.add(IntTag.valueOf(1));
        assertFalse(Arrays.equals(CanonicalResourceNbt.encode(x), CanonicalResourceNbt.encode(y)));
        assertFalse(Arrays.equals(
                CanonicalResourceNbt.encode(IntTag.valueOf(1)), CanonicalResourceNbt.encode(LongTag.valueOf(1))));
        assertEquals(a, CanonicalResourceNbt.decode(CanonicalResourceNbt.encode(a)));
    }

    @Test
    void sizeDepthAndMalformedInputsAreRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalResourceNbt.encode(new ByteArrayTag(new byte[262144])));
        CompoundTag root = new CompoundTag();
        CompoundTag current = root;
        for (int i = 0; i < 65; i++) {
            CompoundTag child = new CompoundTag();
            current.put("x", child);
            current = child;
        }
        assertThrows(IllegalArgumentException.class, () -> CanonicalResourceNbt.encode(root));
        for (byte[] data : new byte[][] {
            new byte[262145],
            new byte[] {99},
            new byte[] {7, 127, -1, -1, -1},
            new byte[] {9, 1, 127, -1, -1, -1},
            new byte[] {3, 0, 0, 0, 1, 0},
            new byte[] {10, 1, 0, 1, 97, 1, 1, 0, 1, 97, 2, 0}
        }) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalResourceNbt.decode(data));
        }
    }

    @Test
    void nestedCompoundsAreSortedAndAllNativeTagKindsRoundtrip() {
        CompoundTag left = new CompoundTag();
        left.putInt("a", 1);
        left.putLong("b", 2);
        CompoundTag right = new CompoundTag();
        right.putLong("b", 2);
        right.putInt("a", 1);
        CompoundTag outerLeft = new CompoundTag();
        outerLeft.put("nested", left);
        CompoundTag outerRight = new CompoundTag();
        outerRight.put("nested", right);
        assertArrayEquals(CanonicalResourceNbt.encode(outerLeft), CanonicalResourceNbt.encode(outerRight));
        for (net.minecraft.nbt.Tag tag : new net.minecraft.nbt.Tag[] {
            net.minecraft.nbt.EndTag.INSTANCE,
            net.minecraft.nbt.ByteTag.valueOf((byte) 1),
            net.minecraft.nbt.ShortTag.valueOf((short) 2),
            IntTag.valueOf(3),
            LongTag.valueOf(4),
            net.minecraft.nbt.FloatTag.valueOf(1.5f),
            net.minecraft.nbt.DoubleTag.valueOf(2.5),
            new ByteArrayTag(new byte[] {1, 2}),
            net.minecraft.nbt.StringTag.valueOf("Unicode \u4e2d"),
            new ListTag(),
            outerLeft,
            new net.minecraft.nbt.IntArrayTag(new int[] {1, 2}),
            new net.minecraft.nbt.LongArrayTag(new long[] {1, 2})
        }) {
            assertEquals(tag, CanonicalResourceNbt.decode(CanonicalResourceNbt.encode(tag)));
        }
        ByteArrayTag boundary = new ByteArrayTag(new byte[262139]);
        assertEquals(262144, CanonicalResourceNbt.encode(boundary).length);
        assertEquals(boundary, CanonicalResourceNbt.decode(CanonicalResourceNbt.encode(boundary)));
    }
}
