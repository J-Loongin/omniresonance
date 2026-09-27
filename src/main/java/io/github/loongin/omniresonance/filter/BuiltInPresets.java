// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Legacy immutable versioned rules retained for previously saved references. Pure lookup and projection, with no world access, ownership, mutation or
 * persistence. Availability is supplied by the authorized server catalog; UUIDs alone never grant editing rights. */
public final class BuiltInPresets {
    public record Entry(String key, ResourceLocation type, ResourceFilterPreset preset) {
        public Component label() {
            return Component.translatable("omniresonance.builtin_preset." + key);
        }

        public FilterPresetSummary summary() {
            return new FilterPresetSummary(preset.id(), preset.name().value(), 0, 1, false);
        }
    }

    public static final List<Entry> ENTRIES = List.of(
            entry("energy", ResourceTypes.ENERGY, "All Energy (FE)"),
            entry("source", ResourceTypes.SOURCE, "All Source"),
            entry("soul", ResourceTypes.SOUL, "All Warden Souls"));

    private BuiltInPresets() {}

    private static Entry entry(String name, ResourceLocation type, String internalName) {
        var id = UUID.nameUUIDFromBytes(
                ("omniresonance:builtin_preset:" + name + ":v1").getBytes(StandardCharsets.UTF_8));
        var rule =
                UUID.nameUUIDFromBytes(("omniresonance:builtin_rule:" + name + ":v1").getBytes(StandardCharsets.UTF_8));
        return new Entry(
                name,
                type,
                new ResourceFilterPreset(
                        id,
                        new ManagedName(internalName),
                        0,
                        List.of(new ResourceFilterRule.Match(
                                rule, type, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly()))));
    }

    public static @Nullable Entry find(@Nullable UUID id) {
        for (var entry : ENTRIES) if (entry.preset.id().equals(id)) return entry;
        return null;
    }

    public static boolean available(@Nullable UUID id, Predicate<ResourceLocation> registered) {
        var entry = find(id);
        return entry != null && registered.test(entry.type());
    }

    public static Component label(@Nullable UUID id, String fallback) {
        var entry = find(id);
        return entry == null ? Component.literal(fallback) : entry.label();
    }
}
