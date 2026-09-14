// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.transfer.ItemTransferPolicy;
import io.github.loongin.omniresonance.transfer.RedstoneCondition;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Strict direction-discriminated codec. Default item rate is omitted, reserving sparse override intent. */
final class ItemPolicyNbt {
    private ItemPolicyNbt() {}

    static ItemTransferPolicy decode(CompoundTag binding, TransferDirection direction) {
        ManagedDataNbt.requireType(binding, "item_policy", Tag.TAG_COMPOUND);
        CompoundTag tag = binding.getCompound("item_policy");
        Set<String> fields = new HashSet<>(Set.of(
                "interval_ticks",
                "redstone_condition",
                "filter_mode",
                direction == TransferDirection.INPUT ? "keep_count" : "priority"));
        if (tag.contains("rate")) fields.add("rate");
        if (tag.contains("filter_preset_id")) fields.add("filter_preset_id");
        if (!tag.getAllKeys().equals(fields)) throw new IllegalArgumentException("Mixed or missing item policy fields");
        ManagedDataNbt.requireType(tag, "interval_ticks", Tag.TAG_INT);
        int interval = tag.getInt("interval_ticks");
        int rate = Integer.MAX_VALUE;
        if (tag.contains("rate")) {
            ManagedDataNbt.requireType(tag, "rate", Tag.TAG_INT);
            rate = tag.getInt("rate");
        }
        ManagedDataNbt.requireType(tag, "redstone_condition", Tag.TAG_STRING);
        ManagedDataNbt.requireType(tag, "filter_mode", Tag.TAG_STRING);
        RedstoneCondition redstone =
                switch (tag.getString("redstone_condition")) {
                    case "ignore" -> RedstoneCondition.IGNORE;
                    case "signal" -> RedstoneCondition.SIGNAL;
                    case "no_signal" -> RedstoneCondition.NO_SIGNAL;
                    default -> throw new IllegalArgumentException("Invalid redstone condition");
                };
        FilterMode mode =
                switch (tag.getString("filter_mode")) {
                    case "whitelist" -> FilterMode.WHITELIST;
                    case "blacklist" -> FilterMode.BLACKLIST;
                    default -> throw new IllegalArgumentException("Invalid filter mode");
                };
        UUID preset = tag.contains("filter_preset_id") ? ManagedDataNbt.readUuid(tag, "filter_preset_id") : null;
        if (direction == TransferDirection.INPUT) {
            ManagedDataNbt.requireType(tag, "keep_count", Tag.TAG_LONG);
            return new ItemTransferPolicy.Input(interval, rate, redstone, preset, mode, tag.getLong("keep_count"));
        }
        ManagedDataNbt.requireType(tag, "priority", Tag.TAG_INT);
        return new ItemTransferPolicy.Output(interval, rate, redstone, preset, mode, tag.getInt("priority"));
    }

    static CompoundTag encode(ItemTransferPolicy policy) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("interval_ticks", policy.intervalTicks());
        if (policy.rate() != Integer.MAX_VALUE) tag.putInt("rate", policy.rate());
        tag.putString("redstone_condition", policy.redstoneCondition().name().toLowerCase(Locale.ROOT));
        tag.putString("filter_mode", policy.filterMode().name().toLowerCase(Locale.ROOT));
        if (policy.filterPresetId() != null) tag.putUUID("filter_preset_id", policy.filterPresetId());
        if (policy instanceof ItemTransferPolicy.Input input) tag.putLong("keep_count", input.keepCount());
        else tag.putInt("priority", ((ItemTransferPolicy.Output) policy).priority());
        return tag;
    }
}
