// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NodeForm;
import net.minecraft.core.Direction;

/** Parent editor draft shared with the selector; only the server save commits it. */
final class NodeWorkingFacesDraft {
    private final boolean panel;
    private int selection;
    private boolean open;

    NodeWorkingFacesDraft(WorkingFaces faces, NodeForm form, Direction facing) {
        faces.validate(form);
        panel = form == NodeForm.PANEL;
        selection = faces.effectiveMask(facing);
    }

    void open() {
        open = true;
    }

    void close() {
        open = false;
    }

    boolean openNow() {
        return open;
    }

    boolean panel() {
        return panel;
    }

    boolean selected(Direction direction) {
        return (selection & (1 << direction.get3DDataValue())) != 0;
    }

    void toggle(Direction direction) {
        if (panel || !open) throw new IllegalStateException("Working face selection is fixed or closed");
        selection ^= 1 << direction.get3DDataValue();
    }

    WorkingFaces value() {
        return panel ? WorkingFaces.attachedFace() : WorkingFaces.explicit(selection);
    }
}
