// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure synchronous versioned binary edit frames, independent of persisted NBT. No world access,
 * simulation, registration, authority checks or mutation occur. Failures throw without partial output.
 * Wire reads borrow stable bytes without copying the frame and return detached immutable DTOs.
 * Length and counts are checked before allocation: 16 MiB frame, 128-byte ASCII IDs, 262144 entries
 * per list and combined rows/retained IDs. The fixed schema has no recursive structures or native NBT
 * parser/heap quota. Callers budget decoded DTOs separately from pool bytes and use scoped views only
 * within synchronous owner-thread completion callbacks.
 *
 * Every trusted stored-policy seed fits: common wire fields are smaller than common NBT fields;
 * each scope ID saves one byte; each missing retained ID is smaller than its original raw row;
 * every normalized known row is smaller than its non-default persisted row. Default values are omitted
 * from row bytes, never row identities; decoding restores their exact typed values. The wire batch
 * default is always one, independent of adapters or future editor defaults.
 */
public final class ResourcePolicyEditCodec {
    public static final int MAX_BYTES = ManagementTransferPool.MAXIMUM_OBJECT_BYTES;
    private static final int MAGIC = 0x4f525045;
    private static final int VERSION = 2;
    private static final int OUTPUT = 1;
    private static final int CUSTOM = 2;
    private static final int PRESET = 4;
    private static final int BLACKLIST = 8;
    private static final int DISCARD = 16;
    private static final int RATE = 1;
    private static final int EXACT = 2;
    private static final int BATCH = 4;

    private ResourcePolicyEditCodec() {}

    /**
     * Counts exact wire bytes through the canonical writer without allocating any bulk buffer. Pure and
     * thread-safe for immutable input; rejects oversize. A pool caller uses this before reserving, then
     * invokes encode from the admitted factory. No input is retained or mutated.
     */
    public static int encodedSize(ResourcePolicyEdit edit) {
        CountingOutput output = new CountingOutput();
        write(edit, output);
        return output.count;
    }

    /** Returns one caller-owned frame after exact size admission; never changes the immutable edit. */
    public static byte[] encode(ResourcePolicyEdit edit) {
        int size = encodedSize(edit);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
        write(edit, bytes);
        return bytes.toByteArray();
    }

    /** Borrows a caller-stabilized frame for this call; malformed input fails before returning a DTO. */
    public static ResourcePolicyEdit decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return read(bytes.length, new ByteArrayInputStream(bytes));
    }

    /** Borrows a live owner-thread management view; no backing frame copy or reference escapes the call. */
    public static ResourcePolicyEdit decode(ManagementObjectView whole) {
        Objects.requireNonNull(whole, "whole");
        int length = whole.length();
        return read(length, new ViewInput(length, index -> whole.byteAt(index) & 255));
    }

    private static void write(ResourcePolicyEdit edit, OutputStream bytes) {
        Objects.requireNonNull(edit, "edit");
        try {
            DataOutputStream output = new DataOutputStream(bytes);
            boolean input = edit.fields() instanceof ResourcePolicyEdit.InputFields;
            int flags = (input ? 0 : OUTPUT)
                    | (edit.scope().kind() == ResourceScope.Kind.CUSTOM_SET ? CUSTOM : 0)
                    | (edit.filterPresetId() != null ? PRESET : 0)
                    | (edit.filterMode() == FilterMode.BLACKLIST ? BLACKLIST : 0)
                    | (edit.discardPreviousDirectionFields() ? DISCARD : 0);
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(flags);
            output.writeInt(edit.intervalTicks());
            output.writeByte(
                    switch (edit.redstoneCondition()) {
                        case IGNORE -> 0;
                        case SIGNAL -> 1;
                        case NO_SIGNAL -> 2;
                    });
            if (edit.filterPresetId() != null) {
                output.writeLong(edit.filterPresetId().getMostSignificantBits());
                output.writeLong(edit.filterPresetId().getLeastSignificantBits());
            }
            if (input) output.writeLong(((ResourcePolicyEdit.InputFields) edit.fields()).keepCount());
            else output.writeInt(((ResourcePolicyEdit.OutputFields) edit.fields()).priority());
            if ((flags & CUSTOM) != 0) writeIds(output, edit.scope().ids());
            output.writeInt(edit.rows().size());
            for (ResourcePolicyEdit.Row row : edit.rows()) {
                writeId(output, row.typeId());
                ResourceTransferPolicy.TypeOverride value = row.value();
                int rowFlags = value.rate() == ResourceTransferPolicy.DEFAULT_RATE ? 0 : RATE;
                if (value instanceof ResourceTransferPolicy.InputOverride values) {
                    if (values.batchMode() == ResourceTransferPolicy.BatchMode.EXACT) rowFlags |= EXACT;
                    if (values.batchSize() != 1) rowFlags |= BATCH;
                }
                output.writeByte(rowFlags);
                if ((rowFlags & RATE) != 0) output.writeLong(value.rate());
                if ((rowFlags & BATCH) != 0)
                    output.writeLong(((ResourceTransferPolicy.InputOverride) value).batchSize());
            }
            writeIds(output, edit.retainedMissingIds());
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode edit", failure);
        }
    }

    private static void writeIds(DataOutputStream output, List<ResourceLocation> ids) throws IOException {
        output.writeInt(ids.size());
        for (ResourceLocation id : ids) writeId(output, id);
    }

    private static void writeId(DataOutputStream output, ResourceLocation id) throws IOException {
        String value = id.toString();
        output.writeByte(value.length());
        output.writeBytes(value);
    }

    private static ResourcePolicyEdit read(int length, InputStream bytes) {
        if (length < 1 || length > MAX_BYTES) throw new IllegalArgumentException("Invalid edit frame length");
        try {
            DataInputStream input = new DataInputStream(bytes);
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION)
                throw new IllegalArgumentException("Unknown edit format");
            int flags = input.readUnsignedByte();
            if ((flags & ~(OUTPUT | CUSTOM | PRESET | BLACKLIST | DISCARD)) != 0)
                throw new IllegalArgumentException("Unknown edit flags");
            int interval = input.readInt();
            RedstoneCondition redstone =
                    switch (input.readUnsignedByte()) {
                        case 0 -> RedstoneCondition.IGNORE;
                        case 1 -> RedstoneCondition.SIGNAL;
                        case 2 -> RedstoneCondition.NO_SIGNAL;
                        default -> throw new IllegalArgumentException("Unknown redstone condition");
                    };
            UUID preset = (flags & PRESET) != 0 ? new UUID(input.readLong(), input.readLong()) : null;
            boolean isInput = (flags & OUTPUT) == 0;
            ResourcePolicyEdit.DirectionFields fields = isInput
                    ? new ResourcePolicyEdit.InputFields(input.readLong())
                    : new ResourcePolicyEdit.OutputFields(input.readInt());
            ResourcePolicyEdit.Scope scope = (flags & CUSTOM) != 0
                    ? ResourcePolicyEdit.Scope.custom(readIds(input, ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS))
                    : ResourcePolicyEdit.Scope.all();
            int count = count(input, ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS, 4);
            List<ResourcePolicyEdit.Row> rows = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                ResourceLocation id = readId(input);
                int rowFlags = input.readUnsignedByte();
                if ((rowFlags & ~(isInput ? RATE | EXACT | BATCH : RATE)) != 0)
                    throw new IllegalArgumentException("Unknown or mixed direction row flags");
                long rate = (rowFlags & RATE) != 0 ? input.readLong() : ResourceTransferPolicy.DEFAULT_RATE;
                ResourceTransferPolicy.TypeOverride value = isInput
                        ? new ResourceTransferPolicy.InputOverride(
                                rate,
                                (rowFlags & EXACT) != 0
                                        ? ResourceTransferPolicy.BatchMode.EXACT
                                        : ResourceTransferPolicy.BatchMode.GREEDY,
                                (rowFlags & BATCH) != 0 ? input.readLong() : 1)
                        : new ResourceTransferPolicy.OutputOverride(rate);
                rows.add(new ResourcePolicyEdit.Row(id, value));
            }
            List<ResourceLocation> retained = readIds(input, ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS - count);
            if (input.read() != -1) throw new IllegalArgumentException("Trailing edit bytes");
            return new ResourcePolicyEdit(
                    interval,
                    scope,
                    redstone,
                    preset,
                    (flags & BLACKLIST) != 0 ? FilterMode.BLACKLIST : FilterMode.WHITELIST,
                    fields,
                    rows,
                    retained,
                    (flags & DISCARD) != 0);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid edit frame", failure);
        }
    }

    private static List<ResourceLocation> readIds(DataInputStream input, int maximum) throws IOException {
        int count = count(input, maximum, 3);
        List<ResourceLocation> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add(readId(input));
        return ids;
    }

    private static int count(DataInputStream input, int maximum, int minimumEntryBytes) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum || count > input.available() / minimumEntryBytes)
            throw new IllegalArgumentException("Invalid edit collection length");
        return count;
    }

    private static ResourceLocation readId(DataInputStream input) throws IOException {
        int length = input.readUnsignedByte();
        if (length < 2 || length > ResourceScope.MAXIMUM_RESOURCE_TYPE_ID_BYTES || length > input.available())
            throw new IllegalArgumentException("Invalid edit ID length");
        char[] chars = new char[length];
        for (int index = 0; index < length; index++) {
            int next = input.readUnsignedByte();
            if (next == 0 || next > 127) throw new IllegalArgumentException("Non-ASCII edit ID");
            chars[index] = (char) next;
        }
        String value = new String(chars);
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical edit ID");
        return id;
    }

    private static final class CountingOutput extends OutputStream {
        int count;

        @Override
        public void write(int value) {
            add(1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            add(length);
        }

        private void add(int length) {
            if (length > MAX_BYTES - count) throw new IllegalArgumentException("Edit exceeds 16 MiB");
            count += length;
        }
    }

    private static final class ViewInput extends InputStream {
        private final int length;
        private final IntUnaryOperator byteAt;
        private int offset;

        ViewInput(int length, IntUnaryOperator byteAt) {
            this.length = length;
            this.byteAt = byteAt;
        }

        @Override
        public int read() {
            return offset == length ? -1 : byteAt.applyAsInt(offset++);
        }

        @Override
        public int available() {
            return length - offset;
        }
    }
}
