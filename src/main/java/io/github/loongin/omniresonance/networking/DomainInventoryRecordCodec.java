// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;

/** Pure bounded record framing; keys stay opaque and no adapter, world, or registry is consulted. */
public final class DomainInventoryRecordCodec {
    public static final int MAXIMUM_BYTES = 32 + 128 + 262144;

    private DomainInventoryRecordCodec() {}

    /** Returns owned bytes for exactly one immutable absolute resource record, rejecting invalid quantities/IDs. */
    public static byte[] encode(DomainLedger.Change record) {
        if (record.sequence() <= 0 || record.amount() < 0 || record.revision() < 0)
            throw new IllegalArgumentException("Invalid inventory record");
        byte[] type = record.key().typeId().toString().getBytes(StandardCharsets.UTF_8);
        byte[] key = record.key().canonicalBytes();
        var buffer = ByteBuffer.allocate(32 + type.length + key.length);
        buffer.putLong(record.sequence()).putLong(record.amount()).putLong(record.revision());
        buffer.putInt(type.length).put(type).putInt(key.length).put(key);
        return buffer.array();
    }

    /** Validates all lengths before allocation, rejects trailing/truncated data, and detaches the resource key. */
    public static DomainLedger.Change decode(byte[] bytes) {
        if (bytes.length < 33 || bytes.length > MAXIMUM_BYTES)
            throw new IllegalArgumentException("Invalid record length");
        try {
            var buffer = ByteBuffer.wrap(bytes);
            long id = buffer.getLong(), amount = buffer.getLong(), revision = buffer.getLong();
            int typeLength = buffer.getInt();
            if (id <= 0
                    || amount < 0
                    || revision < 0
                    || typeLength < 1
                    || typeLength > 128
                    || typeLength > buffer.remaining() - 4)
                throw new IllegalArgumentException("Invalid inventory identity");
            byte[] typeBytes = new byte[typeLength];
            buffer.get(typeBytes);
            String name = new String(typeBytes, StandardCharsets.UTF_8);
            var type = ResourceLocation.parse(name);
            if (!java.util.Arrays.equals(typeBytes, type.toString().getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Noncanonical resource type");
            int length = buffer.getInt();
            if (length < 0 || length > 262144 || length != buffer.remaining())
                throw new IllegalArgumentException("Invalid resource key length");
            byte[] key = new byte[length];
            buffer.get(key);
            return new DomainLedger.Change(id, new ResourceVariantKey(type, key), amount, revision);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid inventory record", invalid);
        }
    }
}
