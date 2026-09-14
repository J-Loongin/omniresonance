// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.filter.ResourceRuleIntent;
import io.github.loongin.omniresonance.persistence.ResourceFilterPresetNbt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.ResourceLocation;

/** Bounded full-rule S2C snapshots and disjoint C2S typed intents; persistence bytes never enter intent parsing. */
public final class FullFilterCodec {
    private FullFilterCodec() {}

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    public static int snapshotSize(ResourceFilterPreset snapshot) {
        CompoundTag tag = ResourceFilterPresetNbt.encode(snapshot);
        return count(output -> {
            output.writeByte(10);
            tag.write(output);
        });
    }

    public static byte[] snapshot(ResourceFilterPreset snapshot) {
        CompoundTag tag = ResourceFilterPresetNbt.encode(snapshot);
        return encode(output -> {
            output.writeByte(10);
            tag.write(output);
        });
    }

    public static ResourceFilterPreset readSnapshot(byte[] bytes) {
        bound(bytes.length);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readUnsignedByte() != 10) throw new IllegalArgumentException("Invalid snapshot root");
            CompoundTag tag = CompoundTag.TYPE.load(input, NbtAccounter.create(128L * 1024 * 1024));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing snapshot bytes");
            ResourceFilterPreset result = ResourceFilterPresetNbt.decode(tag);
            if (result.rules().size() != 1) throw new IllegalArgumentException("Expected one rule snapshot");
            return result;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid snapshot", failure);
        }
    }

    /** Exact registered SaveResourceRule envelope admission; headers never force a valid inline intent into chunks. */
    public static boolean intentFitsPacket(int size) {
        if (size < 0 || size > ManagementTransferPool.MAXIMUM_OBJECT_BYTES)
            throw new IllegalArgumentException("Invalid intent length");
        int varint = size < 128 ? 1 : size < 16384 ? 2 : size < 2097152 ? 3 : 4;
        return registeredIdBytes(NetworkTerminalRequest.TYPE.id()) + 42 + varint + size <= 262144;
    }

    /** Actual registered custom-payload identifier bytes, including its native UTF-8 length prefix. */
    public static int registeredIdBytes(ResourceLocation id) {
        int length = id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return net.minecraft.network.VarInt.getByteSize(length) + length;
    }

    /** Success snapshot decision includes the registered response identifier and every FullRule field. */
    public static boolean snapshotFitsPacket(int size, boolean sampled) {
        bound(size);
        return registeredIdBytes(NetworkTerminalResponse.TYPE.id())
                        + 51
                        + (sampled ? 16 : 0)
                        + net.minecraft.network.VarInt.getByteSize(size)
                        + size
                <= 262144;
    }

    /** Rejects an oversized complete registered envelope before decoding body-owned values or allocating fields. */
    static void requireBodyBound(net.minecraft.network.FriendlyByteBuf buffer, ResourceLocation id) {
        if (buffer.readableBytes() > 262144 - registeredIdBytes(id))
            throw new io.netty.handler.codec.DecoderException("Registered terminal envelope exceeds byte limit");
    }

    /** Checks the complete native custom-payload envelope after body encoding, without changing buffer ownership. */
    static void requireEncodedBodyBound(net.minecraft.network.FriendlyByteBuf buffer, int start, ResourceLocation id) {
        if (buffer.writerIndex() - start > 262144 - registeredIdBytes(id))
            throw new io.netty.handler.codec.EncoderException("Registered terminal envelope exceeds byte limit");
    }

    public static int intentSize(ResourceRuleIntent intent) {
        return count(output -> writeIntent(output, intent));
    }

    public static byte[] intent(ResourceRuleIntent intent) {
        return encode(output -> writeIntent(output, intent));
    }

    private static void writeIntent(DataOutputStream output, ResourceRuleIntent intent) throws IOException {
        if (intent instanceof ResourceRuleIntent.Reference reference) {
            output.writeByte(1);
            uuid(output, reference.presetId());
            return;
        }
        ResourceRuleIntent.Match match = (ResourceRuleIntent.Match) intent;
        output.writeByte(0);
        output.writeUTF(match.typeId().toString());
        if (match.selector() instanceof ResourceFilterRule.WholeType) output.writeByte(0);
        else if (match.selector() instanceof ResourceFilterRule.Exact exact) {
            output.writeByte(1);
            output.writeUTF(exact.resourceId().toString());
        } else if (match.selector() instanceof ResourceFilterRule.TagSelector tag) {
            output.writeByte(2);
            output.writeUTF(tag.tagId().toString());
        } else {
            output.writeByte(3);
            output.writeUTF(((ResourceFilterRule.Glob) match.selector()).glob().pattern());
        }
        output.writeByte(match.mode().ordinal());
        output.writeBoolean(match.sampleToken() != null);
        if (match.sampleToken() != null) uuid(output, match.sampleToken());
        output.writeInt(match.selectedKeys().size());
        for (ResourceLocation key : match.selectedKeys().stream().sorted().toList()) output.writeUTF(key.toString());
    }

    public static ResourceRuleIntent readIntent(byte[] bytes) {
        bound(bytes.length);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            ResourceRuleIntent result;
            int kind = input.readUnsignedByte();
            if (kind == 1) result = new ResourceRuleIntent.Reference(uuid(input));
            else if (kind == 0) {
                ResourceLocation type = id(input.readUTF());
                int selector = input.readUnsignedByte();
                ResourceFilterRule.Selector selected =
                        switch (selector) {
                            case 0 -> ResourceFilterRule.Selector.wholeType();
                            case 1 -> ResourceFilterRule.Selector.exact(id(input.readUTF()));
                            case 2 -> ResourceFilterRule.Selector.tag(id(input.readUTF()));
                            case 3 -> ResourceFilterRule.Selector.glob(input.readUTF());
                            default -> throw new IllegalArgumentException("Unknown selector");
                        };
                int mode = input.readUnsignedByte();
                if (mode > 2) throw new IllegalArgumentException("Unknown component mode");
                UUID token = input.readBoolean() ? uuid(input) : null;
                int count = input.readInt();
                if (count < 0 || count > ResourceFilterPreset.MAX_ENTRIES || count > input.available() / 4)
                    throw new IllegalArgumentException("Invalid key count");
                Set<ResourceLocation> keys = new HashSet<>();
                for (int i = 0; i < count; i++)
                    if (!keys.add(id(input.readUTF()))) throw new IllegalArgumentException("Duplicate component key");
                result = new ResourceRuleIntent.Match(
                        type, selected, ComponentCondition.Mode.values()[mode], keys, token);
            } else throw new IllegalArgumentException("Unknown rule intent");
            if (input.available() != 0) throw new IllegalArgumentException("Trailing intent bytes");
            return result;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid rule intent", failure);
        }
    }

    public static ResourceRuleIntent readIntent(ManagementObjectView view) {
        byte[] bytes = new byte[view.length()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = view.byteAt(i);
        return readIntent(bytes);
    }

    private static ResourceLocation id(String text) {
        ResourceLocation id = ResourceLocation.parse(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical ID");
        return id;
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static int count(Writer writer) {
        class Counter extends OutputStream {
            int size;

            @Override
            public void write(int value) {
                size = Math.addExact(size, 1);
                bound(size);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                size = Math.addExact(size, length);
                bound(size);
            }
        }
        Counter counter = new Counter();
        try {
            writer.write(new DataOutputStream(counter));
            return counter.size;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid managed filter", failure);
        }
    }

    private static byte[] encode(Writer writer) {
        int size = count(writer);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
        try {
            writer.write(new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid managed filter", failure);
        }
    }

    private static void bound(int length) {
        if (length < 0 || length > ManagementTransferPool.MAXIMUM_OBJECT_BYTES)
            throw new IllegalArgumentException("Invalid managed filter length");
    }
}
