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
    void copiedBareTagInExactModeGetsAnExplicitModeErrorWithoutChangingTheDraft() {
        var copied = new TerminalTagClipboard.Candidate("minecraft:item", List.of("c:ingots"), "c:ingots");
        var draft = new TerminalResourceRuleDraft(null);
        draft.text = "c:ingots";
        assertEquals("tag_in_exact_id", draft.selectorError(copied));
        assertEquals(0, draft.selector);
        draft.selector = 1;
        assertNull(draft.selectorError(copied));
        draft.text = "#c:ingots";
        assertEquals("tag_prefix", draft.selectorError(copied));
        draft.selector = 0;
        assertEquals("tag_in_exact_id", draft.selectorError(copied));
        draft.text = "minecraft:iron_ingot";
        assertNull(draft.selectorError(copied));
    }

    @Test
    void usesOnlyTheMatchingTypedCopyAndCreatesAnIdOnlyTagRule() {
        var candidate = new TerminalTagClipboard.Candidate("minecraft:fluid", List.of("c:water"), "c:water");
        var rule = TerminalTagPaste.read(candidate, "c:water");
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
