// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.FilterPresetPreview;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Samples each bounded direct rule once before filling additional examples in round-robin order.
 * Identities deduplicate by resource type and ID, not texture or localized name. References never resolve. */
final class PresetIconPreview extends ResourceIconPreview {
    private static final class Rule {
        final RuleIconPreview preview;
        int consumed;
        boolean first, exhausted;

        Rule(RuleIconPreview preview) {
            this.preview = preview;
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final HashSet<RuleIconPreview.Identity> identities = new HashSet<>();
    private final HashSet<RulePreviewImage> placeholders = new HashSet<>();
    private int cursor, firstRemaining, live;
    private boolean done;

    PresetIconPreview(FilterPresetPreview metadata, RulePreviewSource source) {
        super(true);
        more = metadata.truncated();
        var seen = new HashSet<io.github.loongin.omniresonance.networking.FilterRulePreview>();
        for (var rule : metadata.rules()) if (seen.add(rule)) rules.add(new Rule(new RuleIconPreview(rule, source)));
        firstRemaining = live = rules.size();
        if (rules.isEmpty()) {
            finish();
            if (metadata.truncated()) fallback = UNAVAILABLE;
        }
    }

    int prepare(int budget) {
        int used = 0;
        while (!done && used < budget) {
            Rule rule = rules.get(cursor);
            cursor = (cursor + 1) % rules.size();
            if (rule.exhausted || firstRemaining > 0 && rule.first) {
                used++;
                continue;
            }
            var child = rule.preview;
            if (rule.consumed < child.imageCount()) {
                used++;
                var image = child.image(rule.consumed);
                boolean unique = identities.add(child.identity(rule.consumed++));
                first(rule);
                if (unique) add(image);
            } else if (child.done()) {
                used++;
                if (!rule.first && placeholders.add(child.current())) add(child.current());
                first(rule);
                rule.exhausted = true;
                live--;
                more |= child.hasMore();
                if (live == 0 || more && images.size() == MAXIMUM_IMAGES) finish();
            } else {
                used += Math.max(1, child.prepareNext(Math.min(64, budget - used)));
            }
        }
        return used;
    }

    private void first(Rule rule) {
        if (!rule.first) {
            rule.first = true;
            firstRemaining--;
        }
    }

    private void add(RulePreviewImage image) {
        if (images.size() == MAXIMUM_IMAGES) {
            more = true;
            finish();
        } else {
            images.add(image);
            if (more && images.size() == MAXIMUM_IMAGES) finish();
        }
    }

    private void finish() {
        done = true;
        fallback = EMPTY;
        rules.clear();
        identities.clear();
        placeholders.clear();
    }
}
