// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ResourceScopeTest {
    @Test
    void customScopePreservesUnknownValidIdsAndDetachesItsSet() {
        ResourceLocation unknown = ResourceLocation.parse("example:optional_resource");
        java.util.HashSet<ResourceLocation> source = new java.util.HashSet<>(Set.of(ResourceTypes.ITEM, unknown));

        ResourceScope scope = ResourceScope.customSet(source);
        source.clear();

        assertEquals(Set.of(ResourceTypes.ITEM, unknown), scope.resourceTypeIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> scope.resourceTypeIds().clear());
        assertTrue(ResourceScope.all().includes(ResourceLocation.parse("future:resource")));
    }

    @Test
    void customScopeRejectsEmptyOversizedIdsAndOversizedCollections() {
        assertThrows(IllegalArgumentException.class, () -> ResourceScope.customSet(Set.of()));
        ResourceLocation boundary = ResourceLocation.parse("a:" + "b".repeat(126));
        assertEquals(Set.of(boundary), ResourceScope.customSet(Set.of(boundary)).resourceTypeIds());
        ResourceLocation oversized = ResourceLocation.parse("a:" + "b".repeat(127));
        assertThrows(IllegalArgumentException.class, () -> ResourceScope.customSet(Set.of(oversized)));

        AbstractCollection<ResourceLocation> oversizedCollection = new AbstractCollection<>() {
            @Override
            public Iterator<ResourceLocation> iterator() {
                return List.of(ResourceTypes.ITEM).iterator();
            }

            @Override
            public int size() {
                return 262145;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> ResourceScope.customSet(oversizedCollection));
    }
}
