// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;

/**
 * Immutable authoritative identity and last-confirmed physical snapshot for one network node.
 *
 * <p>This world-independent value is safe to share as a read-only snapshot on any thread. Construction and
 * physical-snapshot replacement perform no simulation, world access, persistence mutation or caller mutation.
 * Missing fields throw {@link NullPointerException}; nonpositive numbers throw {@link IllegalArgumentException}.
 */
public record NetworkNodeRecord(
        UUID nodeId, long nodeNumber, ManagedName name, GlobalPos position, NodeForm form, Direction facing) {
    public NetworkNodeRecord {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(facing, "facing");
        if (nodeNumber < 1) {
            throw new IllegalArgumentException("Node number must be positive");
        }
    }

    /** Returns this value when unchanged or a new immutable snapshot; no authoritative state is modified. */
    public NetworkNodeRecord withPhysicalSnapshot(NodeForm newForm, Direction newFacing) {
        Objects.requireNonNull(newForm, "newForm");
        Objects.requireNonNull(newFacing, "newFacing");
        return form == newForm && facing == newFacing
                ? this
                : new NetworkNodeRecord(nodeId, nodeNumber, name, position, newForm, newFacing);
    }
}
