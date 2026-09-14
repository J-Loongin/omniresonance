// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.networking.NodeChannelSummary;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuRequest;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class NodeResourceScreenTest {
    private static final UUID SESSION = new UUID(1, 2);

    private static NodeMenuState.DirectBindingEdit state() {
        var node = new NodeMenuNodeSummary(
                new UUID(2, 1),
                "Network",
                new UUID(3, 1),
                "Node",
                1,
                ResourceLocation.parse("minecraft:overworld"),
                BlockPos.ZERO,
                NodeForm.BLOCK,
                Direction.DOWN,
                true,
                false,
                NodeMode.DIRECT);
        return new NodeMenuState.DirectBindingEdit(
                node,
                new NodeTunnelSummary(new UUID(4, 1), "Tunnel", 1, true, 1, 1, 1),
                new NodeChannelSummary(new UUID(5, 1), "Channel", 1, 1, 0, TransferDirection.INPUT));
    }

    private static NodeResourcePolicyDraft draft() {
        return new NodeResourcePolicyDraft(state().policy(), null, NodeResourcePolicyDraftTest.catalog());
    }

    private static Font font() {
        return new Font(
                id -> {
                    throw new AssertionError("No graphics required");
                },
                false) {
            @Override
            public int width(String text) {
                return text.length();
            }

            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.clamp(width, 0, text.length()));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean tail) {
                int n = Math.clamp(width, 0, text.length());
                return tail ? text.substring(text.length() - n) : text.substring(0, n);
            }
        };
    }

    @Test
    void productionFormButtonsUseResourceDraftAndSaveActualIntent() {
        var draft = draft();
        var faces = new NodeWorkingFacesDraft(WorkingFaces.explicit(0), NodeForm.BLOCK, Direction.DOWN);
        var widgets = new ArrayList<AbstractWidget>();
        NodeMenuRequest[] saved = {null};
        int[] scopeClicks = {0};
        var actions = new NodeResourcePolicyView.Actions(
                () -> {},
                () -> {},
                () -> {},
                draft::confirmDirectionChange,
                () -> {},
                () -> scopeClicks[0]++,
                () -> {},
                () -> saved[0] = ResonanceNodeScreen.resourceSaveRequest(1, SESSION, 1, draft, faces, new UUID(8, 1)));
        ResonanceNodeScreen.buildResourceForm(
                font(),
                TerminalLayout.calculate(960, 540).content(),
                0,
                draft,
                false,
                widgets::add,
                Component.empty(),
                actions);
        ((TerminalButton) widgets.get(1)).onPress();
        assertEquals(1, scopeClicks[0]);
        ((TerminalEditBox) widgets.stream()
                        .filter(TerminalEditBox.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .setValue("7");
        ((TerminalButton) widgets.getLast()).onPress();
        var request = assertInstanceOf(NodeMenuRequest.SaveResourcePolicy.class, saved[0]);
        assertEquals(7, request.policy().intervalTicks());
        assertEquals(ResourceScope.Kind.ALL, request.policy().scope().kind());
        assertEquals(WorkingFaces.explicit(0), request.workingFaces());
        assertFalse(request.confirmedReset());
    }

    @Test
    void productionSavePendingSurvivesPreparationAndAcceptsFinalSameSequence() {
        var state = state();
        var model = NodeMenuInteractionPolicy.Model.loading()
                .apply(new NodeMenuResponse.State(1, SESSION, 0, state))
                .model()
                .edited()
                .submit(NodeMenuInteractionPolicy.PendingKind.SAVE, 1);
        var ready = model.apply(new NodeMenuResponse.UploadReady(1, SESSION, 1, new UUID(7, 1)));
        assertFalse(ready.accepted());
        assertSame(model, ready.model());
        assertEquals(
                NodeMenuInteractionPolicy.PendingKind.SAVE,
                ready.model().pending().kind());
        var finalResponse = ready.model()
                .apply(new NodeMenuResponse.State(
                        1,
                        SESSION,
                        1,
                        new NodeMenuState.DirectChannelRoot(state.node(), state.tunnel(), state.channel())));
        assertTrue(finalResponse.accepted());
        assertNull(finalResponse.model().pending());
        assertFalse(finalResponse
                .model()
                .apply(new NodeMenuResponse.State(1, SESSION, 1, state))
                .accepted());
    }

    @Test
    void unappliedScopeAndSixFacesParticipateInProductionCloseDirty() {
        var draft = draft();
        var state = state();
        var faces = new NodeWorkingFacesDraft(state.workingFaces(), NodeForm.BLOCK, Direction.DOWN);
        var scope = draft.openScope();
        var selection = NodeResourceTypeSelection.scope(draft, scope, Object::toString);
        assertFalse(ResonanceNodeScreen.resourceDirty(draft, selection, faces, state));
        selection.choose(ResourceTypes.FLUID);
        assertTrue(ResonanceNodeScreen.resourceDirty(draft, selection, faces, state));
        assertFalse(ResonanceNodeScreen.resourceDirty(draft, null, faces, state));
        faces.open();
        faces.toggle(Direction.EAST);
        assertTrue(ResonanceNodeScreen.resourceDirty(draft, null, faces, state));
        faces.toggle(Direction.EAST);
        assertFalse(ResonanceNodeScreen.resourceDirty(draft, null, faces, state));
    }

    @Test
    void resourceSearchUsesProductionHeaderAndFocusedKeyPriority() {
        var selection = NodeResourceTypeSelection.overrides(draft(), Object::toString);
        assertEquals(TerminalHeaderLayout.Action.SEARCH, ResonanceNodeScreen.topBarAction(state(), true));
        int[] closed = {0};
        assertTrue(ResonanceNodeScreen.routeSearchKey(
                org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,
                0,
                0,
                null,
                true,
                () -> closed[0]++,
                selection.search(),
                true,
                () -> {}));
        assertEquals(1, closed[0]);
        assertFalse(selection.search().expanded());
        var field = new TerminalEditBox(font(), 0, 0, 100, 20, Component.empty());
        field.setFocused(true);
        assertTrue(ResonanceNodeScreen.routeSearchKey(
                org.lwjgl.glfw.GLFW.GLFW_KEY_E,
                0,
                0,
                field,
                true,
                () -> closed[0]++,
                selection.search(),
                true,
                () -> {}));
        assertEquals(1, closed[0]);
    }

    @Test
    void productionWheelReachesSparseSaveWithoutChangingDraft() {
        var draft = draft();
        draft.addType(ResourceTypes.ITEM);
        draft.addType(ResourceTypes.FLUID);
        draft.expanded = true;
        var body = TerminalLayout.calculate(320, 240).content();
        int offset = 0;
        for (int i = 0; i < 30; i++) offset = ResonanceNodeScreen.scrollResourceForm(body, offset, draft, -1);
        var layout = NodeResourcePolicyView.layout(body, offset, draft);
        assertEquals(layout.totalRows(), layout.firstRow() + layout.visibleRows());
        assertEquals(2, draft.settingIds().size());
    }
}
