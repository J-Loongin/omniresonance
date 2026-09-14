// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable internal preset; copies bounded rules without authority access or mutation. */
public record ResourceFilterPreset(UUID id, ManagedName name, long revision, List<ResourceFilterRule> rules) {
    public static final int MAX_ENTRIES = 262144;

    public ResourceFilterPreset {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(rules);
        if (revision < 0 || rules.size() > MAX_ENTRIES) throw new IllegalArgumentException("Invalid preset bounds");
        Set<UUID> ids = new HashSet<>();
        for (ResourceFilterRule rule : rules)
            if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate rule ID");
        rules = List.copyOf(rules);
    }
}
