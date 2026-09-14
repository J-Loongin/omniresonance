// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Actual unnamed native NBT size gate shared by managed object codecs. */
final class ManagedObjectNbtSize {
    private ManagedObjectNbtSize() {}

    static void validate(CompoundTag tag) {
        try (DataOutputStream output = new DataOutputStream(new LimitedOutput())) {
            output.writeByte(Tag.TAG_COMPOUND);
            tag.write(output);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Managed object NBT cannot be encoded", exception);
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private int count;

        @Override
        public void write(int value) {
            requireSpace(1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireSpace(length);
        }

        private void requireSpace(int length) {
            if (length > ResourcePolicyNbt.MAX_BYTES - count)
                throw new IllegalArgumentException("Managed object exceeds 16 MiB");
            count += length;
        }
    }
}
