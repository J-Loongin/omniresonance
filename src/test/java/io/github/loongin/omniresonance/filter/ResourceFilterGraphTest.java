// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceFilterGraphTest {
    @Test
    void aPageBuildsOneReverseGraphAndQueriesDoNotRescanTheLibrary() {
        var presets = new ArrayList<ResourceFilterPreset>();
        UUID leaf = new UUID(0, 1);
        for (int i = 0; i < 512; i++)
            presets.add(new ResourceFilterPreset(
                    new UUID(1, i),
                    new ManagedName("Preset " + i),
                    0,
                    List.of(new ResourceFilterRule.Reference(new UUID(2, i), leaf))));
        int[] reads = {0};
        var counted = new AbstractCollection<ResourceFilterPreset>() {
            public int size() {
                return presets.size();
            }

            public Iterator<ResourceFilterPreset> iterator() {
                var source = presets.iterator();
                return new Iterator<>() {
                    public boolean hasNext() {
                        return source.hasNext();
                    }

                    public ResourceFilterPreset next() {
                        reads[0]++;
                        return source.next();
                    }
                };
            }
        };
        var graph = ResourceFilterGraph.reverseIndex(counted);
        assertEquals(512, reads[0]);
        for (int i = 0; i < 128; i++) assertEquals(Set.of(new UUID(1, i)), graph.ancestors(new UUID(1, i)));
        assertEquals(513, graph.ancestors(leaf).size());
        assertEquals(512, reads[0]);
    }
}
