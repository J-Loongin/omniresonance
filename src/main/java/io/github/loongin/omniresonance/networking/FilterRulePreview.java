// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Bounded presentation metadata, never a filter or inventory authority. No component payloads or world access. */
public record FilterRulePreview(Kind kind, @Nullable ResourceLocation type, String selector) {
    public enum Kind {
        NONE,
        REFERENCE,
        EXACT,
        TAG,
        GLOB,
        WHOLE_TYPE
    }

    public static final FilterRulePreview NONE = new FilterRulePreview(Kind.NONE, null, "");
    public static final FilterRulePreview REFERENCE = new FilterRulePreview(Kind.REFERENCE, null, "");
    public static final int MAX_SELECTOR = 256;

    public FilterRulePreview {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(selector);
        boolean typed = kind != Kind.NONE && kind != Kind.REFERENCE;
        if (typed != (type != null)
                || selector.length() > MAX_SELECTOR
                || type != null && type.toString().length() > 128)
            throw new IllegalArgumentException("Invalid rule preview");
        if (kind == Kind.EXACT || kind == Kind.TAG) {
            var id = ResourceLocation.tryParse(selector);
            if (id == null || !id.toString().equals(selector)) throw new IllegalArgumentException("Invalid preview ID");
        } else if (kind == Kind.GLOB) {
            new io.github.loongin.omniresonance.filter.ResourceIdGlob(selector);
        } else if (!selector.isEmpty()) throw new IllegalArgumentException("Unexpected preview selector");
    }

    public static FilterRulePreview from(ResourceFilterRule rule) {
        if (rule instanceof ResourceFilterRule.Reference) return REFERENCE;
        var match = (ResourceFilterRule.Match) rule;
        Kind kind;
        String text;
        switch (match.selector()) {
            case ResourceFilterRule.Exact exact -> {
                kind = Kind.EXACT;
                text = exact.resourceId().toString();
            }
            case ResourceFilterRule.TagSelector tag -> {
                kind = Kind.TAG;
                text = tag.tagId().toString();
            }
            case ResourceFilterRule.Glob glob -> {
                kind = Kind.GLOB;
                text = glob.glob().pattern();
            }
            case ResourceFilterRule.WholeType ignored -> {
                kind = Kind.WHOLE_TYPE;
                text = "";
            }
        }
        // Legal long selectors remain fully readable/editable; preview never truncates an identity into another
        // resource.
        return text.length() > MAX_SELECTOR ? NONE : new FilterRulePreview(kind, match.resourceTypeId(), text);
    }

    public static FilterRulePreview exactItem(String id) {
        return id.length() <= MAX_SELECTOR ? new FilterRulePreview(Kind.EXACT, ResourceTypes.ITEM, id) : NONE;
    }

    public int maximumEncodedBytes() {
        return 1
                + (type == null ? 0 : 3 + type.toString().getBytes(StandardCharsets.UTF_8).length)
                + 3
                + selector.getBytes(StandardCharsets.UTF_8).length;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeEnum(kind);
        if (type != null) buffer.writeUtf(type.toString(), 128);
        buffer.writeUtf(selector, MAX_SELECTOR);
    }

    public static FilterRulePreview read(FriendlyByteBuf buffer) {
        int value = buffer.readVarInt();
        if (value < 0 || value >= Kind.values().length)
            throw new io.netty.handler.codec.DecoderException("Invalid preview kind");
        Kind kind = Kind.values()[value];
        var type = kind == Kind.NONE || kind == Kind.REFERENCE ? null : ResourceLocation.parse(buffer.readUtf(128));
        return new FilterRulePreview(kind, type, buffer.readUtf(MAX_SELECTOR));
    }
}
