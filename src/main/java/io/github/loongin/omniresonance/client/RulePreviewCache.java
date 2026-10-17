// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.FilterRulePreview;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Client-view-owned LRU by bounded rule metadata. At most 128 sequences of 24 icons; no global inventory cache.
 * Only current visible rows prepare/animate. Page exit, tag sync and resource reload clear all registry references. */
final class RulePreviewCache {
    static final int CAPACITY = 128, TICK_BUDGET = 16384;
    private final RulePreviewSource source;
    private final LinkedHashMap<FilterRulePreview, RuleIconPreview> entries = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashSet<RuleIconPreview> visible = new LinkedHashSet<>();

    RulePreviewCache(RulePreviewSource source) {
        this.source = source;
    }

    void beginRows() {
        visible.clear();
    }

    RuleIconPreview get(FilterRulePreview metadata) {
        var preview = entries.get(metadata);
        if (preview == null) {
            if (entries.size() == CAPACITY)
                visible.remove(entries.remove(entries.keySet().iterator().next()));
            preview = new RuleIconPreview(metadata, source);
            entries.put(metadata, preview);
        }
        visible.add(preview);
        return preview;
    }

    void tick() {
        int budget = TICK_BUDGET;
        // The visible set is bounded by the fixed-height management page, independent of total rule count.
        int share = Math.max(1, TICK_BUDGET / Math.max(1, visible.size()));
        for (var preview : visible) {
            preview.tickAnimation();
            budget -= preview.prepare(Math.min(share, budget));
        }
    }

    void clear() {
        entries.clear();
        visible.clear();
    }

    int size() {
        return entries.size();
    }
}
