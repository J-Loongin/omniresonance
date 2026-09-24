// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DomainInventoryQueryTest {
    private static final DomainInventoryQuery.Document IRON = new DomainInventoryQuery.Document(
            "iron ingot",
            "minecraft vanilla",
            "minecraft:iron_ingot",
            "minecraft:item",
            List.of("c:ingots/iron"),
            () -> "a useful metal");

    @Test
    void fullAndRecipeViewerTagQueriesBothFindMatchingStoredResources() {
        var wood = new DomainInventoryQuery.Document(
                "stripped birch wood",
                "minecraft",
                "minecraft:stripped_birch_wood",
                "minecraft:item",
                List.of("c:stripped_woods"),
                () -> "");
        assertTrue(DomainInventoryQuery.parse("#c:stripped_woods").matches(wood));
        assertTrue(DomainInventoryQuery.parse("#stripped_woods").matches(wood));
        assertFalse(DomainInventoryQuery.parse("#c:stripped_woods").matches(IRON));
    }

    @Test
    void fieldsAndBooleanGroupsUseQuotedPhrasesWithoutRegex() {
        assertTrue(DomainInventoryQuery.parse("\"iron ingot\" @mine #ingots type:item !*gold")
                .matches(IRON));
        assertTrue(DomainInventoryQuery.parse("water | $metal *iron").matches(IRON));
        assertFalse(DomainInventoryQuery.parse("type:fluid | !#ingots").matches(IRON));
        assertTrue(DomainInventoryQuery.parse("").matches(IRON));
        assertThrows(IllegalArgumentException.class, () -> DomainInventoryQuery.parse("iron |"));
        assertThrows(IllegalArgumentException.class, () -> DomainInventoryQuery.parse("\"iron"));
        assertThrows(IllegalArgumentException.class, () -> DomainInventoryQuery.parse("type:"));
    }

    @Test
    void tooltipIsOnlyGeneratedForQueriesThatActuallyReachADollarTerm() {
        var doc = new DomainInventoryQuery.Document(
                "iron", "minecraft", "minecraft:iron", "minecraft:item", List.of(), () -> {
                    throw new AssertionError("Unexpected tooltip");
                });
        assertTrue(DomainInventoryQuery.parse("iron").matches(doc));
        assertFalse(DomainInventoryQuery.parse("water $metal").matches(doc));
    }

    @Test
    void quotedOperatorsRemainLiteralNames() {
        var doc = new DomainInventoryQuery.Document(
                "| !gold @mod", "else", "else:item", "minecraft:item", List.of(), () -> "");
        assertTrue(DomainInventoryQuery.parse("\"|\" \"!gold\" \"@mod\"").matches(doc));
        assertThrows(IllegalArgumentException.class, () -> DomainInventoryQuery.parse("\"\""));
    }

    @Test
    void tooltipSearchIncludesShownFluidNamesUnitsTagsAndCurrentExactQuantities() {
        long[] amount = {1234};
        var fluid = new DomainInventoryQuery.Document(
                "water",
                "minecraft",
                "minecraft:water",
                "minecraft:fluid",
                List.of("c:water"),
                () -> "",
                "mB",
                () -> amount[0]);
        assertTrue(DomainInventoryQuery.parse("$water $mb $1234 $#c:water").matches(fluid));
        amount[0] = 999;
        assertFalse(DomainInventoryQuery.parse("$1234").matches(fluid));
    }
}
