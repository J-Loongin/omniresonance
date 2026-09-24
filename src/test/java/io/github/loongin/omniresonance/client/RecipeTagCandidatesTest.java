// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeTagCandidatesTest {
    @Test
    void inconsistentDuplicatesAndOversizedCandidateSetsNeverGuessTags() {
        var a = new RecipeTagCandidates.Candidate(
                "minecraft:item", "minecraft:iron_ingot", List.of("c:ingots", "c:iron"));
        var b = new RecipeTagCandidates.Candidate("minecraft:item", "minecraft:iron_ingot", List.of("c:ingots"));
        assertEquals(
                List.of("c:ingots"),
                RecipeTagCandidates.common(List.of(a, b)).orElseThrow().tags());
        var values = new java.util.ArrayList<RecipeTagCandidates.Candidate>();
        for (int i = 0; i < 257; i++)
            values.add(new RecipeTagCandidates.Candidate("minecraft:item", "test:item_" + i, List.of("c:ingots")));
        assertTrue(RecipeTagCandidates.common(values).isEmpty());
    }

    @Test
    void duplicateSourcesAndAlternativesUseOnlyTheSharedTags() {
        var a = new RecipeTagCandidates.Candidate(
                "minecraft:item", "minecraft:iron_ingot", List.of("c:ingots", "c:ingots/iron"));
        var b = new RecipeTagCandidates.Candidate(
                "minecraft:item", "minecraft:gold_ingot", List.of("c:ingots", "c:ingots/gold"));
        var result = RecipeTagCandidates.common(List.of(a, a, b));
        assertEquals(List.of("c:ingots"), result.orElseThrow().tags());
        assertTrue(RecipeTagCandidates.common(
                        List.of(a, new RecipeTagCandidates.Candidate("minecraft:item", "minecraft:stone", List.of())))
                .isEmpty());
        assertTrue(RecipeTagCandidates.common(List.of(
                        a,
                        new RecipeTagCandidates.Candidate("minecraft:fluid", "minecraft:water", List.of("c:ingots"))))
                .isEmpty());
    }
}
