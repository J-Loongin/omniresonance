// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ResourceFilterCompilerTest {
    private static final UUID ROOT = new UUID(0, 1);

    private static ResourceFilterPreset preset(UUID id, ResourceFilterRule... rules) {
        return new ResourceFilterPreset(id, new ManagedName("Preset"), 3, List.of(rules));
    }

    private static ResourceFilterRule.Reference ref(long rule, UUID target) {
        return new ResourceFilterRule.Reference(new UUID(1, rule), target);
    }

    private static ResourceFilterRule.Match energy() {
        return new ResourceFilterRule.Match(
                new UUID(1, 0),
                ResourceTypes.ENERGY,
                ResourceFilterRule.Selector.wholeType(),
                ComponentCondition.idOnly());
    }

    private static ResourceFilterCompiler.Compiled compile(UUID root, Map<UUID, ResourceFilterPreset> presets) {
        return ResourceFilterCompiler.compile(
                root,
                new ResourceFilterCompiler.OwnerSnapshot(new UUID(9, 9), presets),
                (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0));
    }

    @Test
    void wildcardNeverMatchesInvalidUppercaseRegistrationIds() {
        assertFalse(new ResourceIdGlob("*").matches("minecraft:STONE"));
    }

    @Test
    void selectionEmptyAndEnergySemantics() {
        var candidate = FilterResourceSample.capture(EnergyVariant.INSTANCE);
        assertTrue(compile(null, Map.of()).allows(candidate, FilterMode.WHITELIST));
        assertFalse(compile(ROOT, Map.of()).allows(candidate, FilterMode.BLACKLIST));
        var empty = compile(ROOT, Map.of(ROOT, preset(ROOT)));
        assertFalse(empty.allows(candidate, FilterMode.WHITELIST));
        assertTrue(empty.allows(candidate, FilterMode.BLACKLIST));
        var matched = compile(ROOT, Map.of(ROOT, preset(ROOT, energy())));
        assertTrue(matched.allows(candidate, FilterMode.WHITELIST));
        assertFalse(matched.allows(candidate, FilterMode.BLACKLIST));
    }

    @Test
    void missingBranchBlocksEvenMatchingOrAndRecompilationRecovers() {
        UUID dep = new UUID(0, 2);
        var root = preset(ROOT, energy(), ref(1, dep));
        assertFalse(compile(ROOT, Map.of(ROOT, root)).valid());
        assertTrue(compile(ROOT, Map.of(ROOT, root, dep, preset(dep))).valid());
        assertFalse(compile(ROOT, Map.of(ROOT, preset(ROOT, ref(1, ROOT)))).valid());
    }

    @Test
    void depthCountsEdgesAndSharedDependenciesUseLongestPath() {
        for (int depth : new int[] {8, 9}) {
            Map<UUID, ResourceFilterPreset> graph = new HashMap<>();
            for (int i = 1; i <= depth + 1; i++) {
                UUID id = new UUID(0, i);
                graph.put(id, i == depth + 1 ? preset(id, energy()) : preset(id, ref(i, new UUID(0, i + 1))));
            }
            assertEquals(depth == 8, compile(ROOT, graph).valid());
            graph.put(ROOT, preset(ROOT, ref(90, new UUID(0, depth + 1)), ref(91, new UUID(0, 2))));
            assertEquals(depth == 8, compile(ROOT, graph).valid());
            graph.put(ROOT, preset(ROOT, ref(91, new UUID(0, 2)), ref(90, new UUID(0, depth + 1))));
            assertEquals(depth == 8, compile(ROOT, graph).valid());
        }
    }

    @Test
    void diamondCompilesOnceAndPreparationResumes() {
        UUID left = new UUID(0, 2), right = new UUID(0, 3), leaf = new UUID(0, 4);
        var graph = Map.of(
                ROOT,
                preset(ROOT, ref(1, left), ref(2, right)),
                left,
                preset(left, ref(3, leaf)),
                right,
                preset(right, ref(4, leaf)),
                leaf,
                preset(leaf, energy()));
        var preparation = ResourceFilterCompiler.prepare(
                ROOT,
                new ResourceFilterCompiler.OwnerSnapshot(ROOT, graph),
                (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0));
        int steps = 0;
        while (!preparation.done()) {
            assertTrue(preparation.step(1) <= 1);
            assertTrue(++steps < 30);
        }
        assertEquals(4, preparation.result().dependencies().size());
        assertEquals(1, preparation.result().ruleCount());
        assertThrows(
                UnsupportedOperationException.class,
                () -> preparation.result().dependencies().clear());
    }

    @Test
    void manyRulesResumeAndMultiNodeCyclesFailClosed() {
        List<ResourceFilterRule> rules = new java.util.ArrayList<>();
        for (int i = 0; i < 10000; i++)
            rules.add(new ResourceFilterRule.Match(
                    new UUID(1, i),
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.wholeType(),
                    ComponentCondition.idOnly()));
        var root = new ResourceFilterPreset(ROOT, new ManagedName("Many"), 0, rules);
        var preparation = ResourceFilterCompiler.prepare(
                ROOT,
                new ResourceFilterCompiler.OwnerSnapshot(ROOT, Map.of(ROOT, root)),
                (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0));
        long work = 0;
        while (!preparation.done()) {
            int used = preparation.step(7);
            assertTrue(used <= 7);
            work += used;
        }
        assertEquals(10003, work);
        var e = preparation
                .result()
                .evaluate(FilterResourceSample.capture(EnergyVariant.INSTANCE), FilterMode.BLACKLIST);
        work = 0;
        while (!e.done()) {
            int used = e.step(11);
            assertTrue(used <= 11);
            work += used;
        }
        assertTrue(e.allowed());
        assertTrue(work <= 1);
        UUID child = new UUID(0, 2);
        assertFalse(compile(ROOT, Map.of(ROOT, preset(ROOT, ref(1, child)), child, preset(child, ref(2, ROOT))))
                .valid());
    }

    @Test
    void layeredDiamondsKeepOnlyReachableDistinctRules() {
        Map<UUID, ResourceFilterPreset> graph = new HashMap<>();
        for (int level = 0; level < 8; level++)
            for (int branch = 0; branch < 2; branch++) {
                UUID id = new UUID(level, branch);
                graph.put(id, preset(id, ref(1, new UUID(level + 1, 0)), ref(2, new UUID(level + 1, 1))));
            }
        for (int branch = 0; branch < 2; branch++) {
            UUID id = new UUID(8, branch);
            graph.put(id, preset(id, energy()));
        }
        var result = compile(new UUID(0, 0), graph);
        assertTrue(result.valid());
        assertEquals(17, result.dependencies().size());
        assertEquals(2, result.ruleCount());
        assertFalse(result.dependencies().containsKey(new UUID(0, 1)));
    }

    @Test
    void globIsAnchoredNormalizesStarsAndResumesAtCharacterBudget() {
        assertTrue(new ResourceIdGlob("mine***craft:st?ne").matches("minecraft:stone"));
        assertFalse(new ResourceIdGlob("stone").matches("minecraft:stone"));
        assertEquals("a*b", new ResourceIdGlob("a***b").pattern());
        String pattern = "*a".repeat(120) + "b";
        String input = "a".repeat(30000);
        var evaluation = new ResourceIdGlob(pattern).evaluate(input);
        long work = 0;
        while (!evaluation.done()) {
            int used = evaluation.step(17);
            assertTrue(used <= 17);
            work += used;
        }
        assertFalse(evaluation.matches());
        assertTrue(work <= (long) (pattern.length() + 1) * (input.length() + 1));
        assertThrows(IllegalArgumentException.class, () -> new ResourceIdGlob("A:*"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceIdGlob("a".repeat(257)));
    }

    @Test
    void fastDecisionsAgreeWithEvaluationWithoutCandidatePreparation() {
        var sample = FilterResourceSample.capture(EnergyVariant.INSTANCE);
        for (var compiled :
                List.of(compile(null, Map.of()), compile(ROOT, Map.of()), compile(ROOT, Map.of(ROOT, preset(ROOT))))) {
            for (var mode : FilterMode.values()) {
                assertEquals(compiled.allows(sample, mode), compiled.fastDecision(ResourceTypes.ENERGY, mode));
                var evaluation = compiled.evaluate(sample, mode);
                assertTrue(evaluation.step(1) <= 1);
                assertTrue(evaluation.done());
            }
        }
        assertEquals(
                null,
                compile(ROOT, Map.of(ROOT, preset(ROOT, energy())))
                        .fastDecision(ResourceTypes.ENERGY, FilterMode.WHITELIST));
    }
}
