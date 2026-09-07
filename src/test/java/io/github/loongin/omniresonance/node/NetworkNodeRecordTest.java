// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Pure validation and immutable revisioned behavior for authoritative node records. */
final class NetworkNodeRecordTest {
    private static final UUID NODE = new UUID(7, 9);
    private static final GlobalPos POSITION = GlobalPos.of(Level.OVERWORLD, new BlockPos(12, 64, -9));

    @Test
    void validRecordOwnsStableIdentityAndReusesUnchangedSnapshot() {
        ManagedName name = new ManagedName("Ore input");
        NetworkNodeRecord record = NetworkNodeRecord.fresh(NODE, 1, name, POSITION, NodeForm.PANEL, Direction.WEST);

        assertAll(
                () -> assertEquals(NODE, record.nodeId()),
                () -> assertEquals(1, record.nodeNumber()),
                () -> assertEquals("Ore input", record.name().value()),
                () -> assertEquals(POSITION, record.position()),
                () -> assertEquals(0, record.revision()),
                () -> assertTrue(record.enabled()),
                () -> assertFalse(record.chunkLoadingRequested()),
                () -> assertEquals(NodeMode.UNCONFIGURED, record.mode()));
        assertAll(
                () -> assertSame(record, record.withPhysicalSnapshot(NodeForm.PANEL, Direction.WEST)),
                () -> assertSame(record, record.withName(name)),
                () -> assertSame(record, record.withEnabled(true)),
                () -> assertSame(record, record.withChunkLoadingRequested(false)),
                () -> assertSame(record, record.withMode(NodeMode.UNCONFIGURED)));
    }

    @Test
    void changedPhysicalSnapshotPreservesAuthorityFields() {
        NetworkNodeRecord record = NetworkNodeRecord.fresh(
                NODE, 4, new ManagedName("Ore input"), POSITION, NodeForm.PANEL, Direction.WEST);

        NetworkNodeRecord changed = record.withPhysicalSnapshot(NodeForm.BLOCK, Direction.UP);

        assertNotSame(record, changed);
        assertEquals(NODE, changed.nodeId());
        assertEquals(4, changed.nodeNumber());
        assertEquals(record.name(), changed.name());
        assertEquals(POSITION, changed.position());
        assertEquals(NodeForm.BLOCK, changed.form());
        assertEquals(Direction.UP, changed.facing());
        assertEquals(1, changed.revision());
        assertTrue(changed.enabled());
        assertFalse(changed.chunkLoadingRequested());
        assertEquals(NodeMode.UNCONFIGURED, changed.mode());
    }

    @Test
    void managementTransitionsEachAdvanceExactlyOneRevision() {
        NetworkNodeRecord record = NetworkNodeRecord.fresh(
                NODE, 4, new ManagedName("Ore input"), POSITION, NodeForm.PANEL, Direction.WEST);

        NetworkNodeRecord renamed = record.withName(new ManagedName("Ore output"));
        NetworkNodeRecord disabled = renamed.withEnabled(false);
        NetworkNodeRecord requested = disabled.withChunkLoadingRequested(true);
        NetworkNodeRecord direct = requested.withMode(NodeMode.DIRECT);

        assertAll(
                () -> assertEquals(1, renamed.revision()),
                () -> assertEquals("Ore output", renamed.name().value()),
                () -> assertEquals(2, disabled.revision()),
                () -> assertFalse(disabled.enabled()),
                () -> assertEquals(3, requested.revision()),
                () -> assertTrue(requested.chunkLoadingRequested()),
                () -> assertEquals(4, direct.revision()),
                () -> assertEquals(NodeMode.DIRECT, direct.mode()));
    }

    @Test
    void revisionOverflowRejectsEveryRealChangeBeforeCreatingAValue() {
        NetworkNodeRecord exhausted = new NetworkNodeRecord(
                NODE,
                1,
                new ManagedName("Node"),
                POSITION,
                NodeForm.BLOCK,
                Direction.DOWN,
                Long.MAX_VALUE,
                true,
                false,
                NodeMode.UNCONFIGURED);

        assertAll(
                () -> assertThrows(
                        ArithmeticException.class, () -> exhausted.withPhysicalSnapshot(NodeForm.PANEL, Direction.UP)),
                () -> assertThrows(ArithmeticException.class, () -> exhausted.withName(new ManagedName("Changed"))),
                () -> assertThrows(ArithmeticException.class, () -> exhausted.withEnabled(false)),
                () -> assertThrows(ArithmeticException.class, () -> exhausted.withChunkLoadingRequested(true)),
                () -> assertThrows(ArithmeticException.class, () -> exhausted.withMode(NodeMode.DIRECT)));
    }

    @Test
    void missingFieldsAndNonpositiveNumbersAreRejected() {
        ManagedName name = new ManagedName("Node");
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(
                        null,
                        1,
                        name,
                        POSITION,
                        NodeForm.BLOCK,
                        Direction.DOWN,
                        0,
                        true,
                        false,
                        NodeMode.UNCONFIGURED));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(
                        NODE,
                        1,
                        null,
                        POSITION,
                        NodeForm.BLOCK,
                        Direction.DOWN,
                        0,
                        true,
                        false,
                        NodeMode.UNCONFIGURED));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(
                        NODE, 1, name, null, NodeForm.BLOCK, Direction.DOWN, 0, true, false, NodeMode.UNCONFIGURED));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(
                        NODE, 1, name, POSITION, null, Direction.DOWN, 0, true, false, NodeMode.UNCONFIGURED));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(
                        NODE, 1, name, POSITION, NodeForm.BLOCK, null, 0, true, false, NodeMode.UNCONFIGURED));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(
                        NODE, 1, name, POSITION, NodeForm.BLOCK, Direction.DOWN, 0, true, false, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkNodeRecord(
                        NODE,
                        0,
                        name,
                        POSITION,
                        NodeForm.BLOCK,
                        Direction.DOWN,
                        0,
                        true,
                        false,
                        NodeMode.UNCONFIGURED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkNodeRecord(
                        NODE,
                        -1,
                        name,
                        POSITION,
                        NodeForm.BLOCK,
                        Direction.DOWN,
                        0,
                        true,
                        false,
                        NodeMode.UNCONFIGURED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkNodeRecord(
                        NODE,
                        1,
                        name,
                        POSITION,
                        NodeForm.BLOCK,
                        Direction.DOWN,
                        -1,
                        true,
                        false,
                        NodeMode.UNCONFIGURED));
        assertThrows(
                NullPointerException.class,
                () -> NetworkNodeRecord.fresh(NODE, 1, name, POSITION, NodeForm.BLOCK, Direction.DOWN)
                        .withPhysicalSnapshot(null, Direction.UP));
        assertThrows(
                NullPointerException.class,
                () -> NetworkNodeRecord.fresh(NODE, 1, name, POSITION, NodeForm.BLOCK, Direction.DOWN)
                        .withPhysicalSnapshot(NodeForm.PANEL, null));
        NetworkNodeRecord record = NetworkNodeRecord.fresh(NODE, 1, name, POSITION, NodeForm.BLOCK, Direction.DOWN);
        assertThrows(NullPointerException.class, () -> record.withName(null));
        assertThrows(NullPointerException.class, () -> record.withMode(null));
    }
}
