// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Fixed-size discriminated item-policy wire format; decoding validates values without accessing authority. */
public final class ItemPolicyMenuCodec {
    private ItemPolicyMenuCodec() {}

    public static ItemTransferPolicy read(FriendlyByteBuf buffer) {
        TransferDirection direction = NodeMenuCodecSupport.readTransferDirection(buffer);
        int interval = buffer.readInt();
        int rate = buffer.readInt();
        RedstoneCondition redstone =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> RedstoneCondition.IGNORE;
                    case 1 -> RedstoneCondition.SIGNAL;
                    case 2 -> RedstoneCondition.NO_SIGNAL;
                    default -> throw new DecoderException("Invalid redstone condition");
                };
        UUID preset = buffer.readBoolean() ? buffer.readUUID() : null;
        FilterMode mode =
                switch (buffer.readUnsignedByte()) {
                    case 0 -> FilterMode.WHITELIST;
                    case 1 -> FilterMode.BLACKLIST;
                    default -> throw new DecoderException("Invalid filter mode");
                };
        return direction == TransferDirection.INPUT
                ? new ItemTransferPolicy.Input(interval, rate, redstone, preset, mode, buffer.readLong())
                : new ItemTransferPolicy.Output(interval, rate, redstone, preset, mode, buffer.readInt());
    }

    public static void write(FriendlyByteBuf buffer, ItemTransferPolicy policy) {
        NodeMenuCodecSupport.writeTransferDirection(buffer, policy.direction());
        buffer.writeInt(policy.intervalTicks()).writeInt(policy.rate());
        buffer.writeByte(
                switch (policy.redstoneCondition()) {
                    case IGNORE -> 0;
                    case SIGNAL -> 1;
                    case NO_SIGNAL -> 2;
                });
        buffer.writeBoolean(policy.filterPresetId() != null);
        if (policy.filterPresetId() != null) buffer.writeUUID(policy.filterPresetId());
        buffer.writeByte(policy.filterMode() == FilterMode.WHITELIST ? 0 : 1);
        switch (policy) {
            case ItemTransferPolicy.Input input -> buffer.writeLong(input.keepCount());
            case ItemTransferPolicy.Output output -> buffer.writeInt(output.priority());
        }
    }
}
