// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Detached management graph; caller-thread work proportional to library rules, never cached or persisted. */
public final class ResourceFilterGraph {
    private ResourceFilterGraph() {}

    /** Returns the changed preset and all transitive parents, including parents of a currently missing preset. */
    public static Set<UUID> ancestors(Collection<ResourceFilterPreset> presets, UUID changed) {
        Map<UUID, Set<UUID>> reverse = new HashMap<>();
        for (ResourceFilterPreset preset : presets)
            for (ResourceFilterRule rule : preset.rules())
                if (rule instanceof ResourceFilterRule.Reference reference)
                    reverse.computeIfAbsent(reference.presetId(), ignored -> new HashSet<>())
                            .add(preset.id());
        Set<UUID> result = new HashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        pending.add(changed);
        while (!pending.isEmpty()) {
            UUID id = pending.removeFirst();
            if (result.add(id)) pending.addAll(reverse.getOrDefault(id, Set.of()));
        }
        return Set.copyOf(result);
    }

    /** Validates prospective affected paths before authority changes; missing saved references remain unresolved. */
    public static void validate(Collection<ResourceFilterPreset> presets, ResourceFilterPreset replacement) {
        Map<UUID, ResourceFilterPreset> prospective = new HashMap<>();
        for (ResourceFilterPreset preset : presets) prospective.put(preset.id(), preset);
        prospective.put(replacement.id(), replacement);
        Map<UUID, Integer> depths = new HashMap<>();
        for (UUID root : ancestors(prospective.values(), replacement.id()))
            depth(root, prospective, new HashSet<>(), depths, 0);
    }

    private static int depth(
            UUID id,
            Map<UUID, ResourceFilterPreset> presets,
            Set<UUID> visiting,
            Map<UUID, Integer> memo,
            int pathDepth) {
        if (pathDepth > 8 || !visiting.add(id))
            throw new IllegalArgumentException("Cyclic or over-depth preset reference");
        Integer cached = memo.get(id);
        if (cached != null) {
            visiting.remove(id);
            if (pathDepth + cached > 8) throw new IllegalArgumentException("Over-depth preset reference");
            return cached;
        }
        int result = 0;
        ResourceFilterPreset preset = presets.get(id);
        if (preset != null)
            for (ResourceFilterRule rule : preset.rules())
                if (rule instanceof ResourceFilterRule.Reference reference)
                    result = Math.max(result, 1 + depth(reference.presetId(), presets, visiting, memo, pathDepth + 1));
        visiting.remove(id);
        memo.put(id, result);
        return result;
    }
}
