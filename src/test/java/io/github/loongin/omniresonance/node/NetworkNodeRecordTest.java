// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** Pure validation and immutable physical-snapshot behavior for authoritative node records. */
final class NetworkNodeRecordTest {
    private static final UUID NODE = new UUID(7, 9);
    private static final GlobalPos POSITION = GlobalPos.of(Level.OVERWORLD, new BlockPos(12, 64, -9));

    @Test
    void validRecordOwnsStableIdentityAndReusesUnchangedSnapshot() {
        NetworkNodeRecord record =
                new NetworkNodeRecord(NODE, 1, new ManagedName("Ore input"), POSITION, NodeForm.PANEL, Direction.WEST);

        assertEquals(NODE, record.nodeId());
        assertEquals(1, record.nodeNumber());
        assertEquals("Ore input", record.name().value());
        assertEquals(POSITION, record.position());
        assertSame(record, record.withPhysicalSnapshot(NodeForm.PANEL, Direction.WEST));
    }

    @Test
    void changedPhysicalSnapshotPreservesAuthorityFields() {
        NetworkNodeRecord record =
                new NetworkNodeRecord(NODE, 4, new ManagedName("Ore input"), POSITION, NodeForm.PANEL, Direction.WEST);

        NetworkNodeRecord changed = record.withPhysicalSnapshot(NodeForm.BLOCK, Direction.UP);

        assertNotSame(record, changed);
        assertEquals(NODE, changed.nodeId());
        assertEquals(4, changed.nodeNumber());
        assertEquals(record.name(), changed.name());
        assertEquals(POSITION, changed.position());
        assertEquals(NodeForm.BLOCK, changed.form());
        assertEquals(Direction.UP, changed.facing());
    }

    @Test
    void missingFieldsAndNonpositiveNumbersAreRejected() {
        ManagedName name = new ManagedName("Node");
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(null, 1, name, POSITION, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(NODE, 1, null, POSITION, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(NODE, 1, name, null, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class, () -> new NetworkNodeRecord(NODE, 1, name, POSITION, null, Direction.DOWN));
        assertThrows(
                NullPointerException.class, () -> new NetworkNodeRecord(NODE, 1, name, POSITION, NodeForm.BLOCK, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkNodeRecord(NODE, 0, name, POSITION, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NetworkNodeRecord(NODE, -1, name, POSITION, NodeForm.BLOCK, Direction.DOWN));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(NODE, 1, name, POSITION, NodeForm.BLOCK, Direction.DOWN)
                        .withPhysicalSnapshot(null, Direction.UP));
        assertThrows(
                NullPointerException.class,
                () -> new NetworkNodeRecord(NODE, 1, name, POSITION, NodeForm.BLOCK, Direction.DOWN)
                        .withPhysicalSnapshot(NodeForm.PANEL, null));
    }
}
