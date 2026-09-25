// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable reachable preset closure for exchange terms. Contains no owner library, world or live tag membership.
 * Capture is pure caller-thread preparation, not a hot-path operation; callers own authority and revision checks.
 * Invalid or oversized candidates fail before publication without modifying the input library.
 */
public final class ExchangeFilterSnapshot {
    private final UUID root;
    private final Map<UUID, ResourceFilterPreset> presets;
    private final int ruleCount;

    private ExchangeFilterSnapshot(UUID root, Builder builder) {
        this.root = root;
        this.presets = Map.copyOf(builder.captured);
        this.ruleCount = builder.ruleCount;
    }

    /**
     * Captures only reachable immutable values, retaining reference identities and component conditions.
     * The library must remain stable during this caller-thread operation. Limits are caller-provided aggregate
     * admission budgets, bounded by the existing filter representation limit; zero rules permits empty presets.
     * No simulation, persistence or authority mutation occurs. Missing references, cycles, excessive depth,
     * mismatched IDs or exceeded budgets throw IllegalArgumentException; no partial snapshot is returned.
     */
    public static ExchangeFilterSnapshot capture(
            UUID root, Map<UUID, ResourceFilterPreset> library, int maximumPresets, int maximumRules) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(library);
        if (maximumPresets < 1
                || maximumPresets > ResourceFilterPreset.MAX_ENTRIES
                || maximumRules < 0
                || maximumRules > ResourceFilterPreset.MAX_ENTRIES)
            throw new IllegalArgumentException("Invalid snapshot limits");
        Builder builder = new Builder(library, maximumPresets, maximumRules);
        builder.visit(root, 0);
        return new ExchangeFilterSnapshot(root, builder);
    }

    public UUID root() {
        return root;
    }

    public Map<UUID, ResourceFilterPreset> presets() {
        return presets;
    }

    public int ruleCount() {
        return ruleCount;
    }

    private static final class Builder {
        private final Map<UUID, ResourceFilterPreset> library;
        private final int maximumPresets;
        private final int maximumRules;
        private final Map<UUID, ResourceFilterPreset> captured = new HashMap<>();
        private final Map<UUID, Integer> heights = new HashMap<>();
        private final Set<UUID> visiting = new HashSet<>();
        private int ruleCount;

        private Builder(Map<UUID, ResourceFilterPreset> library, int maximumPresets, int maximumRules) {
            this.library = library;
            this.maximumPresets = maximumPresets;
            this.maximumRules = maximumRules;
        }

        private int visit(UUID id, int depth) {
            if (depth > 8 || visiting.contains(id))
                throw new IllegalArgumentException("Cyclic or over-depth exchange filter");
            Integer cached = heights.get(id);
            if (cached != null) {
                if (depth + cached > 8) throw new IllegalArgumentException("Over-depth exchange filter");
                return cached;
            }
            ResourceFilterPreset preset = library.get(id);
            if (preset == null || !id.equals(preset.id()))
                throw new IllegalArgumentException("Missing or mismatched exchange preset");
            if (captured.size() == maximumPresets || preset.rules().size() > maximumRules - ruleCount)
                throw new IllegalArgumentException("Exchange filter snapshot exceeds budget");
            captured.put(id, preset);
            ruleCount += preset.rules().size();
            visiting.add(id);
            int height = 0;
            for (ResourceFilterRule rule : preset.rules())
                if (rule instanceof ResourceFilterRule.Reference reference)
                    height = Math.max(height, 1 + visit(reference.presetId(), depth + 1));
            visiting.remove(id);
            heights.put(id, height);
            return height;
        }
    }
}
