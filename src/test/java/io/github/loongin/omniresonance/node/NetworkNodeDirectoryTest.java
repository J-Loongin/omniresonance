// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Derived-index contracts for unique and conflicting authoritative node records. */
final class NetworkNodeDirectoryTest {
    private static final UUID NETWORK_A = new UUID(1, 1);
    private static final UUID NETWORK_B = new UUID(1, 2);
    private static final UUID NODE_A = new UUID(2, 1);
    private static final UUID NODE_B = new UUID(2, 2);
    private static final UUID NODE_C = new UUID(2, 3);
    private static final GlobalPos POS_A = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 1));
    private static final GlobalPos POS_B = GlobalPos.of(Level.OVERWORLD, new BlockPos(20, 70, 2));
    private static final GlobalPos POS_C = GlobalPos.of(Level.NETHER, new BlockPos(1, 64, 1));

    @Test
    void uniqueEntriesAreIndexedByIdPositionAndDimensionChunk() {
        NetworkNodeDirectory.Entry second = entry(NETWORK_A, NODE_B, 2, "B", POS_B);
        NetworkNodeDirectory.Entry first = entry(NETWORK_A, NODE_A, 1, "A", POS_A);
        NetworkNodeDirectory.Entry third = entry(NETWORK_B, NODE_C, 1, "C", POS_C);
        NetworkNodeDirectory directory = new NetworkNodeDirectory(List.of(second, third, first));

        assertUnique(first, directory.byId(NODE_A));
        assertUnique(first, directory.byPosition(POS_A));
        assertUnique(third, directory.byPosition(POS_C));
        assertEquals(List.of(first), directory.recordsInChunk(Level.OVERWORLD, new ChunkPos(POS_A.pos())));
        assertEquals(List.of(third), directory.recordsInChunk(Level.NETHER, new ChunkPos(POS_C.pos())));
        assertEquals(List.of(first, second, third), directory.allUniqueEntries());
        assertEquals(
                NetworkNodeDirectory.Status.ABSENT,
                directory.byId(new UUID(9, 9)).status());
        assertTrue(directory.byId(new UUID(9, 9)).entry().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> directory.allUniqueEntries().clear());
    }

    @Test
    void runtimeMutationsKeepAllThreeIndexesConsistent() {
        NetworkNodeDirectory.Entry first = entry(NETWORK_A, NODE_A, 1, "A", POS_A);
        NetworkNodeDirectory directory = new NetworkNodeDirectory(List.of(first));
        NetworkNodeDirectory.Entry second = entry(NETWORK_A, NODE_B, 2, "B", POS_B);

        assertEquals(second, directory.add(second));
        assertUnique(second, directory.byId(NODE_B));
        assertEquals(List.of(second), directory.recordsInChunk(Level.OVERWORLD, new ChunkPos(POS_B.pos())));

        NetworkNodeRecord changedRecord = first.record().withPhysicalSnapshot(NodeForm.PANEL, Direction.UP);
        NetworkNodeDirectory.Entry changed = new NetworkNodeDirectory.Entry(NETWORK_A, changedRecord);
        assertEquals(changed, directory.update(first, changed));
        assertUnique(changed, directory.byId(NODE_A));
        assertUnique(changed, directory.byPosition(POS_A));

        assertTrue(directory.remove(NETWORK_A, NODE_A, POS_B).isEmpty());
        assertUnique(changed, directory.byId(NODE_A));
        assertEquals(changed, directory.remove(NETWORK_A, NODE_A, POS_A).orElseThrow());
        assertEquals(NetworkNodeDirectory.Status.ABSENT, directory.byId(NODE_A).status());
        assertEquals(
                NetworkNodeDirectory.Status.ABSENT, directory.byPosition(POS_A).status());
        assertTrue(directory
                .recordsInChunk(Level.OVERWORLD, new ChunkPos(POS_A.pos()))
                .isEmpty());
    }

    @Test
    void managementSnapshotReplacementUpdatesAllThreeIndexes() {
        NetworkNodeDirectory.Entry first = entry(NETWORK_A, NODE_A, 1, "A", POS_A);
        NetworkNodeDirectory directory = new NetworkNodeDirectory(List.of(first));
        NetworkNodeRecord changedRecord = first.record()
                .withName(new ManagedName("Crusher input"))
                .withChunkLoadingRequested(true)
                .withMode(NodeMode.DIRECT)
                .withEnabled(false);
        NetworkNodeDirectory.Entry changed = new NetworkNodeDirectory.Entry(NETWORK_A, changedRecord);

        assertEquals(changed, directory.update(first, changed));
        assertUnique(changed, directory.byId(NODE_A));
        assertUnique(changed, directory.byPosition(POS_A));
        assertEquals(List.of(changed), directory.recordsInChunk(Level.OVERWORLD, new ChunkPos(POS_A.pos())));
        assertEquals(List.of(changed), directory.allUniqueEntries());
    }

    @Test
    void preparedNetworkReplacementChangesOnlyNetworkAndTargetNumber() {
        NetworkNodeDirectory.Entry source = entry(NETWORK_A, NODE_A, 1, "A", POS_A);
        NetworkNodeDirectory directory = new NetworkNodeDirectory(List.of(source));
        NetworkNodeRecord moved = source.record().moveTo(7, new ManagedName("Moved"));
        NetworkNodeDirectory.Entry target = new NetworkNodeDirectory.Entry(NETWORK_B, moved);

        NetworkNodeDirectory.PreparedNetworkReplacement prepared = directory.prepareNetworkReplacement(source, target);
        assertUnique(source, directory.byId(NODE_A));
        assertEquals(target, directory.commitNetworkReplacement(prepared));

        assertUnique(target, directory.byId(NODE_A));
        assertUnique(target, directory.byPosition(POS_A));
        assertEquals(List.of(target), directory.recordsInChunk(Level.OVERWORLD, new ChunkPos(POS_A.pos())));
        assertThrows(
                IllegalArgumentException.class,
                () -> directory.prepareNetworkReplacement(target, new NetworkNodeDirectory.Entry(NETWORK_B, moved)));
    }

    @Test
    void startupConflictsAreLocalAndIndependentOfInputOrder() {
        NetworkNodeDirectory.Entry sameIdFirst = entry(NETWORK_A, NODE_A, 1, "A", POS_A);
        NetworkNodeDirectory.Entry sameIdSecond = entry(NETWORK_B, NODE_A, 1, "B", POS_B);
        NetworkNodeDirectory.Entry samePosition = entry(NETWORK_B, NODE_B, 2, "C", POS_A);
        NetworkNodeDirectory.Entry healthy = entry(NETWORK_A, NODE_C, 3, "Healthy", POS_C);
        List<NetworkNodeDirectory.Entry> entries = List.of(sameIdFirst, sameIdSecond, samePosition, healthy);

        for (List<NetworkNodeDirectory.Entry> order : List.of(entries, reversed(entries))) {
            NetworkNodeDirectory directory = new NetworkNodeDirectory(order);
            assertEquals(
                    NetworkNodeDirectory.Status.CONFLICTED,
                    directory.byId(NODE_A).status());
            assertEquals(
                    NetworkNodeDirectory.Status.CONFLICTED,
                    directory.byId(NODE_B).status());
            assertEquals(
                    NetworkNodeDirectory.Status.CONFLICTED,
                    directory.byPosition(POS_A).status());
            assertEquals(
                    NetworkNodeDirectory.Status.CONFLICTED,
                    directory.byPosition(POS_B).status());
            assertUnique(healthy, directory.byId(NODE_C));
            assertUnique(healthy, directory.byPosition(POS_C));
            assertEquals(List.of(healthy), directory.allUniqueEntries());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> directory.add(entry(
                            new UUID(8, 8), NODE_A, 9, "Cannot win", GlobalPos.of(Level.END, new BlockPos(1, 80, 1)))));
        }
    }

    @Test
    void invalidUpdatesAndWrongThreadCallsRejectBeforeMutation() throws Exception {
        NetworkNodeDirectory.Entry first = entry(NETWORK_A, NODE_A, 1, "A", POS_A);
        NetworkNodeDirectory directory = new NetworkNodeDirectory(List.of(first));
        assertThrows(NullPointerException.class, () -> new NetworkNodeDirectory(null));
        List<NetworkNodeDirectory.Entry> withNull = new ArrayList<>();
        withNull.add(first);
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new NetworkNodeDirectory(withNull));
        assertThrows(NullPointerException.class, () -> directory.byId(null));
        assertThrows(NullPointerException.class, () -> directory.byPosition(null));
        assertThrows(
                IllegalArgumentException.class, () -> directory.update(first, entry(NETWORK_B, NODE_A, 1, "A", POS_A)));
        assertThrows(
                IllegalArgumentException.class, () -> directory.update(first, entry(NETWORK_A, NODE_B, 1, "A", POS_A)));
        NetworkNodeRecord renumbered = new NetworkNodeRecord(
                NODE_A,
                2,
                first.record().name(),
                POS_A,
                first.record().form(),
                first.record().facing(),
                first.record().revision(),
                first.record().enabled(),
                first.record().chunkLoadingRequested(),
                first.record().mode());
        assertThrows(
                IllegalArgumentException.class,
                () -> directory.update(first, new NetworkNodeDirectory.Entry(NETWORK_A, renumbered)));
        assertThrows(
                IllegalArgumentException.class, () -> directory.update(first, entry(NETWORK_A, NODE_A, 1, "A", POS_B)));
        assertUnique(first, directory.byId(NODE_A));

        try (var executor = Executors.newSingleThreadExecutor()) {
            for (Runnable operation : List.<Runnable>of(
                    () -> directory.byId(NODE_A),
                    () -> directory.byPosition(POS_A),
                    () -> directory.recordsInChunk(Level.OVERWORLD, new ChunkPos(POS_A.pos())),
                    directory::allUniqueEntries,
                    () -> directory.add(entry(NETWORK_A, NODE_B, 2, "B", POS_B)),
                    () -> directory.update(first, first),
                    () -> directory.prepareNetworkReplacement(
                            first,
                            new NetworkNodeDirectory.Entry(
                                    NETWORK_B, first.record().moveTo(2, new ManagedName("Moved")))),
                    () -> directory.remove(NETWORK_A, NODE_A, POS_A))) {
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> executor.submit(operation).get());
                assertTrue(failure.getCause() instanceof IllegalStateException);
            }
        }
        assertUnique(first, directory.byId(NODE_A));
    }

    private static void assertUnique(NetworkNodeDirectory.Entry expected, NetworkNodeDirectory.Lookup actual) {
        assertEquals(NetworkNodeDirectory.Status.UNIQUE, actual.status());
        assertEquals(expected, actual.entry().orElseThrow());
    }

    private static NetworkNodeDirectory.Entry entry(
            UUID networkId, UUID nodeId, long number, String name, GlobalPos position) {
        return new NetworkNodeDirectory.Entry(
                networkId,
                NetworkNodeRecord.fresh(
                        nodeId, number, new ManagedName(name), position, NodeForm.BLOCK, Direction.DOWN));
    }

    private static List<NetworkNodeDirectory.Entry> reversed(List<NetworkNodeDirectory.Entry> entries) {
        List<NetworkNodeDirectory.Entry> reversed = new ArrayList<>(entries);
        java.util.Collections.reverse(reversed);
        return reversed;
    }
}
