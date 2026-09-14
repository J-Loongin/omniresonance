// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.node.NodeForm;
import java.util.Objects;
import net.minecraft.core.Direction;

/** Immutable bounded channel selection; pure operations retain no world, ownership or simulation state. */
public record WorkingFaces(int mask, boolean attached) {
    public WorkingFaces {
        if (mask < 0 || mask > 63 || (attached && mask != 0))
            throw new IllegalArgumentException("Invalid working faces");
    }

    /** Explicit six-bit selection in Direction data-value order; zero deliberately selects no target. */
    public static WorkingFaces explicit(int mask) {
        return new WorkingFaces(mask, false);
    }

    /** Fixed physical attached direction, also used to preserve legacy single-face bindings. */
    public static WorkingFaces attachedFace() {
        return new WorkingFaces(0, true);
    }

    /** Resolves fixed attachment without querying capabilities or mutating this value. */
    public int effectiveMask(Direction facing) {
        return attached ? 1 << Objects.requireNonNull(facing, "facing").get3DDataValue() : mask;
    }

    /** Rejects forged panel selections before authority mutation; panels exclusively use fixed attachment. */
    public void validate(NodeForm form) {
        if (Objects.requireNonNull(form, "form") == NodeForm.PANEL && !attached)
            throw new IllegalArgumentException("Panel working face must be attached");
    }
}
