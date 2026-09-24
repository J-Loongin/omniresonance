// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.filter.ResourceRuleIntent;
import io.github.loongin.omniresonance.transfer.CanonicalResourceNbt;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Client-only typed draft; readonly server component values never become a C2S value payload. */
final class TerminalResourceRuleDraft {
    ResourceLocation type = ResourceTypes.ITEM;
    int selector;
    String text = "minecraft:stone";
    ComponentCondition.Mode mode = ComponentCondition.Mode.ID_ONLY;
    final Set<ResourceLocation> selected = new HashSet<>();
    @Nullable
    UUID reference, sampleToken;
    ComponentCondition source = ComponentCondition.idOnly();
    boolean dirty;
    private @Nullable ComponentCondition cachedSource;
    private CompoundTag cachedComponents = new CompoundTag();
    private java.util.List<String> cachedKeys = java.util.List.of();

    TerminalResourceRuleDraft(@Nullable ResourceFilterRule rule) {
        if (rule instanceof ResourceFilterRule.Reference ref) {
            selector = 4;
            reference = ref.presetId();
            text = "";
        } else if (rule instanceof ResourceFilterRule.Match match) {
            type = match.resourceTypeId();
            source = match.components();
            mode = source.persistenceSnapshot().mode();
            for (var key : source.persistenceSnapshot().selected()) selected.add(key.key());
            if (match.selector() instanceof ResourceFilterRule.Exact exact)
                text = exact.resourceId().toString();
            else if (match.selector() instanceof ResourceFilterRule.TagSelector tag) {
                selector = 1;
                text = "#" + tag.tagId();
            } else if (match.selector() instanceof ResourceFilterRule.Glob glob) {
                selector = 2;
                text = glob.glob().pattern();
            } else {
                selector = 3;
                text = "";
            }
        }
    }

    boolean pasteTag(@Nullable TerminalTagClipboard.Candidate candidate, String value) {
        var match = TerminalTagPaste.read(candidate, value);
        if (match == null) return false;
        type = match.typeId();
        selector = 1;
        text = "#" + ((ResourceFilterRule.TagSelector) match.selector()).tagId();
        mode = ComponentCondition.Mode.ID_ONLY;
        source = ComponentCondition.idOnly();
        sampleToken = null;
        selected.clear();
        dirty = true;
        return true;
    }

    void editText(String value) {
        text = value;
        if (selector < 3 && value.startsWith("#")) selector = 1;
        dirty = true;
    }

    void select(int value) {
        selector = value;
        if (selector == 1 && !text.startsWith("#")) text = "#" + text;
        else if (selector != 1 && text.startsWith("#")) text = text.substring(1);
    }

    ResourceRuleIntent intent() {
        if (selector == 4) return new ResourceRuleIntent.Reference(java.util.Objects.requireNonNull(reference));
        ResourceFilterRule.Selector choice =
                switch (selector) {
                    case 0 -> ResourceFilterRule.Selector.exact(canonical(text));
                    case 1 ->
                        ResourceFilterRule.Selector.tag(canonical(text.startsWith("#") ? text.substring(1) : text));
                    case 2 -> ResourceFilterRule.Selector.glob(text);
                    case 3 -> ResourceFilterRule.Selector.wholeType();
                    default -> throw new IllegalArgumentException("Invalid selector");
                };
        return new ResourceRuleIntent.Match(
                type, choice, mode, mode == ComponentCondition.Mode.SELECTED ? selected : Set.of(), sampleToken);
    }

    CompoundTag available() {
        if (cachedSource == source) return cachedComponents;
        var snapshot = source.persistenceSnapshot();
        CompoundTag result = snapshot.mode() == ComponentCondition.Mode.FULL
                ? (CompoundTag) CanonicalResourceNbt.decode(snapshot.fullBytes())
                : new CompoundTag();
        if (snapshot.mode() == ComponentCondition.Mode.SELECTED)
            for (var entry : snapshot.selected())
                result.put(entry.key().toString(), CanonicalResourceNbt.decode(entry.canonicalBytes()));
        cachedSource = source;
        cachedComponents = result;
        cachedKeys = result.getAllKeys().stream().sorted().toList();
        return result;
    }

    java.util.List<String> keys() {
        available();
        return cachedKeys;
    }

    void sample(ResourceFilterRule.Match match, UUID token) {
        type = match.resourceTypeId();
        source = match.components();
        sampleToken = token;
        if (selector == 0 && match.selector() instanceof ResourceFilterRule.Exact exact)
            text = exact.resourceId().toString();
        selected.retainAll(
                available().getAllKeys().stream().map(ResourceLocation::parse).toList());
        dirty = true;
    }

    private static ResourceLocation canonical(String text) {
        ResourceLocation result = ResourceLocation.parse(text);
        if (!result.toString().equals(text)) throw new IllegalArgumentException("Noncanonical selector");
        return result;
    }
}
