// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PipeConnectionsTest {
    @Test
    void everyTypeAndFaceIsIndependent() {
        for (var selected : PipeConnections.Type.values())
            for (var side : Direction.values()) {
                var one = PipeConnections.only(selected, side);
                var scoped = PipeConnections.forScope(
                        ResourceScope.customSet(Set.of(selected.resourceType())), 1 << side.get3DDataValue());
                assertEquals(one, scoped);
                assertEquals(one.hashCode(), scoped.hashCode());
                for (var type : PipeConnections.Type.values())
                    for (var face : Direction.values())
                        assertEquals(type == selected && face == side, one.allows(type, face));
                assertFalse(one.allows(selected, null));
            }
    }

    @Test
    void unionIsImmutableAndDoesNotMixResourcesAcrossFaces() {
        var a = PipeConnections.only(PipeConnections.Type.ITEM, Direction.NORTH);
        var b = PipeConnections.only(PipeConnections.Type.CHEMICAL, Direction.SOUTH);
        var merged = a.union(b);
        assertEquals(merged, b.union(a));
        assertEquals(merged, merged.union(a).union(PipeConnections.NONE));
        assertTrue(merged.allows(PipeConnections.Type.ITEM, Direction.NORTH));
        assertTrue(merged.allows(PipeConnections.Type.CHEMICAL, Direction.SOUTH));
        assertFalse(merged.allows(PipeConnections.Type.ITEM, Direction.SOUTH));
        assertFalse(a.allows(PipeConnections.Type.CHEMICAL, Direction.SOUTH));
    }

    @Test
    void allAndUnavailableScopesPreserveConnectionBoundaries() {
        var all = PipeConnections.forScope(ResourceScope.all(), 63);
        for (var type : PipeConnections.Type.values())
            for (var side : Direction.values()) assertTrue(all.allows(type, side));
        assertTrue(PipeConnections.forScope(ResourceScope.all(), 0).isEmpty());
        assertTrue(PipeConnections.forScope(
                        ResourceScope.customSet(Set.of(ResourceLocation.parse("missing:resource"))), 63)
                .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PipeConnections.forScope(ResourceScope.all(), -1));
        assertThrows(IllegalArgumentException.class, () -> PipeConnections.forScope(ResourceScope.all(), 64));
    }
}
