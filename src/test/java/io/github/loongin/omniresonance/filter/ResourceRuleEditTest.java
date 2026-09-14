// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceRuleEditTest {
    @Test
    void idOnlyCannotInventFullOrSelectedComponents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ComponentCondition.idOnly().selectTrusted(ComponentCondition.Mode.FULL, Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ComponentCondition.idOnly().selectTrusted(ComponentCondition.Mode.SELECTED, Set.of()));
        assertEquals(
                ComponentCondition.Mode.ID_ONLY,
                ComponentCondition.idOnly()
                        .selectTrusted(ComponentCondition.Mode.ID_ONLY, Set.of())
                        .persistenceSnapshot()
                        .mode());
    }

    @Test
    void prospectiveGraphAcceptsDiamondRejectsNinthEdgeAndDetectsAncestors() {
        var name = new io.github.loongin.omniresonance.network.ManagedName("Preset");
        java.util.List<ResourceFilterPreset> chain = new java.util.ArrayList<>();
        for (int i = 0; i <= 8; i++)
            chain.add(new ResourceFilterPreset(
                    new java.util.UUID(1, i),
                    name,
                    0,
                    i == 8
                            ? java.util.List.of()
                            : java.util.List.of(new ResourceFilterRule.Reference(
                                    new java.util.UUID(2, i), new java.util.UUID(1, i + 1)))));
        ResourceFilterGraph.validate(chain, chain.getLast());
        assertEquals(
                9, ResourceFilterGraph.ancestors(chain, chain.getLast().id()).size());
        var ninth = new ResourceFilterPreset(
                chain.getLast().id(),
                name,
                1,
                java.util.List.of(
                        new ResourceFilterRule.Reference(new java.util.UUID(2, 9), new java.util.UUID(1, 9))));
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterGraph.validate(chain, ninth));
        var cycle = new ResourceFilterPreset(
                chain.getLast().id(),
                name,
                1,
                java.util.List.of(new ResourceFilterRule.Reference(
                        new java.util.UUID(2, 9), chain.getFirst().id())));
        assertThrows(IllegalArgumentException.class, () -> ResourceFilterGraph.validate(chain, cycle));
        var root = new ResourceFilterPreset(
                new java.util.UUID(4, 1),
                name,
                0,
                java.util.List.of(
                        new ResourceFilterRule.Reference(
                                new java.util.UUID(5, 1), chain.get(6).id()),
                        new ResourceFilterRule.Reference(
                                new java.util.UUID(5, 2), chain.get(7).id())));
        chain.add(root);
        ResourceFilterGraph.validate(chain, root);
    }
}
