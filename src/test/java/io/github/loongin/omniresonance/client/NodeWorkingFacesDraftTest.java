// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NodeForm;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class NodeWorkingFacesDraftTest {
    @Test
    void selectorChangesParentDraftAndBackRetainsItWithoutChangingAuthority() {
        WorkingFaces authority = WorkingFaces.explicit(0);
        NodeWorkingFacesDraft draft = new NodeWorkingFacesDraft(authority, NodeForm.BLOCK, Direction.NORTH);
        draft.open();
        draft.toggle(Direction.UP);
        assertEquals(WorkingFaces.explicit(2), draft.value());
        draft.close();
        assertFalse(draft.openNow());
        draft.open();
        draft.toggle(Direction.WEST);
        assertEquals(WorkingFaces.explicit(18), draft.value());
        assertEquals(WorkingFaces.explicit(0), authority);
        NodeWorkingFacesDraft discarded = new NodeWorkingFacesDraft(authority, NodeForm.BLOCK, Direction.NORTH);
        assertEquals(WorkingFaces.explicit(0), discarded.value());
    }

    @Test
    void legacySingleFaceAndFixedPanelNeverExpandImplicitly() {
        NodeWorkingFacesDraft legacy =
                new NodeWorkingFacesDraft(WorkingFaces.attachedFace(), NodeForm.BLOCK, Direction.WEST);
        assertEquals(WorkingFaces.explicit(16), legacy.value());
        NodeWorkingFacesDraft panel =
                new NodeWorkingFacesDraft(WorkingFaces.attachedFace(), NodeForm.PANEL, Direction.NORTH);
        panel.open();
        assertThrows(IllegalStateException.class, () -> panel.toggle(Direction.UP));
        assertEquals(WorkingFaces.attachedFace(), panel.value());
    }
}
