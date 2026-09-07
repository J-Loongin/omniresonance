// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.node;

import io.github.loongin.omniresonance.network.ManagedName;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;

/**
 * Immutable authoritative identity, physical snapshot and management state for one network node.
 *
 * <p>This world-independent value is safe to share as a read-only snapshot on any thread. Construction and
 * replacement methods perform no simulation, world access, persistence mutation or caller mutation. Every real
 * replacement increments the revision exactly once and rejects overflow before creating a value. Missing fields throw
 * {@link NullPointerException}; invalid numbers throw {@link IllegalArgumentException}.
 */
public record NetworkNodeRecord(
        UUID nodeId,
        long nodeNumber,
        ManagedName name,
        GlobalPos position,
        NodeForm form,
        Direction facing,
        long revision,
        boolean enabled,
        boolean chunkLoadingRequested,
        NodeMode mode) {
    public NetworkNodeRecord {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(mode, "mode");
        if (nodeNumber < 1) {
            throw new IllegalArgumentException("Node number must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Node revision must be nonnegative");
        }
    }

    /** Creates a newly linked node with the exact v3 management defaults and no external side effects. */
    public static NetworkNodeRecord fresh(
            UUID nodeId, long nodeNumber, ManagedName name, GlobalPos position, NodeForm form, Direction facing) {
        return new NetworkNodeRecord(
                nodeId, nodeNumber, name, position, form, facing, 0, true, false, NodeMode.UNCONFIGURED);
    }

    /** Returns this value when unchanged or a revisioned physical snapshot; no authoritative state is modified. */
    public NetworkNodeRecord withPhysicalSnapshot(NodeForm newForm, Direction newFacing) {
        Objects.requireNonNull(newForm, "newForm");
        Objects.requireNonNull(newFacing, "newFacing");
        return form == newForm && facing == newFacing
                ? this
                : new NetworkNodeRecord(
                        nodeId,
                        nodeNumber,
                        name,
                        position,
                        newForm,
                        newFacing,
                        nextRevision(),
                        enabled,
                        chunkLoadingRequested,
                        mode);
    }

    /** Returns this value when unchanged or a revisioned name snapshot without authoritative mutation. */
    public NetworkNodeRecord withName(ManagedName newName) {
        Objects.requireNonNull(newName, "newName");
        return name.equals(newName)
                ? this
                : new NetworkNodeRecord(
                        nodeId,
                        nodeNumber,
                        newName,
                        position,
                        form,
                        facing,
                        nextRevision(),
                        enabled,
                        chunkLoadingRequested,
                        mode);
    }

    /** Returns this value when unchanged or a revisioned enabled snapshot without authoritative mutation. */
    public NetworkNodeRecord withEnabled(boolean newEnabled) {
        return enabled == newEnabled
                ? this
                : new NetworkNodeRecord(
                        nodeId,
                        nodeNumber,
                        name,
                        position,
                        form,
                        facing,
                        nextRevision(),
                        newEnabled,
                        chunkLoadingRequested,
                        mode);
    }

    /** Returns this value when unchanged or a revisioned force-loading request without granting a ticket. */
    public NetworkNodeRecord withChunkLoadingRequested(boolean requested) {
        return chunkLoadingRequested == requested
                ? this
                : new NetworkNodeRecord(
                        nodeId, nodeNumber, name, position, form, facing, nextRevision(), enabled, requested, mode);
    }

    /** Returns this value when unchanged or a revisioned mode snapshot without creating mode configuration. */
    public NetworkNodeRecord withMode(NodeMode newMode) {
        Objects.requireNonNull(newMode, "newMode");
        return mode == newMode
                ? this
                : new NetworkNodeRecord(
                        nodeId,
                        nodeNumber,
                        name,
                        position,
                        form,
                        facing,
                        nextRevision(),
                        enabled,
                        chunkLoadingRequested,
                        newMode);
    }

    /** Returns a revisioned snapshot after one authoritative mode-configuration change. */
    public NetworkNodeRecord withConfigurationChanged() {
        return new NetworkNodeRecord(
                nodeId, nodeNumber, name, position, form, facing, nextRevision(), enabled, chunkLoadingRequested, mode);
    }

    /**
     * Returns the target-network snapshot for a prevalidated move. Physical and public settings are retained, the
     * target owns the supplied number/name, mode resets to unconfigured, and revision advances exactly once.
     */
    public NetworkNodeRecord moveTo(long targetNodeNumber, ManagedName targetName) {
        Objects.requireNonNull(targetName, "targetName");
        if (targetNodeNumber < 1) {
            throw new IllegalArgumentException("Target node number must be positive");
        }
        return new NetworkNodeRecord(
                nodeId,
                targetNodeNumber,
                targetName,
                position,
                form,
                facing,
                nextRevision(),
                enabled,
                chunkLoadingRequested,
                NodeMode.UNCONFIGURED);
    }

    private long nextRevision() {
        return Math.incrementExact(revision);
    }
}
