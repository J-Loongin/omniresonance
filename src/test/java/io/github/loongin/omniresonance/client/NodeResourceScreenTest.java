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
    void resourceNamesUseTheSharedMatcherAndRecoverFromItsFailure() {
        var selection = NodeResourceTypeSelection.overrides(draft(), id -> "物品");
        selection.search().open();
        selection.editSearch("wp", 1);
        try {
            ClientTextSearch.install((name, query) -> name.equals("物品") && query.equals("wp"));
            selection.tick(2);
            assertFalse(selection.results().isEmpty());
            ClientTextSearch.usePlain();
            selection.tick(3);
            assertTrue(selection.results().isEmpty());
            selection.editSearch("minecraft:", 3);
            selection.tick(4);
            assertFalse(selection.results().isEmpty());
        } finally {
            ClientTextSearch.usePlain();
        }
    }

    @Test
    void resourceSettingsPageUsesOnlyTheSharedAddSlotAndPickerUsesSearch() {
        assertEquals(TerminalHeaderLayout.Action.NONE, ResonanceNodeScreen.topBarAction(state(), false, false));
        assertEquals(TerminalHeaderLayout.Action.CREATE, ResonanceNodeScreen.topBarAction(state(), false, true));
        assertEquals(TerminalHeaderLayout.Action.SEARCH, ResonanceNodeScreen.topBarAction(state(), true, true));
        var draft = draft();
        var widgets = new ArrayList<AbstractWidget>();
        var selected = new ArrayList<ResourceLocation>();
        var body = TerminalLayout.calculate(427, 240).content();
        NodeResourceSettingsView.buildList(body, draft, 0, true, widgets::add, selected::add);
        assertTrue(widgets.isEmpty(), "Unconfigured resources must not be enumerated");
        draft.addType(ResourceTypes.FLUID);
        NodeResourceSettingsView.buildList(body, draft, 0, true, widgets::add, selected::add);
        assertEquals(1, widgets.size());
        ((TerminalRowButton) widgets.getFirst()).onPress();
        assertEquals(java.util.List.of(ResourceTypes.FLUID), selected);
        var editor = new NodeResourceSettingEditor(draft, ResourceTypes.FLUID);
        widgets.clear();
        editor.rate = "1000";
        NodeResourceSettingsView.buildEditor(
                font(),
                body,
                editor,
                true,
                widgets::add,
                () -> {},
                () -> {},
                () -> {},
                editor::apply,
                () -> draft.restoreDefault(editor.id));
        assertEquals("2147483647", draft.type(ResourceTypes.FLUID).rate);
        ((TerminalButton) widgets.getLast()).onPress();
        assertEquals("1000", draft.type(ResourceTypes.FLUID).rate);
        assertTrue(widgets.stream().filter(TerminalEditBox.class::isInstance).allMatch(w -> w.getWidth() >= 100));
    }

    @Test
    void intervalUsesItsWholeCellAndScreenDispatchUpdatesDraftWithoutAChooser() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var draft = draft();
            var widgets = new ArrayList<AbstractWidget>();
            var actions = new NodeResourcePolicyView.Actions(
                    () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {});
            var body = TerminalLayout.calculate(size[0], size[1]).content();
            var layout = ResonanceNodeScreen.buildResourceForm(
                    font(), body, 0, draft, false, widgets::add, Component.empty(), actions);
            var field = (TerminalIntervalBox) widgets.stream()
                    .filter(TerminalIntervalBox.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            var cell = NodeResourcePolicyView.commonCells(layout.row(2), 2, layout.threeColumns())
                    .getFirst();
            assertEquals(cell.bounds().width(), field.getWidth());
            assertTrue(ResonanceNodeScreen.scrollIntervalField(widgets, field.getX() + 1, field.getY() + 1, 0, 1));
            assertEquals("2", draft.interval);
            assertFalse(ResonanceNodeScreen.scrollIntervalField(widgets, body.right() + 1, body.bottom() + 1, 0, 1));
            assertFalse(
                    widgets.stream().anyMatch(w -> w.getMessage().getString().equals("…")));
        }
    }

    @Test
    void threeColumnCommonControlsFitTheShortRegularWindowWithoutScrolling() {
        var widgets = new ArrayList<AbstractWidget>();
        var actions = new NodeResourcePolicyView.Actions(
                () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {});
        var body = TerminalLayout.calculate(427, 240).content();
        var form = ResonanceNodeScreen.buildResourceForm(
                font(), body, 0, draft(), false, widgets::add, Component.literal("Faces"), actions);
        assertEquals(form.totalRows(), form.visibleRows());
        assertTrue(widgets.stream().anyMatch(w -> w.getMessage().getString().equals("Faces")));
        assertTrue(widgets.stream()
                        .filter(w -> w.getY() == widgets.getFirst().getY())
                        .count()
                >= 3);
    }

    @Test
    void saveStaysAtBottomWhileResourceRowsScroll() {
        var draft = draft();
        draft.addType(ResourceTypes.ITEM);
        var body = TerminalLayout.calculate(320, 240).content();
        var actions = new NodeResourcePolicyView.Actions(
                () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {});
        for (int scroll : new int[] {0, 1000}) {
            var widgets = new ArrayList<AbstractWidget>();
            ResonanceNodeScreen.buildResourceForm(
                    font(), body, scroll, draft, false, widgets::add, Component.empty(), actions);
            var save = widgets.stream()
                    .filter(w -> w.getMessage().getString().equals("omniresonance.node_menu.save"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(body.bottom() - 8, save.getBottom());
            assertEquals(body.right() - 8, save.getRight());
        }
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
        var body = TerminalLayout.calculate(320, 240).content();
        int offset = 0;
        for (int i = 0; i < 30; i++) offset = ResonanceNodeScreen.scrollResourceForm(body, offset, draft, -1);
        var layout = NodeResourcePolicyView.layout(body, offset, draft);
        assertEquals(layout.totalRows(), layout.firstRow() + layout.visibleRows());
        assertEquals(2, draft.settingIds().size());
    }
}
