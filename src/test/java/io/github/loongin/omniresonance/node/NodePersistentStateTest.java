// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/** Pure codec and ownership contracts for node-owned block-entity fields. */
final class NodePersistentStateTest {
    private static final UUID NODE_ID = new UUID(1, 2);
    private static final List<String> OWNED_KEYS = List.of("schema_version", "node_id", "link_state");

    @Test
    void freshStateWritesExactVersionedBlankFieldsWithoutErasingOtherData() {
        NodePersistentState state = NodePersistentState.fresh(NODE_ID);
        CompoundTag output = new CompoundTag();
        output.putString("another_mod", "keep");

        state.writeOwnedFields(output);

        assertEquals(1, output.getInt("schema_version"));
        assertEquals(NODE_ID, output.getUUID("node_id"));
        assertEquals("blank", output.getString("link_state"));
        assertEquals("keep", output.getString("another_mod"));
        assertEquals(
                new NodePersistentState.Valid(NODE_ID, NodeLinkState.BLANK),
                state.valid().orElseThrow());
        assertFalse(state.isUnavailable());
    }

    @Test
    void legalBlankAndLinkedStatesRoundTrip() {
        for (NodeLinkState linkState : NodeLinkState.values()) {
            CompoundTag tag = validTag(linkState.serializedName());
            NodePersistentState decoded = NodePersistentState.decode(tag);
            assertEquals(
                    new NodePersistentState.Valid(NODE_ID, linkState),
                    decoded.valid().orElseThrow());
            CompoundTag output = new CompoundTag();
            decoded.writeOwnedFields(output);
            assertEquals(tag, output);
        }
    }

    @Test
    void everyMalformedShapeStaysUnavailableAndPreservesExistingRawFields() {
        CompoundTag missing = new CompoundTag();
        CompoundTag oldSchema = validTag("blank");
        oldSchema.putInt("schema_version", 0);
        CompoundTag futureSchema = validTag("blank");
        futureSchema.putInt("schema_version", 2);
        CompoundTag wrongSchemaType = validTag("blank");
        wrongSchemaType.putString("schema_version", "one");
        CompoundTag malformedUuid = validTag("blank");
        malformedUuid.put("node_id", new IntArrayTag(new int[] {1, 2}));
        CompoundTag wrongUuidType = validTag("blank");
        wrongUuidType.putString("node_id", NODE_ID.toString());
        CompoundTag unknownLink = validTag("unknown");
        CompoundTag wrongLinkType = validTag("blank");
        wrongLinkType.putInt("link_state", 1);

        for (CompoundTag fixture : List.of(
                missing,
                oldSchema,
                futureSchema,
                wrongSchemaType,
                malformedUuid,
                wrongUuidType,
                unknownLink,
                wrongLinkType)) {
            CompoundTag snapshot = fixture.copy();
            NodePersistentState decoded = NodePersistentState.decode(fixture);
            mutateOwnedFields(fixture);

            assertTrue(decoded.isUnavailable());
            assertTrue(decoded.valid().isEmpty());
            CompoundTag output = new CompoundTag();
            output.putString("unrelated", "preserved");
            decoded.writeOwnedFields(output);
            assertEquals("preserved", output.getString("unrelated"));
            for (String key : OWNED_KEYS) {
                assertEquals(snapshot.get(key), output.get(key), () -> "Owned field changed: " + key);
            }
        }
    }

    @Test
    void linkStateAndValidStateRejectUnknownOrMissingInputs() {
        assertEquals(NodeLinkState.BLANK, NodeLinkState.fromSerialized("blank"));
        assertEquals(NodeLinkState.LINKED, NodeLinkState.fromSerialized("linked"));
        assertThrows(IllegalArgumentException.class, () -> NodeLinkState.fromSerialized("BLANK"));
        assertThrows(IllegalArgumentException.class, () -> NodeLinkState.fromSerialized("unknown"));
        assertThrows(NullPointerException.class, () -> NodeLinkState.fromSerialized(null));
        assertThrows(NullPointerException.class, () -> NodePersistentState.fresh(null));
        assertThrows(NullPointerException.class, () -> NodePersistentState.decode(null));
        assertThrows(NullPointerException.class, () -> new NodePersistentState.Valid(null, NodeLinkState.BLANK));
        assertThrows(NullPointerException.class, () -> new NodePersistentState.Valid(NODE_ID, null));
    }

    @Test
    void nodeFormsHaveStableSerializedNames() {
        assertEquals("block", NodeForm.BLOCK.serializedName());
        assertEquals("panel", NodeForm.PANEL.serializedName());
    }

    private static CompoundTag validTag(String linkState) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", 1);
        tag.putUUID("node_id", NODE_ID);
        tag.putString("link_state", linkState);
        return tag;
    }

    private static void mutateOwnedFields(CompoundTag tag) {
        tag.putInt("schema_version", 99);
        tag.putUUID("node_id", new UUID(9, 9));
        tag.put("link_state", StringTag.valueOf("mutated"));
    }
}
