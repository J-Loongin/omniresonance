// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Runtime-owned reverse index, bounded by loaded hosts. Add/remove/clear follow the host lifecycle;
 * no world queries, simulation, global cache or retained entries after unload. Identity membership is deliberate. */
final class Ae2ChunkIndex<T> {
    private record Chunk(ResourceKey<Level> dimension, long position) {}

    private final Map<T, Chunk> positions = new IdentityHashMap<>();
    private final Map<Chunk, Set<T>> chunks = new HashMap<>();

    void add(T host, ResourceKey<Level> dimension, long position) {
        var chunk = new Chunk(dimension, position);
        if (positions.containsKey(host)) throw new IllegalStateException("Host already indexed");
        positions.put(host, chunk);
        chunks.computeIfAbsent(chunk, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(host);
    }

    void remove(T host) {
        var chunk = positions.remove(host);
        if (chunk == null) return;
        var members = chunks.get(chunk);
        members.remove(host);
        if (members.isEmpty()) chunks.remove(chunk);
    }

    Set<T> removeChunk(ResourceKey<Level> dimension, long position) {
        var members = chunks.remove(new Chunk(dimension, position));
        if (members == null) return Set.of();
        for (T host : members) positions.remove(host);
        return members;
    }

    int size() {
        return positions.size();
    }

    void clear() {
        positions.clear();
        chunks.clear();
    }
}
