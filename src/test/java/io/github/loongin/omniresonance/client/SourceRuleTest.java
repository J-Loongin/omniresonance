// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceRuleTest {
    @Test
    void soulRulesKeepASeparateWholeTypeIdentityAndSourceUnits() {
        var rule = TerminalRuleInput.explicit("soul");
        assertEquals(ResourceTypes.SOUL, rule.typeId());
        assertInstanceOf(ResourceFilterRule.WholeType.class, rule.selector());
        var draft = new TerminalResourceRuleDraft(null);
        draft.changeType(ResourceTypes.SOUL);
        assertEquals(3, draft.selector);
        assertEquals("", draft.text);
        var document = new DomainInventoryQuery.Document(
                "souls",
                "industrialforegoingsouls",
                "industrialforegoingsouls:soul",
                "industrialforegoingsouls:soul",
                java.util.List.of(),
                () -> "",
                "Soul",
                () -> 1000);
        assertTrue(DomainInventoryQuery.parse("type:soul $1000").matches(document));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceFilterRule.Match(
                        UUID.randomUUID(),
                        ResourceTypes.SOUL,
                        ResourceFilterRule.Selector.tag(ResourceTypes.SOUL),
                        ComponentCondition.idOnly()));
    }

    @Test
    void sourceInventorySearchUsesItsOwnUnitAndType() {
        var document = new DomainInventoryQuery.Document(
                "魔源",
                "ars_nouveau ars nouveau",
                "ars_nouveau:source",
                "ars_nouveau:source",
                java.util.List.of(),
                () -> "",
                "Source",
                () -> 2000);
        assertTrue(DomainInventoryQuery.parse("type:source").matches(document));
        assertTrue(DomainInventoryQuery.parse("@ars").matches(document));
        assertTrue(DomainInventoryQuery.parse("$2000").matches(document));
        org.junit.jupiter.api.Assertions.assertFalse(
                DomainInventoryQuery.parse("type:energy").matches(document));
    }

    @Test
    void typedSourceAndTypeSwitchUseOnlyWholeType() {
        var draft = new TerminalResourceRuleDraft(null);
        draft.changeType(ResourceTypes.SOURCE);
        assertEquals(3, draft.selector);
        assertEquals("", draft.text);
        draft.changeType(ResourceTypes.ITEM);
        assertEquals(0, draft.selector);
        var intent = TerminalRuleInput.explicit("source");
        assertEquals(ResourceTypes.SOURCE, intent.typeId());
        assertInstanceOf(ResourceFilterRule.WholeType.class, intent.selector());
        assertTrue(TerminalRuleInput.explicitSyntax("source"));
        assertEquals("", TerminalRuleInput.body("source"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceFilterRule.Match(
                        UUID.randomUUID(),
                        ResourceTypes.SOURCE,
                        ResourceFilterRule.Selector.tag(ResourceTypes.SOURCE),
                        ComponentCondition.idOnly()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceFilterRule.Match(
                        UUID.randomUUID(),
                        ResourceTypes.SOURCE,
                        ResourceFilterRule.Selector.exact(ResourceTypes.SOURCE),
                        ComponentCondition.idOnly()));
    }
}
