// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Immutable owned-field representation for one physical node block entity.
 *
 * <p>Decode never borrows or changes caller NBT. Invalid data remains unavailable and re-emits defensive copies of
 * only existing owned fields; no decoder branch invents a UUID or blank state. Values contain no world, entity or
 * inventory references and cause no persistence mutation until a caller explicitly writes them to its output tag.
 */
public sealed interface NodePersistentState permits NodePersistentState.Valid, NodePersistentState.Unavailable {
    int SCHEMA_VERSION = 1;
    String SCHEMA_VERSION_KEY = "schema_version";
    String NODE_ID_KEY = "node_id";
    String LINK_STATE_KEY = "link_state";

    /** Creates a new valid blank state with caller-supplied server identity. */
    static NodePersistentState fresh(UUID nodeId) {
        return new Valid(Objects.requireNonNull(nodeId, "nodeId"), NodeLinkState.BLANK);
    }

    /** Creates a valid linked value for server authority transitions without persistence mutation. */
    static NodePersistentState linked(UUID nodeId) {
        return new Valid(Objects.requireNonNull(nodeId, "nodeId"), NodeLinkState.LINKED);
    }

    /** Strictly decodes the three owned fields, preserving their raw forms when any validation fails. */
    static NodePersistentState decode(CompoundTag source) {
        Objects.requireNonNull(source, "source");
        CompoundTag rawFields = copyOwnedFields(source);
        if (source.contains(SCHEMA_VERSION_KEY, Tag.TAG_INT)
                && source.getInt(SCHEMA_VERSION_KEY) == SCHEMA_VERSION
                && source.hasUUID(NODE_ID_KEY)
                && source.contains(LINK_STATE_KEY, Tag.TAG_STRING)) {
            try {
                return new Valid(
                        source.getUUID(NODE_ID_KEY), NodeLinkState.fromSerialized(source.getString(LINK_STATE_KEY)));
            } catch (IllegalArgumentException invalidValue) {
                // Return preserved unavailable data below.
            }
        }
        return new Unavailable(rawFields);
    }

    /** Returns a validated immutable value or empty for unavailable raw data. */
    Optional<Valid> valid();

    /** Reports unavailable decoded data without mutation or defaulting. */
    boolean isUnavailable();

    /** Merges only this component's owned fields into a caller-owned output tag. */
    void writeOwnedFields(CompoundTag output);

    /** Validated immutable node identity and stable link state. */
    record Valid(UUID nodeId, NodeLinkState linkState) implements NodePersistentState {
        public Valid {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(linkState, "linkState");
        }

        @Override
        public Optional<Valid> valid() {
            return Optional.of(this);
        }

        @Override
        public boolean isUnavailable() {
            return false;
        }

        @Override
        public void writeOwnedFields(CompoundTag output) {
            Objects.requireNonNull(output, "output");
            output.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
            output.putUUID(NODE_ID_KEY, nodeId);
            output.putString(LINK_STATE_KEY, linkState.serializedName());
        }
    }

    final class Unavailable implements NodePersistentState {
        private final CompoundTag rawFields;

        private Unavailable(CompoundTag rawFields) {
            this.rawFields = rawFields;
        }

        @Override
        public Optional<Valid> valid() {
            return Optional.empty();
        }

        @Override
        public boolean isUnavailable() {
            return true;
        }

        @Override
        public void writeOwnedFields(CompoundTag output) {
            Objects.requireNonNull(output, "output");
            for (String key : new String[] {SCHEMA_VERSION_KEY, NODE_ID_KEY, LINK_STATE_KEY}) {
                Tag value = rawFields.get(key);
                if (value != null) {
                    output.put(key, value.copy());
                }
            }
        }
    }

    private static CompoundTag copyOwnedFields(CompoundTag source) {
        Objects.requireNonNull(source, "source");
        CompoundTag copy = new CompoundTag();
        for (String key : new String[] {SCHEMA_VERSION_KEY, NODE_ID_KEY, LINK_STATE_KEY}) {
            Tag value = source.get(key);
            if (value != null) {
                copy.put(key, value.copy());
            }
        }
        return copy;
    }
}
