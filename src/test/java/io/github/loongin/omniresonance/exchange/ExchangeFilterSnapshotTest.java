// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.FilterResourceSample;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeFilterSnapshotTest {
    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private static ResourceFilterPreset preset(int n, ResourceFilterRule... rules) {
        return new ResourceFilterPreset(id(n), new ManagedName("Preset " + n), 0, List.of(rules));
    }

    private static ResourceFilterRule ref(int rule, int target) {
        return new ResourceFilterRule.Reference(id(rule), id(target));
    }

    private static ResourceFilterRule match(int rule) {
        return new ResourceFilterRule.Match(
                id(rule), ResourceTypes.ITEM, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly());
    }

    @Test
    void capturesOnlyReachableRulesAndDetachesLibrary() {
        Map<UUID, ResourceFilterPreset> library = new HashMap<>();
        ResourceFilterPreset child = preset(2, match(12));
        library.put(id(1), preset(1, ref(10, 2), ref(11, 2)));
        library.put(id(2), child);
        library.put(id(3), preset(3, match(13)));
        ExchangeFilterSnapshot snapshot = ExchangeFilterSnapshot.capture(id(1), library, 2, 3);
        library.put(id(2), preset(2));
        library.clear();
        assertEquals(2, snapshot.presets().size());
        assertEquals(child, snapshot.presets().get(id(2)));
        assertEquals(id(1), snapshot.root());
        assertEquals(3, snapshot.ruleCount());
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.presets().clear());
    }

    @Test
    void rejectsMissingCyclesMismatchesAndAggregateOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ExchangeFilterSnapshot.capture(id(1), Map.of(), 2, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExchangeFilterSnapshot.capture(id(1), Map.of(id(1), preset(2)), 2, 3));
        Map<UUID, ResourceFilterPreset> cycle = Map.of(id(1), preset(1, ref(10, 2)), id(2), preset(2, ref(11, 1)));
        assertThrows(IllegalArgumentException.class, () -> ExchangeFilterSnapshot.capture(id(1), cycle, 2, 3));
        Map<UUID, ResourceFilterPreset> tree = Map.of(id(1), preset(1, ref(10, 2)), id(2), preset(2, match(11)));
        assertThrows(IllegalArgumentException.class, () -> ExchangeFilterSnapshot.capture(id(1), tree, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> ExchangeFilterSnapshot.capture(id(1), tree, 2, 1));
        assertEquals(2, ExchangeFilterSnapshot.capture(id(1), tree, 2, 2).ruleCount());
    }

    @Test
    void retainedRulesCompileAfterOriginalLibraryIsDeleted() {
        ResourceFilterRule energy = new ResourceFilterRule.Match(
                id(30), ResourceTypes.ENERGY, ResourceFilterRule.Selector.wholeType(), ComponentCondition.idOnly());
        Map<UUID, ResourceFilterPreset> library = new HashMap<>();
        library.put(id(1), preset(1, ref(31, 2)));
        library.put(id(2), preset(2, energy));
        ExchangeFilterSnapshot snapshot = ExchangeFilterSnapshot.capture(id(1), library, 2, 2);
        library.clear();
        ResourceFilterCompiler.Compiled compiled = ResourceFilterCompiler.compile(
                snapshot.root(),
                new ResourceFilterCompiler.OwnerSnapshot(id(90), snapshot.presets()),
                (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0));
        assertTrue(compiled.valid());
        assertTrue(compiled.allows(FilterResourceSample.capture(EnergyVariant.INSTANCE), FilterMode.WHITELIST));
        assertFalse(compiled.allows(FilterResourceSample.capture(EnergyVariant.INSTANCE), FilterMode.BLACKLIST));
        ExchangeFilterSnapshot empty = ExchangeFilterSnapshot.capture(id(3), Map.of(id(3), preset(3)), 1, 0);
        assertEquals(0, empty.ruleCount());
        assertThrows(
                IllegalArgumentException.class, () -> ExchangeFilterSnapshot.capture(id(3), empty.presets(), 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> ExchangeFilterSnapshot.capture(id(3), empty.presets(), 1, -1));
    }

    @Test
    void checksDepthEvenWhenSharedSubgraphWasAlreadyVisited() {
        Map<UUID, ResourceFilterPreset> library = new HashMap<>();
        for (int n = 1; n <= 9; n++) library.put(id(n), preset(n, ref(100 + n, n + 1)));
        library.put(id(10), preset(10));
        assertEquals(
                9,
                ExchangeFilterSnapshot.capture(id(2), library, 10, 10).presets().size());
        library.put(id(1), preset(1, ref(200, 9), ref(201, 2)));
        assertThrows(IllegalArgumentException.class, () -> ExchangeFilterSnapshot.capture(id(1), library, 10, 10));
    }
}
