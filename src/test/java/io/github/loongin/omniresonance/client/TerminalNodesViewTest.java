// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NodeDirectoryPage;
import io.github.loongin.omniresonance.networking.NodeDirectoryRequest;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

class TerminalNodesViewTest {
    private static final UUID SESSION = new UUID(2, 2), NODE = new UUID(3, 3), CHANNEL = new UUID(4, 4);

    private static Font font() {
        return new Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false) {
            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.min(text.length(), Math.max(0, width)));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean reverse) {
                return plainSubstrByWidth(text, width);
            }

            @Override
            public int width(String text) {
                return text.length();
            }

            @Override
            public int width(FormattedText text) {
                return text.getString().length();
            }

            @Override
            public List<FormattedCharSequence> split(FormattedText text, int width) {
                return List.of(Component.literal(text.getString()).getVisualOrderText());
            }
        };
    }

    private static NodeDirectoryPage.Row row(boolean enabled) {
        return new NodeDirectoryPage.Row(
                new NodeMenuNodeSummary(
                        new UUID(5, 5),
                        "Network",
                        NODE,
                        "Node A",
                        0,
                        ResourceLocation.parse("minecraft:overworld"),
                        new BlockPos(1, 80, 2),
                        NodeForm.BLOCK,
                        Direction.NORTH,
                        enabled,
                        false,
                        NodeMode.DIRECT),
                1,
                enabled ? NodeDirectoryRequest.Status.ONLINE : NodeDirectoryRequest.Status.DISABLED,
                4,
                1);
    }

    private static NodeDirectoryPage page(long sequence, boolean selected, boolean enabled, boolean editing) {
        return new NodeDirectoryPage(
                SESSION,
                1,
                sequence,
                true,
                false,
                editing,
                List.of(row(enabled)),
                false,
                false,
                selected ? row(enabled) : null,
                selected && enabled
                        ? List.of(new NodeDirectoryPage.Card(
                                CHANNEL, "Channel", "Tunnel", true, true, "*", "", 20, 16, "IGNORE", 4, 0))
                        : List.of(),
                0,
                selected && enabled ? 1 : 0,
                List.of(ResourceLocation.parse("minecraft:overworld")),
                List.of(ResourceLocation.parse("minecraft:item")));
    }

    private static TerminalButton key(List<AbstractWidget> widgets, String key) {
        return (TerminalButton) widgets.stream()
                .filter(w -> w.getMessage().getContents() instanceof TranslatableContents value
                        && value.getKey().equals("omniresonance.nodes." + key))
                .findFirst()
                .orElseThrow();
    }

    private static TerminalButton node(List<AbstractWidget> widgets) {
        return (TerminalButton) widgets.stream()
                .filter(w -> w.getMessage().getString().equals("Node A"))
                .findFirst()
                .orElseThrow();
    }

    private static TerminalButton card(List<AbstractWidget> widgets) {
        return (TerminalButton) widgets.stream()
                .filter(w -> w.getMessage().getString().startsWith("Channel"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void compactTerminalKeepsNodeControlsInsideTheSharedFrame() {
        for (int width : new int[] {320, 427, 640}) {
            var body = NetworkSetupScreen.managementLayout(width, 240).content();
            var widgets = new ArrayList<AbstractWidget>();
            var view =
                    new TerminalNodesView(new UUID(1, 1), SESSION, 1, ignored -> {}, (node, teleport) -> {}, () -> {});
            view.open();
            view.build(font(), body, widgets::add, widgets::remove, ignored -> {});
            view.accept(page(1, false, true, false));
            node(widgets).onPress();
            view.accept(page(2, true, true, false));
            for (String name : List.of("disable", "rename", "highlight", "teleport")) {
                var control = key(widgets, name);
                assertTrue(control.getX() >= body.x() && control.getRight() <= body.right());
                assertTrue(control.getY() >= body.y() && control.getBottom() <= body.bottom());
                assertTrue(control.getWidth() >= 60);
            }
            assertTrue(node(widgets).getRight() < view.editorBounds().x());
        }
    }

    @Test
    void browsingActionsTileEveryRowWithoutReservedEmptyColumns() {
        for (int width : new int[] {184, 219, 244, 288, 424, 700}) {
            for (boolean enabled : new boolean[] {true, false}) {
                var widgets = new ArrayList<AbstractWidget>();
                var view = new TerminalNodesView(
                        new UUID(1, 1), SESSION, 1, ignored -> {}, (node, teleport) -> {}, () -> {});
                view.open();
                view.build(
                        font(),
                        new TerminalLayout.Rect(0, 0, width, 360),
                        widgets::add,
                        widgets::remove,
                        ignored -> {});
                view.accept(page(1, false, enabled, false));
                node(widgets).onPress();
                view.accept(page(2, true, enabled, false));
                var controls = enabled
                        ? List.of(
                                key(widgets, "disable"),
                                key(widgets, "rename"),
                                key(widgets, "highlight"),
                                key(widgets, "teleport"))
                        : List.of(key(widgets, "enable"), key(widgets, "highlight"), key(widgets, "teleport"));
                var pane = view.editorBounds();
                for (var control : controls) {
                    var row = controls.stream()
                            .filter(other -> other.getY() == control.getY())
                            .toList();
                    assertEquals(pane.x() + 8, row.getFirst().getX());
                    assertEquals(pane.right() - 8, row.getLast().getRight());
                    assertEquals(20, control.getHeight());
                    for (int j = 1; j < row.size(); j++) {
                        assertEquals(6, row.get(j).getX() - row.get(j - 1).getRight());
                        assertTrue(
                                Math.abs(row.get(j).getWidth() - row.get(j - 1).getWidth()) <= 1);
                    }
                }
            }
        }
    }

    @Test
    void nodeConfirmationUsesSharedContentSizedDialog() {
        var widgets = new ArrayList<AbstractWidget>();
        var requests = new ArrayList<NodeDirectoryRequest>();
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
        var body = new TerminalLayout.Rect(0, 0, 700, 360);
        view.open();
        view.build(font(), body, widgets::add, widgets::remove, ignored -> {});
        view.accept(page(1, false, true, false));
        node(widgets).onPress();
        view.accept(page(2, true, true, false));
        key(widgets, "disable").onPress();
        var expected = TerminalActionLayout.of(TerminalDialogLayout.confirmation(
                        body, font(), Component.translatable("omniresonance.nodes.disable_notice")))
                .primary();
        var confirm = key(widgets, "confirm");
        assertEquals(expected.x(), confirm.getX());
        assertEquals(expected.y(), confirm.getY());
        key(widgets, "cancel").onPress();
        key(widgets, "rename").onPress();
        view.accept(page(3, true, true, true));
        var field = (TerminalEditBox) widgets.stream()
                .filter(TerminalEditBox.class::isInstance)
                .findFirst()
                .orElseThrow();
        field.setValue("Changed");
        key(widgets, "save").onPress();
        assertFalse(view.requestClose(), "Already submitted changes must not prompt for a second discard");
    }

    @Test
    void enterAndIconUseTheSameSearchLifecycleAndCanClosePendingCatalogs() {
        for (boolean keyboard : new boolean[] {true, false}) {
            var requests = new ArrayList<NodeDirectoryRequest>();
            var widgets = new ArrayList<AbstractWidget>();
            var view =
                    new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
            view.open();
            view.build(font(), new TerminalLayout.Rect(0, 0, 700, 360), widgets::add, widgets::remove, ignored -> {});
            view.accept(page(1, false, true, false));
            if (keyboard) assertTrue(view.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0));
            else
                ((TerminalSearchButton) widgets.stream()
                                .filter(w -> w instanceof TerminalSearchButton)
                                .findFirst()
                                .orElseThrow())
                        .onPress();
            assertTrue(view.searchExpanded());
            assertEquals(NodeDirectoryRequest.Action.CATALOG, requests.getLast().action());
            assertTrue(view.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0));
            assertFalse(view.searchExpanded());
            assertEquals(NodeDirectoryRequest.Action.QUERY, requests.getLast().action());
            int count = requests.size();
            view.tick();
            assertEquals(count, requests.size(), "Closing must not schedule a duplicate query");
            view.accept(new NodeDirectoryPage(
                    SESSION,
                    1,
                    2,
                    true,
                    false,
                    false,
                    List.of(row(true)),
                    false,
                    false,
                    null,
                    List.of(),
                    0,
                    0,
                    List.of(),
                    List.of(),
                    null,
                    new NodeDirectoryPage.Catalog(1, 0, 1)));
            assertFalse(view.searchExpanded());
        }
    }

    @Test
    void catalogSearchWaitsForAllBatchesAndUsesTheOptionalMatcher() {
        var catalog = new NodeSearchCatalog();
        var first = row(true);
        catalog.accept(new NodeDirectoryPage.Catalog(1, 0, 2), List.of(first));
        assertFalse(catalog.ready());
        assertTrue(catalog.filter("Node").isEmpty());
        var n = first.node();
        var second = new NodeDirectoryPage.Row(
                new NodeMenuNodeSummary(
                        n.networkId(),
                        n.networkName(),
                        new UUID(7, 7),
                        "测试节点",
                        0,
                        n.dimension(),
                        n.position(),
                        n.form(),
                        n.facing(),
                        true,
                        false,
                        n.mode()),
                2,
                first.status(),
                0,
                0);
        catalog.accept(new NodeDirectoryPage.Catalog(1, 1, 2), List.of(second));
        assertTrue(catalog.ready());
        try {
            ClientTextSearch.install(
                    (text, query) -> text.contains(query) || text.contains("测试节点") && query.equals("csjd"));
            assertEquals(List.of(second), catalog.filter("csjd"));
            ClientTextSearch.usePlain();
            assertTrue(catalog.filter("csjd").isEmpty());
            assertEquals(List.of(second), catalog.filter("测试"));
        } finally {
            ClientTextSearch.usePlain();
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> catalog.accept(new NodeDirectoryPage.Catalog(2, 1, 2), List.of(second)));
        assertFalse(catalog.ready());
    }

    @Test
    void configurationSummaryOmitsDefaultsButKeepsFacesAndExceptions() {
        var defaults = new NodeDirectoryPage.Card(null, "", "", true, true, "*", "", 1, 0, "IGNORE", 4, 0);
        var lines = TerminalNodesView.cardLines(defaults);
        assertEquals(1, lines.size());
        assertEquals(
                "omniresonance.nodes.working_faces",
                ((TranslatableContents) lines.getFirst().getContents()).getKey());
        var custom = new NodeDirectoryPage.Card(
                CHANNEL, "Channel", "Tunnel", true, false, "minecraft:item", "?", 20, -3, "SIGNAL", 0, 2);
        var keys = TerminalNodesView.cardLines(custom).stream()
                .map(c -> ((TranslatableContents) c.getContents()).getKey())
                .toList();
        assertTrue(keys.containsAll(List.of(
                "omniresonance.nodes.route",
                "omniresonance.nodes.scope",
                "omniresonance.nodes.preset",
                "omniresonance.nodes.interval",
                "omniresonance.nodes.priority",
                "omniresonance.nodes.redstone",
                "omniresonance.nodes.no_faces",
                "omniresonance.nodes.overrides")));
    }

    @Test
    void actionButtonsHaveFullHeightAndEqualColumnWidthsInSmallWindows() {
        var requests = new ArrayList<NodeDirectoryRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
        view.open();
        view.build(font(), new TerminalLayout.Rect(0, 28, 288, 172), widgets::add, widgets::remove, ignored -> {});
        view.accept(page(1, false, true, false));
        node(widgets).onPress();
        view.accept(page(2, true, true, false));
        var rename = key(widgets, "rename");
        var teleport = key(widgets, "teleport");
        assertEquals(20, rename.getHeight());
        assertEquals(20, teleport.getHeight());
        assertEquals(rename.getX(), teleport.getX());
        assertEquals(rename.getWidth(), teleport.getWidth());
    }

    @Test
    void loadingStatusSitsBelowActionsWithoutAnyClickableControl() {
        for (int width : new int[] {288, 700}) {
            var requests = new ArrayList<NodeDirectoryRequest>();
            var widgets = new ArrayList<AbstractWidget>();
            var view =
                    new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
            view.open();
            view.build(font(), new TerminalLayout.Rect(0, 0, width, 360), widgets::add, widgets::remove, ignored -> {});
            view.accept(page(1, false, true, false));
            node(widgets).onPress();
            view.accept(page(2, true, true, false));
            var teleport = key(widgets, "teleport");
            var status = view.chunkStatusBounds();
            assertTrue(status.y() >= teleport.getY() + teleport.getHeight() + 6);
            int x = status.x() + status.width() / 2, y = status.y() + status.height() / 2;
            assertFalse(widgets.stream()
                    .anyMatch(w -> x >= w.getX()
                            && x < w.getX() + w.getWidth()
                            && y >= w.getY()
                            && y < w.getY() + w.getHeight()));
            assertEquals(2, requests.size());
        }
    }

    @Test
    void narrowDetailScrollsInformationAndCardsWithoutMovingActions() {
        var widgets = new ArrayList<AbstractWidget>();
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, ignored -> {}, (node, teleport) -> {}, () -> {});
        view.open();
        view.build(font(), new TerminalLayout.Rect(0, 0, 288, 172), widgets::add, widgets::remove, ignored -> {});
        view.accept(page(1, false, true, false));
        node(widgets).onPress();
        view.accept(page(2, true, true, false));
        card(widgets).onPress();
        int actionY = key(widgets, "teleport").getY();
        int statusY = view.chunkStatusBounds().y();
        for (int i = 0; i < 20; i++) assertTrue(view.wheel(250, 120, -1));
        assertEquals(actionY, key(widgets, "teleport").getY());
        assertTrue(view.chunkStatusBounds().y() < statusY);
        var edit = key(widgets, "edit_configuration");
        assertTrue(edit.getY() >= actionY + 26);
        assertTrue(edit.getY() + edit.getHeight() <= 166);
        for (int i = 0; i < 20; i++) view.wheel(250, 120, 1);
        assertEquals(statusY, view.chunkStatusBounds().y());
    }

    @Test
    void basicInformationIncludesLocationModeAndStatusForDisabledNodesToo() {
        for (boolean enabled : new boolean[] {true, false}) {
            var fields = TerminalNodesView.basicFields(row(enabled));
            assertEquals(5, fields.size());
            assertEquals(
                    "omniresonance.nodes.dimension",
                    ((TranslatableContents) fields.get(0).label().getContents()).getKey());
            assertEquals("1, 80, 2", fields.get(1).value().getString());
            assertEquals(
                    "omniresonance.node_menu.mode.direct",
                    ((TranslatableContents) fields.get(2).value().getContents()).getKey());
            assertEquals(
                    "omniresonance.nodes.status." + (enabled ? "online" : "disabled"),
                    ((TranslatableContents) fields.get(3).value().getContents()).getKey());
            assertEquals(
                    "omniresonance.nodes.loading_state.off",
                    ((TranslatableContents) fields.get(4).value().getContents()).getKey());
        }
    }

    @Test
    void openingDoesNotSelectAndExplicitSelectionKeepsCardsCollapsed() {
        var requests = new ArrayList<NodeDirectoryRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
        view.open();
        view.build(font(), new TerminalLayout.Rect(0, 0, 700, 360), widgets::add, widgets::remove, ignored -> {});
        view.accept(page(1, false, true, false));
        assertEquals(NodeDirectoryRequest.Action.QUERY, requests.getLast().action());
        assertEquals(1, requests.size());
        node(widgets).onPress();
        assertEquals(NODE, requests.getLast().node());
        view.accept(page(2, true, true, false));
        assertFalse(widgets.stream()
                .anyMatch(w -> w.getMessage().getContents() instanceof TranslatableContents t
                        && t.getKey().endsWith("edit_configuration")));
        card(widgets).onPress();
        assertTrue(key(widgets, "edit_configuration").active);
        view.accept(page(2, true, true, false));
        assertEquals(key(widgets, "rename").getY(), key(widgets, "teleport").getY());
        var expandedTitle = widgets.stream()
                .filter(w -> w.getMessage().getString().startsWith("Channel"))
                .findFirst()
                .orElseThrow()
                .getMessage();
        assertEquals(
                "Channel · "
                        + Component.translatable("omniresonance.nodes.input").getString(),
                expandedTitle.getString());
        ((TerminalButton) widgets.stream()
                        .filter(w -> w.getMessage().getString().startsWith("Channel"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        assertEquals(
                expandedTitle,
                widgets.stream()
                        .filter(w -> w.getMessage().getString().startsWith("Channel"))
                        .findFirst()
                        .orElseThrow()
                        .getMessage());
        view.accept(page(2, true, true, false));
        assertFalse(widgets.stream()
                .anyMatch(w -> w.getMessage().getContents() instanceof TranslatableContents value
                        && value.getKey().equals("omniresonance.nodes.edit_configuration")));
        assertEquals(2, requests.size());
    }

    @Test
    void minimumWindowShowsSixNameOnlyRowsAndRetainsCoordinatesInTooltip() {
        var requests = new ArrayList<NodeDirectoryRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
        var rows = new ArrayList<NodeDirectoryPage.Row>();
        for (int i = 1; i <= 6; i++)
            rows.add(new NodeDirectoryPage.Row(
                    new NodeMenuNodeSummary(
                            new UUID(5, 5),
                            "Network",
                            new UUID(3, i),
                            "Node " + i,
                            0,
                            ResourceLocation.parse("minecraft:overworld"),
                            new BlockPos(i, 80, 2),
                            NodeForm.BLOCK,
                            Direction.NORTH,
                            true,
                            false,
                            NodeMode.DIRECT),
                    i,
                    NodeDirectoryRequest.Status.ONLINE,
                    4,
                    1));
        view.open();
        view.build(font(), new TerminalLayout.Rect(0, 0, 288, 172), widgets::add, widgets::remove, ignored -> {});
        view.accept(new NodeDirectoryPage(
                SESSION, 1, 1, true, false, false, rows, false, false, null, List.of(), 0, 0, List.of(), List.of()));
        assertEquals(
                6,
                widgets.stream()
                        .filter(w -> w.getMessage().getString().startsWith("Node "))
                        .count());
        var tooltip = TerminalNodesView.nodeTooltip(rows.getFirst());
        assertTrue(tooltip.getSiblings().stream()
                .anyMatch(c -> c.getContents() instanceof TranslatableContents t
                        && t.getKey().equals("omniresonance.chunk_overview.coordinates")));
    }

    @Test
    void toolbarHasSearchOnlyAtEveryWindowSize() {
        for (int width : new int[] {288, 700}) {
            var requests = new ArrayList<NodeDirectoryRequest>();
            var widgets = new ArrayList<AbstractWidget>();
            var view =
                    new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
            view.open();
            view.build(font(), new TerminalLayout.Rect(0, 0, width, 172), widgets::add, widgets::remove, ignored -> {});
            view.accept(page(1, false, true, false));
            assertEquals(2, widgets.size());
            assertFalse(widgets.stream()
                    .anyMatch(w -> w.getMessage().getContents() instanceof TranslatableContents value
                            && value.getKey().startsWith("omniresonance.nodes.filters")));
        }
    }

    @Test
    void searchPreservesInputAndUsesLocalCatalogWithoutSendingTheQuery() {
        var requests = new ArrayList<NodeDirectoryRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        net.minecraft.client.gui.components.events.GuiEventListener[] focused = {null};
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
        view.open();
        view.build(
                font(),
                new TerminalLayout.Rect(0, 0, 700, 360),
                widgets::add,
                widgets::remove,
                value -> focused[0] = value);
        view.accept(page(1, false, true, false));
        assertFalse(view.searchExpanded());
        ((TerminalSearchButton) widgets.stream()
                        .filter(w -> w instanceof TerminalSearchButton)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        view.finishClick(false);
        assertTrue(focused[0] instanceof TerminalSearchBox);
        ((TerminalSearchBox) focused[0]).setValue("Needle");
        view.tick();
        assertEquals("", requests.getLast().query());
        assertEquals(NodeDirectoryRequest.Action.CATALOG, requests.getLast().action());
        var input = (TerminalSearchBox) focused[0];
        view.accept(new NodeDirectoryPage(
                SESSION,
                1,
                requests.getLast().sequence(),
                true,
                false,
                false,
                List.of(row(true)),
                false,
                false,
                null,
                List.of(),
                0,
                0,
                List.of(),
                List.of(),
                null,
                new NodeDirectoryPage.Catalog(1, 0, 1)));
        org.junit.jupiter.api.Assertions.assertSame(
                input,
                widgets.stream()
                        .filter(w -> w instanceof TerminalSearchBox)
                        .findFirst()
                        .orElseThrow());
        assertEquals("Needle", input.getValue());
        assertFalse(widgets.stream().anyMatch(w -> w.getMessage().getString().equals("Node A")));
        int count = requests.size();
        input.setValue("node a");
        view.tick();
        assertEquals(count, requests.size());
        assertTrue(widgets.stream().anyMatch(w -> w.getMessage().getString().equals("Node A")));
    }

    @Test
    void prototypeSelectionExposesActionsAndExistingConfigurationCardsOnly() {
        var requests = new ArrayList<NodeDirectoryRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
        view.open();
        view.build(font(), new TerminalLayout.Rect(0, 0, 700, 360), widgets::add, widgets::remove, ignored -> {});
        view.accept(page(1, false, true, false));
        assertFalse(widgets.stream().anyMatch(w -> w instanceof TerminalSearchBox));
        ((TerminalButton) widgets.stream()
                        .filter(w -> w.getMessage().getString().equals("Node A"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        assertEquals(NodeDirectoryRequest.Action.SELECT, requests.getLast().action());
        view.accept(page(2, true, true, false));
        assertTrue(key(widgets, "rename").active);
        assertTrue(key(widgets, "highlight").active);
        assertFalse(key(widgets, "teleport").active, "No local operator must leave teleport disabled");
        card(widgets).onPress();
        key(widgets, "edit_configuration").onPress();
        assertEquals(
                NodeDirectoryRequest.Action.EDIT_CONFIGURATION,
                requests.getLast().action());
        assertEquals(CHANNEL, requests.getLast().channel());
    }

    @Test
    void narrowDisabledNodeKeepsNavigationBesideVisibleList() {
        var requests = new ArrayList<NodeDirectoryRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        var view = new TerminalNodesView(new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> {});
        view.open();
        view.build(font(), new TerminalLayout.Rect(0, 0, 288, 172), widgets::add, widgets::remove, ignored -> {});
        view.accept(page(1, false, false, false));
        ((TerminalButton) widgets.stream()
                        .filter(w -> w.getMessage().getString().equals("Node A"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        view.accept(page(2, true, false, false));
        assertTrue(key(widgets, "enable").active);
        assertTrue(key(widgets, "highlight").active);
        assertFalse(key(widgets, "teleport").active, "No local operator must leave teleport disabled");
        assertFalse(widgets.stream()
                .anyMatch(w -> w.getMessage().getContents() instanceof TranslatableContents value
                        && value.getKey().equals("omniresonance.nodes.rename")));
        assertFalse(view.back());
        assertTrue(widgets.stream().anyMatch(w -> w.getMessage().getString().equals("Node A")));
    }

    @Test
    void renameRequiresLeaseResponseAndDirtyExitUsesSeparateConfirmation() {
        var requests = new ArrayList<NodeDirectoryRequest>();
        var widgets = new ArrayList<AbstractWidget>();
        int[] closed = {0};
        var view = new TerminalNodesView(
                new UUID(1, 1), SESSION, 1, requests::add, (node, teleport) -> {}, () -> closed[0]++);
        view.open();
        view.build(font(), new TerminalLayout.Rect(0, 0, 700, 360), widgets::add, widgets::remove, ignored -> {});
        view.accept(page(1, false, true, false));
        ((TerminalButton) widgets.stream()
                        .filter(w -> w.getMessage().getString().equals("Node A"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        view.accept(page(2, true, true, false));
        key(widgets, "rename").onPress();
        assertEquals(
                NodeDirectoryRequest.Action.BEGIN_RENAME, requests.getLast().action());
        view.accept(page(3, true, true, true));
        ((TerminalEditBox) widgets.stream()
                        .filter(w -> w instanceof TerminalEditBox)
                        .findFirst()
                        .orElseThrow())
                .setValue("Changed");
        assertTrue(view.requestClose());
        assertEquals(0, closed[0]);
        key(widgets, "discard").onPress();
        assertEquals(1, closed[0]);
    }
}
