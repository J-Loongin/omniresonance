// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.FilterPresetPage;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodePresetPickerTest {
    @Test
    void pickerUsesTheSharedMatcherForItsActualVisibleChoices() {
        var picker = new NodePresetPicker();
        picker.open();
        picker.search().open();
        assertEquals("", picker.nextRequest().query());
        picker.complete(
                new FilterPresetPage(List.of(new FilterPresetSummary(new UUID(204, 1), "测试预设", 0, 0, true)), 0, 1, 1));
        try {
            ClientTextSearch.install((name, query) -> name.equals("测试预设") && query.equals("csys"));
            picker.edit("csys");
            assertEquals(2, picker.count());
            assertNull(picker.nextRequest());
            assertEquals("测试预设", picker.page().entries().getFirst().name());
            ClientTextSearch.usePlain();
            picker.edit("");
            picker.edit("csys");
            assertEquals(1, picker.count());
        } finally {
            ClientTextSearch.usePlain();
        }
    }

    @Test
    void pickerSearchStartsCollapsedAndDoesNotReserveAnInputRow() {
        var picker = new NodePresetPicker();
        picker.open();
        var font = new net.minecraft.client.gui.Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false);
        for (int[] size : new int[][] {{320, 240}, {427, 240}}) {
            var body = TerminalLayout.calculate(size[0], size[1]).content();
            assertNull(NodePresetPickerView.buildSearch(font, body, picker, () -> {}));
            assertEquals(NodeRoutingView.tunnelList(body, false, 0, 0).rows(), NodePresetPickerView.rows(body));
        }
    }

    @Test
    void liveSearchFieldKeepsFocusCaretAndKeyOwnershipAcrossQueriesAndWheelReplies() {
        var picker = new NodePresetPicker();
        picker.open();
        picker.search().open();
        var host = new PickerHost(picker);
        var field = host.field;
        field.setValue("iron");
        host.setFocused(field);
        field.setCursorPosition(2);
        field.setHighlightPos(1);
        assertEquals("", picker.nextRequest().query());
        picker.complete(page(0, 257, 4));
        host.refresh();
        assertEquals(2, field.getCursorPosition());
        assertEquals("r", field.getHighlighted());
        assertTrue(host.charTyped('e', 0));
        assertEquals("ieon", field.getValue());
        assertEquals(128, picker.nextRequest().offset());
        picker.complete(page(128, 257, 4));
        picker.nextRequest();
        picker.complete(page(256, 257, 4));
        host.refresh();
        assertTrue(host.getFocused() == field);
        assertTrue(picker.ready());
        assertNull(picker.nextRequest());
        field.setValue("");
        for (int i = 0; i < 140; i++) picker.wheel(-1, 10);
        host.refresh();
        assertTrue(host.getFocused() == field);
        assertNull(picker.nextRequest());
        assertTrue(field.ownsKey(org.lwjgl.glfw.GLFW.GLFW_KEY_E));
    }

    @Test
    void collapseKeepsAuthorizedCatalogDownloadAndRestoresUnfilteredResultsWithoutReopening() {
        var picker = new NodePresetPicker();
        picker.open();
        picker.search().open();
        picker.edit("iron");
        assertEquals("", picker.nextRequest().query());
        assertTrue(picker.closeSearch());
        assertFalse(picker.search().expanded());
        assertTrue(picker.complete(page(0, 1, 4)));
        assertTrue(picker.ready());
        assertEquals(2, picker.count());
        assertNull(picker.nextRequest());
        assertFalse(picker.search().expanded());
        assertFalse(picker.closeSearch());
        picker.close();
        assertNull(picker.page());
    }

    @Test
    void screenSearchButtonAndKeyRoutesReuseTheCommonSearchStateAndGeometry() {
        var picker = new NodePresetPicker();
        picker.open();
        var search = picker.search();
        var events = new java.util.ArrayList<String>();
        for (int[] size : new int[][] {{320, 240}, {427, 240}}) {
            var window = TerminalLayout.calculate(size[0], size[1]).window();
            var header = TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(window), true);
            var button = ResonanceNodeScreen.buildSearchButton(header.action(), search, true, () -> {
                if (!picker.closeSearch()) search.open();
            });
            assertEquals(TerminalSearchButton.class, button.getClass());
            assertEquals(20, button.getWidth());
            assertEquals(header.action().right(), button.getRight());
            assertTrue(header.remaining().right() + TerminalLayout.GAP <= button.getX());
            button.onPress();
            assertTrue(search.expanded());
            var host = new PickerHost(picker);
            host.setFocused(button);
            search.finishToggleClick(false, host, host.field);
            assertTrue(host.getFocused() == host.field);
            assertEquals(NodeRoutingView.tunnelSearchBounds(host.body).x(), host.field.getX());
            assertEquals(
                    NodeRoutingView.tunnelList(host.body, true, 0, 0).rows(),
                    NodePresetPickerView.rows(host.body, true));
            assertTrue(ResonanceNodeScreen.routeSearchKey(
                    org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,
                    0,
                    0,
                    host.field,
                    true,
                    () -> events.add("close"),
                    search,
                    true,
                    () -> {
                        search.toggle(0);
                        events.add("toggle");
                    }));
            assertEquals("toggle", events.removeLast());
            assertFalse(search.expanded());
            button.onPress();
            button.onPress();
            host.setFocused(button);
            search.finishToggleClick(true, host, null);
            assertNull(host.getFocused());
            assertNull(NodePresetPickerView.buildSearch(
                    new net.minecraft.client.gui.Font(
                            id -> {
                                throw new AssertionError("No rendering");
                            },
                            false),
                    host.body,
                    picker,
                    () -> {}));
        }
        for (int key : new int[] {org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER}) {
            assertFalse(ResonanceNodeScreen.routeSearchKey(
                    key,
                    0,
                    org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL,
                    null,
                    false,
                    () -> events.add("close"),
                    search,
                    true,
                    () -> {
                        search.toggle(0);
                        events.add("toggle");
                    }));
            assertFalse(ResonanceNodeScreen.routeSearchKey(
                    key,
                    0,
                    org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT,
                    null,
                    false,
                    () -> events.add("close"),
                    search,
                    true,
                    () -> {
                        search.toggle(0);
                        events.add("toggle");
                    }));
            assertFalse(ResonanceNodeScreen.routeSearchKey(
                    key, 0, 0, null, false, () -> events.add("close"), search, false, () -> {
                        search.toggle(0);
                        events.add("toggle");
                    }));
            assertTrue(ResonanceNodeScreen.routeSearchKey(
                    key, 0, 0, null, true, () -> events.add("close"), search, true, () -> {
                        search.toggle(0);
                        events.add("toggle");
                    }));
            assertEquals("close", events.removeLast());
            assertFalse(search.expanded());
            assertTrue(ResonanceNodeScreen.routeSearchKey(
                    key, 0, 0, null, false, () -> events.add("close"), search, true, () -> {
                        search.toggle(0);
                        events.add("toggle");
                    }));
            assertEquals("toggle", events.removeLast());
            assertTrue(search.expanded());
            picker.closeSearch();
        }
    }

    private static final class PickerHost extends net.minecraft.client.gui.screens.Screen {
        private final TerminalLayout.Rect body = new TerminalLayout.Rect(0, 0, 400, 300);
        private final NodePresetPicker picker;
        private final TerminalResultRows rows =
                new TerminalResultRows(this, this::addRenderableWidget, this::removeWidget);
        private final TerminalSearchBox field;

        private PickerHost(NodePresetPicker picker) {
            super(net.minecraft.network.chat.Component.empty());
            this.picker = picker;
            var metrics =
                    new net.minecraft.client.gui.Font(
                            id -> {
                                throw new AssertionError("No rendering");
                            },
                            false) {
                        @Override
                        public int width(String text) {
                            return text.length();
                        }

                        @Override
                        public String plainSubstrByWidth(String text, int width) {
                            return text.substring(0, Math.max(0, Math.min(width, text.length())));
                        }

                        @Override
                        public String plainSubstrByWidth(String text, int width, boolean tail) {
                            int length = Math.max(0, Math.min(width, text.length()));
                            return tail ? text.substring(text.length() - length) : text.substring(0, length);
                        }
                    };
            field = addRenderableWidget(NodePresetPickerView.buildSearch(metrics, body, picker, this::refresh));
        }

        private void refresh() {
            rows.clear();
            NodePresetPickerView.buildRows(body, picker, true, rows::add, preset -> {});
        }
    }

    @Test
    void editsSearchLocallyWhileOneCatalogBatchIsInFlight() {
        var picker = new NodePresetPicker();
        picker.open();
        picker.search().open();
        picker.edit("i");
        picker.edit("IRON");
        assertEquals("", picker.nextRequest().query());
        picker.edit("Preset 0");
        assertNull(picker.nextRequest());
        assertTrue(picker.complete(page(0, 1, 4)));
        assertTrue(picker.ready());
        assertEquals(2, picker.count());
        picker.edit("missing");
        assertEquals(1, picker.count());
        assertNull(picker.nextRequest());
        picker.edit("");
        assertEquals(2, picker.count());
        assertEquals(0, picker.scroll());
    }

    @Test
    void completeCatalogWheelCrossesLocalPagesWithoutNetworkRequests() {
        var picker = new NodePresetPicker();
        picker.open();
        for (int offset : new int[] {0, 128, 256}) {
            assertEquals(offset, picker.nextRequest().offset());
            assertNull(picker.nextRequest());
            picker.complete(page(offset, 257, 4));
        }
        assertTrue(picker.ready());
        for (int i = 0; i < 120; i++) picker.wheel(-1, 10);
        assertEquals(128, picker.page().offset());
        assertNull(picker.nextRequest());
        picker.wheel(1, 10);
        assertEquals(0, picker.page().offset());
        assertEquals(119, picker.scroll());
    }

    @Test
    void revisionRestartAndClosingNeverRestoreOldNavigation() {
        var picker = new NodePresetPicker();
        picker.open();
        picker.search().open();
        picker.nextRequest();
        picker.complete(page(0, 257, 4));
        for (int i = 0; i < 130; i++) picker.wheel(-1, 10);
        picker.nextRequest();
        picker.complete(page(0, 2, 5));
        assertEquals(0, picker.scroll());
        picker.edit("stale");
        picker.nextRequest();
        picker.close();
        assertFalse(picker.complete(page(0, 1, 5)));
        assertNull(picker.nextRequest());
        picker.open();
        picker.search().open();
        assertEquals("", picker.nextRequest().query());
    }

    @Test
    void pickerViewContainsOnlyChoiceRowsAndUsesRemainingHeight() {
        var picker = new NodePresetPicker();
        picker.open();
        picker.search().open();
        picker.nextRequest();
        picker.complete(page(0, 1, 4));
        var body = new TerminalLayout.Rect(0, 0, 400, 300);
        var bounds = NodePresetPickerView.rows(body);
        assertEquals(300, bounds.bottom());
        var widgets = new ArrayList<TerminalRowButton>();
        var choices = new ArrayList<FilterPresetSummary>();
        NodePresetPickerView.buildRows(body, picker, true, widgets::add, choices::add);
        assertEquals(2, widgets.size());
        widgets.getFirst().onPress();
        widgets.getLast().onPress();
        assertNull(choices.getFirst());
        assertEquals(new UUID(204, 0), choices.getLast().id());
    }

    private static FilterPresetPage page(int offset, int total, long revision) {
        List<FilterPresetSummary> entries = new ArrayList<>();
        for (int i = offset; i < Math.min(total, offset + 128); i++)
            entries.add(new FilterPresetSummary(new UUID(204, i), "Preset " + i, 0, 0, true));
        return new FilterPresetPage(entries, offset, total, revision);
    }
}
