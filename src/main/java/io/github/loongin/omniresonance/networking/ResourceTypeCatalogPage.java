// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Detached immutable page of a frozen server-lifecycle adapter catalog. The enclosing menu owns session and
 * permission checks; catalogId is a transient identity, never persistence or authority. At most 128 descriptors
 * are retained, with at most 262144 entries in the whole catalog. No factories, worlds or simulation are accessed.
 */
public record ResourceTypeCatalogPage(
        UUID catalogId, int offset, int totalCount, List<ResourceAdapterDirectory.Descriptor> entries) {
    public static final int MAXIMUM_ENTRIES = 128;
    public static final int MAXIMUM_ENVELOPE_BYTES = 262144;

    public ResourceTypeCatalogPage {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(entries, "entries");
        if (offset < 0
                || totalCount < 0
                || totalCount > ResourceScope.MAXIMUM_RESOURCE_TYPE_IDS
                || offset > totalCount
                || entries.size() > MAXIMUM_ENTRIES
                || entries.size() > totalCount - offset
                || entries.isEmpty() && totalCount != 0)
            throw new IllegalArgumentException("Invalid catalog page bounds");
        var seen = new HashSet<ResourceLocation>();
        for (var descriptor : entries) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (!seen.add(descriptor.typeId())) throw new IllegalArgumentException("Duplicate resource descriptor");
        }
        entries = List.copyOf(entries);
    }

    /** Pure scalar continuation check; does not allocate or request another page. */
    public boolean hasNext() {
        return offset + entries.size() < totalCount;
    }

    /** Counts this page's exact encoding, including UTF-8 strings and length prefixes, without a frame allocation. */
    public int encodedSize() {
        int size = 24 + varIntBytes(entries.size());
        for (var descriptor : entries) size += descriptorSize(descriptor);
        return size;
    }

    /**
     * Pure metadata read using the caller-frozen directory. Visits only the admitted page and one boundary entry,
     * without copying the whole catalog. The caller supplies its exact surrounding envelope bytes; a first entry
     * that cannot fit rejects instead of returning a stalled empty page. Invalid input fails without mutation.
     */
    public static ResourceTypeCatalogPage from(
            ResourceAdapterDirectory directory,
            UUID catalogId,
            int offset,
            int maximumEnvelopeBytes,
            int envelopeBytes) {
        List<ResourceLocation> types =
                Objects.requireNonNull(directory, "directory").types();
        if (maximumEnvelopeBytes < 1
                || maximumEnvelopeBytes > MAXIMUM_ENVELOPE_BYTES
                || envelopeBytes < 0
                || envelopeBytes > maximumEnvelopeBytes - 25
                || offset < 0
                || offset > types.size()
                || offset == types.size() && offset != 0)
            throw new IllegalArgumentException("Invalid catalog request bounds");
        int available = maximumEnvelopeBytes - envelopeBytes;
        int descriptorBytes = 0;
        var entries = new ArrayList<ResourceAdapterDirectory.Descriptor>();
        for (int index = offset; index < types.size() && entries.size() < MAXIMUM_ENTRIES; index++) {
            var descriptor = directory.find(types.get(index)).orElseThrow();
            int next = Math.addExact(descriptorBytes, descriptorSize(descriptor));
            if (24 + varIntBytes(entries.size() + 1) + next > available) break;
            entries.add(descriptor);
            descriptorBytes = next;
        }
        return new ResourceTypeCatalogPage(catalogId, offset, types.size(), entries);
    }

    /** Bounded synchronous decode on the buffer owner's thread; counts are checked before collection allocation. */
    public static ResourceTypeCatalogPage read(FriendlyByteBuf buffer) {
        try {
            UUID catalog = buffer.readUUID();
            int offset = buffer.readInt(), total = buffer.readInt(), count = buffer.readVarInt();
            if (count < 0 || count > MAXIMUM_ENTRIES || count > buffer.readableBytes() / 13)
                throw new DecoderException("Invalid catalog descriptor count");
            var entries = new ArrayList<ResourceAdapterDirectory.Descriptor>(count);
            for (int index = 0; index < count; index++) {
                String text = buffer.readUtf(128);
                ResourceLocation id = ResourceLocation.tryParse(text);
                if (id == null || !id.toString().equals(text)) throw new DecoderException("Noncanonical resource type");
                entries.add(new ResourceAdapterDirectory.Descriptor(id, buffer.readUtf(32), buffer.readLong()));
            }
            return new ResourceTypeCatalogPage(catalog, offset, total, entries);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new DecoderException("Invalid catalog page", invalid);
        }
    }

    /** Appends this bounded page to the caller's buffer. The enclosing payload still validates its full envelope. */
    public static void write(FriendlyByteBuf buffer, ResourceTypeCatalogPage page) {
        buffer.writeUUID(page.catalogId())
                .writeInt(page.offset())
                .writeInt(page.totalCount())
                .writeVarInt(page.entries().size());
        for (var descriptor : page.entries())
            buffer.writeUtf(descriptor.typeId().toString(), 128)
                    .writeUtf(descriptor.unit(), 32)
                    .writeLong(descriptor.defaultBatchSize());
    }

    private static int descriptorSize(ResourceAdapterDirectory.Descriptor descriptor) {
        int id = descriptor.typeId().toString().getBytes(StandardCharsets.UTF_8).length;
        int unit = descriptor.unit().getBytes(StandardCharsets.UTF_8).length;
        return varIntBytes(id) + id + varIntBytes(unit) + unit + 8;
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value >>>= 7) != 0) bytes++;
        return bytes;
    }
}
