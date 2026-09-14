// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Bounded vanilla binary NBT with deterministic compound ordering and no root name. */
public final class CanonicalResourceNbt {
    public static final int MAX_BYTES = 262144;
    public static final int MAX_DEPTH = 64;
    private static final long MAX_DECODE_HEAP_BYTES = 64L * MAX_BYTES;

    private CanonicalResourceNbt() {}

    /** Pure caller-thread encoding; rejects oversized or invalid values without changing the tag. */
    public static byte[] encode(Tag tag) {
        Objects.requireNonNull(tag);
        LimitedOutput bytes = new LimitedOutput();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(tag.getId());
            writePayload(tag, output, 0);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Resource NBT cannot be encoded", ex);
        }
    }

    /** Pure bounded decoding. Validates structure before native allocations; returns a detached tag. */
    public static Tag decode(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid resource NBT length");
        }
        try {
            DataInputStream validation = new DataInputStream(new ByteArrayInputStream(bytes));
            validatePayload(validation.readUnsignedByte(), validation, 0);
            if (validation.available() != 0) {
                throw new IllegalArgumentException("Trailing resource NBT bytes");
            }
            return NbtIo.readAnyTag(
                    new DataInputStream(new ByteArrayInputStream(bytes)),
                    new NbtAccounter(MAX_DECODE_HEAP_BYTES, MAX_DEPTH + 1));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalArgumentException("Invalid resource NBT", ex);
        }
    }

    private static void writePayload(Tag tag, DataOutputStream output, int depth) throws IOException {
        checkDepthAndType(depth, tag.getId());
        if (tag instanceof CompoundTag compound) {
            if (compound.size() > MAX_BYTES / 4) {
                throw new IllegalArgumentException("Too many resource NBT keys");
            }
            ArrayList<String> keys = new ArrayList<>(compound.getAllKeys());
            Collections.sort(keys);
            for (String key : keys) {
                Tag child = Objects.requireNonNull(compound.get(key));
                if (child.getId() == Tag.TAG_END) {
                    throw new IllegalArgumentException("End tag in compound");
                }
                output.writeByte(child.getId());
                output.writeUTF(key);
                writePayload(child, output, depth + 1);
            }
            output.writeByte(Tag.TAG_END);
        } else if (tag instanceof ListTag list) {
            int type = list.getElementType();
            checkDepthAndType(depth, type);
            if (list.size() > MAX_BYTES || type == Tag.TAG_END && !list.isEmpty()) {
                throw new IllegalArgumentException("Invalid resource NBT list");
            }
            output.writeByte(type);
            output.writeInt(list.size());
            for (Tag child : list) {
                if (child.getId() != type) {
                    throw new IllegalArgumentException("Mixed NBT list");
                }
                writePayload(child, output, depth + 1);
            }
        } else {
            tag.write(output);
        }
    }

    private static void validatePayload(int type, DataInputStream input, int depth) throws IOException {
        checkDepthAndType(depth, type);
        switch (type) {
            case Tag.TAG_END -> {}
            case Tag.TAG_BYTE -> skip(input, 1);
            case Tag.TAG_SHORT -> skip(input, 2);
            case Tag.TAG_INT, Tag.TAG_FLOAT -> skip(input, 4);
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> skip(input, 8);
            case Tag.TAG_BYTE_ARRAY -> skipArray(input, 1);
            case Tag.TAG_STRING -> input.readUTF();
            case Tag.TAG_LIST -> {
                int childType = input.readUnsignedByte();
                checkDepthAndType(depth, childType);
                int count = input.readInt();
                if (count < 0 || count > input.available() || childType == Tag.TAG_END && count != 0) {
                    throw new IllegalArgumentException("Invalid NBT list length");
                }
                for (int i = 0; i < count; i++) {
                    validatePayload(childType, input, depth + 1);
                }
            }
            case Tag.TAG_COMPOUND -> {
                Set<String> names = new HashSet<>();
                int childType;
                while ((childType = input.readUnsignedByte()) != Tag.TAG_END) {
                    if (!names.add(input.readUTF())) {
                        throw new IllegalArgumentException("Duplicate NBT key");
                    }
                    validatePayload(childType, input, depth + 1);
                }
            }
            case Tag.TAG_INT_ARRAY -> skipArray(input, 4);
            case Tag.TAG_LONG_ARRAY -> skipArray(input, 8);
            default -> throw new IllegalArgumentException("Unknown NBT type");
        }
    }

    private static void skipArray(DataInputStream input, int width) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available() / width) {
            throw new IllegalArgumentException("Invalid NBT array length");
        }
        skip(input, length * width);
    }

    private static void skip(DataInputStream input, int bytes) throws IOException {
        if (input.skipBytes(bytes) != bytes) {
            throw new IllegalArgumentException("Truncated NBT");
        }
    }

    private static void checkDepthAndType(int depth, int type) {
        if (depth > MAX_DEPTH || type < Tag.TAG_END || type > Tag.TAG_LONG_ARRAY) {
            throw new IllegalArgumentException("Invalid NBT depth or type");
        }
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        @Override
        public synchronized void write(int value) {
            requireSpace(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            requireSpace(length);
            super.write(bytes, offset, length);
        }

        private void requireSpace(int length) {
            if (length > MAX_BYTES - count) {
                throw new IllegalArgumentException("Resource NBT exceeds byte limit");
            }
        }
    }
}
