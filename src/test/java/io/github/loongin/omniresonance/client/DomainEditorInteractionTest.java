// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class DomainEditorInteractionTest {
    private static final UUID SESSION = new UUID(10, 1);

    private static NodeMenuNodeSummary node() {
        return new NodeMenuNodeSummary(
                new UUID(1, 1),
                "Network",
                new UUID(1, 2),
                "Node",
                0,
                ResourceLocation.parse("minecraft:overworld"),
                BlockPos.ZERO,
                NodeForm.BLOCK,
                Direction.DOWN,
                true,
                false,
                NodeMode.DOMAIN);
    }

    @Test
    void domainResourceEditsStayDirtyAcrossPickerRefreshAndRequireDiscardConfirmation() {
        var edit = new NodeMenuState.DomainEdit(node(), TransferDirection.INPUT);
        var model = NodeMenuInteractionPolicy.Model.loading()
                .apply(new NodeMenuResponse.State(7, SESSION, 0, edit))
                .model();
        model = model.resourceDraftDirty(true);
        assertTrue(model.dirty());
        model = model.submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, 1)
                .apply(new NodeMenuResponse.State(7, SESSION, 1, edit))
                .model();
        assertTrue(model.dirty());
        assertEquals(NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD, model.backAction());
    }

    @Test
    void rootStatusPollingDoesNotBecomeAWaitingMutation() {
        var root = new NodeMenuState.DomainRoot(node(), TransferDirection.INPUT);
        var model = NodeMenuInteractionPolicy.Model.loading()
                .apply(new NodeMenuResponse.State(7, SESSION, 0, root))
                .model();
        model = model.submit(NodeMenuInteractionPolicy.PendingKind.STATUS, 1);
        assertFalse(model.mutationPending());
        assertEquals(1, model.expectedBackgroundSequence());
    }
}
