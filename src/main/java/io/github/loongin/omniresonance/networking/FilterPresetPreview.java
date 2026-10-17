// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/** Immutable, bounded direct-rule examples only. Pure caller-thread construction never resolves references,
 * tags or inventories. This display metadata grants no authority and owns no component payloads. */
public record FilterPresetPreview(List<FilterRulePreview> rules, boolean truncated) {
    public static final int MAXIMUM_RULES = 24, MAXIMUM_BYTES = 1536;
    public static final FilterPresetPreview EMPTY = new FilterPresetPreview(List.of(), false);

    public FilterPresetPreview {
        if (rules.size() > MAXIMUM_RULES) throw new IllegalArgumentException("Too many preview rules");
        rules = List.copyOf(rules);
        int bytes = 4;
        for (var rule : rules) bytes += rule.maximumEncodedBytes();
        if (bytes > MAXIMUM_BYTES) throw new IllegalArgumentException("Preset preview too large");
    }

    /** Reads at most 24 direct rules from a trusted snapshot, preserving unavailable placeholders and truncation. */
    public static FilterPresetPreview from(List<ResourceFilterRule> rules) {
        return from(rules, MAXIMUM_BYTES);
    }

    /** Applies an additional display-only byte allowance for whole-library management transfers. */
    public static FilterPresetPreview from(List<ResourceFilterRule> rules, int byteLimit) {
        int limit = Math.clamp(byteLimit, 4, MAXIMUM_BYTES);
        int count = Math.min(
                Math.min(MAXIMUM_RULES, rules.size()), (limit - 4) / FilterRulePreview.NONE.maximumEncodedBytes());
        var result = new ArrayList<FilterRulePreview>(count);
        int bytes = 4;
        boolean truncated = rules.size() > count;
        for (int i = 0; i < count; i++) {
            var preview = FilterRulePreview.from(rules.get(i));
            int reserved = (count - i - 1) * FilterRulePreview.NONE.maximumEncodedBytes();
            if (bytes + preview.maximumEncodedBytes() + reserved > limit) {
                preview = FilterRulePreview.NONE;
                truncated = true;
            }
            result.add(preview);
            bytes += preview.maximumEncodedBytes();
        }
        return new FilterPresetPreview(result, truncated);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(truncated).writeVarInt(rules.size());
        for (var rule : rules) rule.write(buffer);
    }

    public static FilterPresetPreview read(FriendlyByteBuf buffer) {
        int start = buffer.readerIndex();
        boolean truncated = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAXIMUM_RULES)
            throw new io.netty.handler.codec.DecoderException("Invalid preview count");
        var rules = new ArrayList<FilterRulePreview>(count);
        for (int i = 0; i < count; i++) {
            rules.add(FilterRulePreview.read(buffer));
            if (buffer.readerIndex() - start > MAXIMUM_BYTES)
                throw new io.netty.handler.codec.DecoderException("Preset preview too large");
        }
        return new FilterPresetPreview(rules, truncated);
    }
}
