// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class Ae2ChunkIndexTest {
    @Test
    void unloadTouchesOnlyMatchingDimensionAndChunkAmongTenThousandHosts() {
        var index = new Ae2ChunkIndex<Object>();
        var hosts = new ArrayList<Object>();
        for (int i = 0; i < 10000; i++) {
            Object host = new Object();
            hosts.add(host);
            index.add(host, Level.OVERWORLD, i);
        }
        Object nether = new Object();
        index.add(nether, Level.NETHER, 12);
        assertTrue(index.removeChunk(Level.OVERWORLD, -1).isEmpty());
        assertEquals(10001, index.size());
        assertEquals(Set.of(hosts.get(12)), index.removeChunk(Level.OVERWORLD, 12));
        assertEquals(10000, index.size());
        assertEquals(Set.of(nether), index.removeChunk(Level.NETHER, 12));
        index.remove(hosts.get(13));
        assertTrue(index.removeChunk(Level.OVERWORLD, 13).isEmpty());
        index.add(hosts.get(12), Level.END, 99);
        assertEquals(Set.of(hosts.get(12)), index.removeChunk(Level.END, 99));
        index.clear();
        assertEquals(0, index.size());
        assertTrue(index.removeChunk(Level.OVERWORLD, 1).isEmpty());
    }
}
