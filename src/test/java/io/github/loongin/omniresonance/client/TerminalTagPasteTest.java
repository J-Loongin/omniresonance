// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class TerminalTagPasteTest {
    @Test
    void shortSearchCopyRestoresFullIdentityInsideAnExistingDraftWithoutGuessing() {
        var draft = new TerminalResourceRuleDraft(null);
        var candidate = new TerminalTagClipboard.Candidate("minecraft:fluid", List.of("c:water"), "#water");
        org.junit.jupiter.api.Assertions.assertTrue(draft.pasteTag(candidate, "#water"));
        assertEquals("#c:water", draft.text);
        assertEquals(ResourceLocation.parse("minecraft:fluid"), draft.type);
        assertEquals(ComponentCondition.Mode.ID_ONLY, draft.mode);
        org.junit.jupiter.api.Assertions.assertFalse(draft.pasteTag(null, "#water"));
        org.junit.jupiter.api.Assertions.assertFalse(draft.pasteTag(candidate, "#lava"));
        assertEquals("#c:water", draft.text);
    }

    @Test
    void prefixedInputCreatesATagRuleAndLegacyTagIdsStillLoad() {
        var draft = new TerminalResourceRuleDraft(null);
        draft.editText("#c:ingots");
        assertEquals(1, draft.selector);
        assertEquals(
                ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:ingots")),
                ((io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match) draft.intent()).selector());
        draft.selector = 1;
        draft.text = "c:ingots";
        assertEquals(
                ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:ingots")),
                ((io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match) draft.intent()).selector());
    }

    @Test
    void usesOnlyTheMatchingTypedCopyAndCreatesAnIdOnlyTagRule() {
        var candidate = new TerminalTagClipboard.Candidate("minecraft:fluid", List.of("c:water"), "#water");
        var rule = TerminalTagPaste.read(candidate, "#water");
        assertEquals(ResourceLocation.parse("minecraft:fluid"), rule.typeId());
        assertEquals(ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:water")), rule.selector());
        assertEquals(ComponentCondition.Mode.ID_ONLY, rule.mode());
        assertNull(rule.sampleToken());
        assertNull(TerminalTagPaste.read(candidate, "c:lava"));
        assertNull(TerminalTagPaste.read(null, "c:water"));
        assertNull(TerminalTagPaste.read(
                new TerminalTagClipboard.Candidate("minecraft:item", List.of("INVALID"), "INVALID"), "INVALID"));
    }
}
