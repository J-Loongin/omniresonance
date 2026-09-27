// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.filter.ResourceRuleIntent;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class TerminalRuleInputTest {
    @Test
    void prefixesCarryTypeWithoutGuessingChemicalNamesOrNamespaces() {
        var fluid = TerminalRuleInput.explicit("fluid:water");
        assertEquals(ResourceTypes.FLUID, fluid.typeId());
        assertEquals(ResourceFilterRule.Selector.exact(ResourceLocation.parse("minecraft:water")), fluid.selector());
        assertEquals(
                TerminalRuleInput.explicit("chemical:mekanism:hydrogen"),
                TerminalRuleInput.explicit("gas:mekanism:hydrogen"));
        assertEquals(
                ResourceFilterRule.Selector.tag(ResourceLocation.parse("minecraft:water")),
                TerminalRuleInput.explicit("fluid:#minecraft:water").selector());
        assertEquals(
                ResourceFilterRule.Selector.wholeType(),
                TerminalRuleInput.explicit("energy").selector());
        assertNull(TerminalRuleInput.explicit("minecraft:iron_ingot"));
        assertNull(TerminalRuleInput.explicit("#c:water"));
        assertThrows(IllegalArgumentException.class, () -> TerminalRuleInput.explicit("fluid:"));
        assertThrows(IllegalArgumentException.class, () -> TerminalRuleInput.explicit("gas:#"));
    }

    @Test
    void draftKeepsTypedTextWhileSynchronizingResourceKinds() {
        var draft = new TerminalResourceRuleDraft(null);
        draft.editText("fluid:");
        assertThrows(IllegalArgumentException.class, draft::intent);
        draft.editText("fluid:water");
        assertEquals("fluid:water", draft.text);
        assertEquals(ResourceTypes.FLUID, draft.type);
        assertEquals(TerminalRuleInput.explicit(draft.text), draft.intent());
        draft.editText("energy");
        assertEquals(ResourceTypes.ENERGY, draft.type);
        assertEquals(3, draft.selector);
        draft.editText("item:minecraft:iron_ingot");
        assertEquals(ResourceTypes.ITEM, draft.type);
        assertEquals(0, draft.selector);
        draft.changeType(ResourceTypes.FLUID);
        assertEquals("minecraft:iron_ingot", draft.text);
        assertEquals(ResourceTypes.FLUID, ((ResourceRuleIntent.Match) draft.intent()).typeId());
    }

    @Test
    void legacyStoredNamespaceAndSingleCopyFormatRemainIntact() {
        var id = ResourceLocation.parse("fluid:water");
        var old = new ResourceFilterRule.Match(
                new java.util.UUID(1, 2),
                ResourceTypes.ITEM,
                ResourceFilterRule.Selector.exact(id),
                io.github.loongin.omniresonance.filter.ComponentCondition.idOnly());
        var draft = new TerminalResourceRuleDraft(old);
        assertEquals(old.selector(), ((ResourceRuleIntent.Match) draft.intent()).selector());
        assertEquals(ResourceTypes.ITEM, ((ResourceRuleIntent.Match) draft.intent()).typeId());
        TerminalTagClipboard.copy(
                "minecraft:fluid", java.util.List.of("c:water"), text -> assertEquals("#water", text));
        assertTrue(draft.pasteTag(TerminalTagClipboard.recent(), "#water"));
        assertEquals(ResourceTypes.FLUID, draft.type);
        assertEquals("#c:water", draft.text);
        assertEquals(TerminalRuleInput.explicit("fluid:#c:water"), draft.intent());
        assertEquals(
                TerminalRuleInput.explicit("gas:mekanism:hydrogen"),
                TerminalTagPaste.read(null, "gas:mekanism:hydrogen"));
        TerminalTagClipboard.clear();
    }
}
