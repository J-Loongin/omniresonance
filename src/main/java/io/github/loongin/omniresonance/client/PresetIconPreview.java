// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.FilterPresetPreview;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Samples each bounded direct rule once before filling additional examples in round-robin order.
 * Preparation can pause independently, but a ready rule cannot enter the next example round early.
 * Identities deduplicate by resource type and ID, not texture or localized name. References never resolve. */
final class PresetIconPreview extends ResourceIconPreview {
    private static final class Rule {
        final RuleIconPreview preview;
        int consumed, completedRound = -1;
        boolean first, exhausted;

        Rule(RuleIconPreview preview) {
            this.preview = preview;
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final HashSet<RuleIconPreview.Identity> identities = new HashSet<>();
    private final HashSet<RulePreviewImage> placeholders = new HashSet<>();
    private int cursor, round, roundRemaining, live;
    private boolean done;

    PresetIconPreview(FilterPresetPreview metadata, RulePreviewSource source) {
        super(true);
        more = metadata.truncated();
        var seen = new HashSet<io.github.loongin.omniresonance.networking.FilterRulePreview>();
        for (var rule : metadata.rules()) if (seen.add(rule)) rules.add(new Rule(new RuleIconPreview(rule, source)));
        roundRemaining = live = rules.size();
        if (rules.isEmpty()) {
            finish();
            if (metadata.truncated()) fallback = UNAVAILABLE;
        }
    }

    int prepare(int budget) {
        int used = 0;
        while (!done && used < budget) {
            if (roundRemaining == 0) {
                round++;
                roundRemaining = live;
            }
            Rule rule = rules.get(cursor);
            cursor = (cursor + 1) % rules.size();
            if (rule.exhausted || rule.completedRound == round) {
                used++;
                continue;
            }
            var child = rule.preview;
            if (rule.consumed < child.imageCount()) {
                used++;
                var image = child.image(rule.consumed);
                boolean unique = identities.add(child.identity(rule.consumed++));
                completeRound(rule);
                if (unique) add(image);
            } else if (child.done()) {
                used++;
                if (!rule.first && placeholders.add(child.current())) add(child.current());
                completeRound(rule);
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

    private void completeRound(Rule rule) {
        rule.first = true;
        rule.completedRound = round;
        roundRemaining--;
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
