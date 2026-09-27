// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.filter.ResourceRuleIntent;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Pure typed clipboard interpretation. Unrelated text and stale world candidates never guess a resource type. */
final class TerminalTagPaste {
    private TerminalTagPaste() {}

    static @Nullable ResourceRuleIntent.Match read(@Nullable TerminalTagClipboard.Candidate candidate, String text) {
        var copied = copiedTag(candidate, text);
        if (copied != null) return copied;
        try {
            return TerminalRuleInput.explicit(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static @Nullable ResourceRuleIntent.Match copiedTag(
            @Nullable TerminalTagClipboard.Candidate candidate, String text) {
        if (candidate == null
                || candidate.tags().size() != 1
                || !candidate.text().equals(text)
                || !text.equals(TerminalTagClipboard.searchText(candidate.tags().getFirst()))) return null;
        try {
            var type = ResourceLocation.tryParse(candidate.resourceType());
            var tag = ResourceLocation.tryParse(candidate.tags().getFirst());
            if (type == null
                    || tag == null
                    || !type.toString().equals(candidate.resourceType())
                    || !tag.toString().equals(candidate.tags().getFirst())) return null;
            return new ResourceRuleIntent.Match(
                    type, ResourceFilterRule.Selector.tag(tag), ComponentCondition.Mode.ID_ONLY, Set.of(), null);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
