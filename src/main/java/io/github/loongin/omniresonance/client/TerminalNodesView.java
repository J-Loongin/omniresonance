// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeDirectoryPage;
import io.github.loongin.omniresonance.networking.NodeDirectoryRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** Searchable node browser with persistent side-by-side panes and explicit node operations. */
final class TerminalNodesView {
    private enum Modal {
        NONE,
        RENAME,
        DISABLE,
        DISCARD
    }

    private final UUID view, session;
    private final long generation;
    private final Consumer<NodeDirectoryRequest> sender;
    private final BiConsumer<UUID, Boolean> navigation;
    private final Runnable closeRoot;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final ClientSearchState search = new ClientSearchState();
    private Font font;
    private Consumer<AbstractWidget> add;
    private Consumer<GuiEventListener> remove;
    private Consumer<GuiEventListener> focus;
    private TerminalLayout.Rect body;
    private TerminalLayout.Rect headerSearchBounds;
    private boolean headerSearchHidden;
    private TerminalNodeLayout layout;
    private TerminalLayout.Rect chunkStatusBounds = new TerminalLayout.Rect(0, 0, 0, 0);

    TerminalLayout.Rect chunkStatusBounds() {
        return chunkStatusBounds;
    }

    private @Nullable NodeDirectoryPage page;
    private @Nullable UUID selected, expanded;
    private Modal modal = Modal.NONE;
    private @Nullable TerminalEditBox nameField;
    private @Nullable AbstractWidget teleportButton;
    private @Nullable TerminalSearchBox searchField;
    private final NodeSearchCatalog catalog = new NodeSearchCatalog();
    private boolean catalogFailed;
    private long catalogStartedTick;
    private String query = "", nameDraft = "", originalName = "";
    private boolean pending, closeAfterDiscard, landAtEnd, detailAtEnd;
    private int listScroll, detailScroll, detailMaximum, rowHeight = 26, detailOffset;
    private long sequence, ticks, anchor;
    private boolean before;
    private NodeDirectoryRequest.Action lastAction = NodeDirectoryRequest.Action.QUERY;

    private record ConfigurationCard(
            NodeDirectoryPage.Card card, List<FormattedCharSequence> lines, int height, boolean expanded) {}
    // Only this bounded page's cards; rebuilt with the page, width or expansion and never measured each frame.
    private List<ConfigurationCard> configurationCards = List.of();

    TerminalNodesView(
            UUID view,
            UUID session,
            long generation,
            Consumer<NodeDirectoryRequest> sender,
            BiConsumer<UUID, Boolean> navigation,
            Runnable closeRoot) {
        this.view = view;
        this.session = session;
        this.generation = generation;
        this.sender = sender;
        this.navigation = navigation;
        this.closeRoot = closeRoot;
    }

    TerminalLayout.Rect editorBounds() {
        return layout.detail();
    }

    void suspendHeaderSearch() {
        headerSearchHidden = true;
        for (var widget : widgets) if (widget instanceof TerminalSearchButton) widget.visible = false;
    }

    void open() {
        request(NodeDirectoryRequest.Action.QUERY, null);
    }

    void resume() {
        headerSearchHidden = false;
        catalog.clear();
        catalogFailed = false;
        pending = false;
        request(NodeDirectoryRequest.Action.QUERY, null);
        rebuild();
    }

    void build(
            Font font,
            TerminalLayout.Rect body,
            Consumer<AbstractWidget> add,
            Consumer<GuiEventListener> remove,
            Consumer<GuiEventListener> focus) {
        build(font, body, new TerminalLayout.Rect(body.right() - 20, body.y() - 28, 20, 20), add, remove, focus);
    }

    void build(
            Font font,
            TerminalLayout.Rect body,
            TerminalLayout.Rect headerBounds,
            Consumer<AbstractWidget> add,
            Consumer<GuiEventListener> remove,
            Consumer<GuiEventListener> focus) {
        headerSearchBounds = headerBounds;
        this.font = font;
        this.body = body;
        this.add = add;
        this.remove = remove;
        this.focus = focus;
        widgets.clear();
        rebuild();
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("omniresonance.nodes." + key, args);
    }

    private void add(AbstractWidget widget) {
        widgets.add(widget);
        add.accept(widget);
    }

    private TerminalButton button(TerminalLayout.Rect r, Component label, boolean active, Runnable click) {
        var b = new TerminalButton(r.x(), r.y(), r.width(), r.height(), label, ignored -> click.run(), false);
        b.active = active;
        b.setTooltip(Tooltip.create(TerminalText.body(label)));
        add(b);
        return b;
    }

    private boolean dirty() {
        return modal == Modal.RENAME && !nameDraft.equals(originalName);
    }

    void accept(NodeDirectoryPage value) {
        if (!value.session().equals(session) || value.generation() != generation || value.sequence() != sequence)
            return;
        if (lastAction == NodeDirectoryRequest.Action.CATALOG) {
            if (!pending && value.available()) return;
            pending = false;
            if (!value.available() || value.catalog() == null) {
                catalog.clear();
                catalogFailed = true;
            } else {
                try {
                    catalog.accept(value.catalog(), value.rows());
                } catch (IllegalArgumentException invalid) {
                    catalogFailed = true;
                }
                if (!catalogFailed && !catalog.ready()) {
                    request(NodeDirectoryRequest.Action.CATALOG, null);
                    return;
                }
            }
            if (!value.available() || page == null || !page.available()) page = value;
            rebuild();
            return;
        }
        if (!value.available()) catalog.clear();
        else {
            for (var row : value.rows()) catalog.update(row);
            if (value.selected() != null) catalog.update(value.selected());
        }
        boolean wasPending = pending;
        boolean preserveNameInput = !wasPending
                && modal == Modal.RENAME
                && value.editing()
                && value.selected() != null
                && page != null
                && page.selected() != null
                && value.selected().node().revision() == page.selected().node().revision();
        page = value;
        if (preserveNameInput) return;
        pending = false;
        if (value.selected() == null && selected != null) {
            selected = null;
            expanded = null;
            modal = Modal.NONE;
        }
        if (wasPending
                && lastAction == NodeDirectoryRequest.Action.BEGIN_RENAME
                && value.editing()
                && value.selected() != null) {
            nameDraft = originalName = value.selected().node().nodeName();
            modal = Modal.RENAME;
        }
        if (wasPending && lastAction == NodeDirectoryRequest.Action.RENAME && !value.rejected() && !value.editing())
            modal = Modal.NONE;
        if (wasPending && lastAction == NodeDirectoryRequest.Action.CANCEL_EDIT) modal = Modal.NONE;
        if (landAtEnd) {
            listScroll = Integer.MAX_VALUE;
            landAtEnd = false;
        }
        if (detailAtEnd) {
            detailScroll = Integer.MAX_VALUE;
            detailAtEnd = false;
        }
        if (value.available() && search.expanded() && !catalog.ready() && !catalogFailed) {
            request(NodeDirectoryRequest.Action.CATALOG, null);
        }
        rebuild();
    }

    private void select(UUID node) {
        if (pending) return;
        selected = node;
        detailOffset = 0;
        detailScroll = 0;
        expanded = null;
        request(NodeDirectoryRequest.Action.SELECT, null);
        rebuild();
    }

    private void request(NodeDirectoryRequest.Action action, @Nullable UUID channel) {
        if (pending && action != NodeDirectoryRequest.Action.QUERY) return;
        pending = true;
        if (action == NodeDirectoryRequest.Action.CATALOG && catalog.size() == 0) catalogStartedTick = ticks;
        lastAction = action;
        sequence = Math.incrementExact(sequence);
        long revision =
                page != null && page.selected() != null ? page.selected().node().revision() : 0;
        sender.accept(new NodeDirectoryRequest(
                view,
                session,
                generation,
                sequence,
                action,
                "",
                NodeDirectoryRequest.Status.ALL,
                null,
                null,
                anchor,
                before,
                selected,
                action == NodeDirectoryRequest.Action.CATALOG ? catalog.size() : detailOffset,
                action == NodeDirectoryRequest.Action.CATALOG ? catalog.revision() : revision,
                nameDraft,
                page != null
                        && page.selected() != null
                        && !page.selected().node().enabled(),
                channel));
        for (var widget : widgets) if (widget != searchField) widget.active = false;
    }

    private void applyQuery() {
        selected = null;
        expanded = null;
        detailScroll = 0;
        detailOffset = 0;
        anchor = 0;
        before = false;
        listScroll = 0;
        if (search.expanded()) {
            if (!catalog.ready() && !pending && !catalogFailed) request(NodeDirectoryRequest.Action.CATALOG, null);
            rebuild();
        } else {
            catalog.clear();
            catalogFailed = false;
            request(NodeDirectoryRequest.Action.QUERY, null);
        }
    }

    private List<NodeDirectoryPage.Row> visibleNodes() {
        if (search.expanded()) return catalog.filter(query);
        return page == null ? List.of() : page.rows();
    }

    private void rebuild() {
        teleportButton = null;
        if (add == null) return;
        chunkStatusBounds = new TerminalLayout.Rect(0, 0, 0, 0);
        boolean focus = searchField != null && searchField.isFocused();
        TerminalResultRows.clearWidgets(widgets, remove, this.focus, searchField);
        nameField = null;
        if (!search.expanded() || modal != Modal.NONE) searchField = null;
        layout = TerminalNodeLayout.calculate(body, search.expanded());
        if (modal != Modal.NONE) {
            buildModal();
            return;
        }
        var bar = layout.toolbar();
        var searchToggle = new TerminalSearchButton(
                headerSearchBounds, search.expanded(), text("search"), ignored -> toggleSearch());
        searchToggle.visible = !headerSearchHidden;
        searchToggle.active = searchEligible();
        if (ClientTextSearch.failed())
            searchToggle.setTooltip(Tooltip.create(Component.translatable("omniresonance.search.jec_unavailable")));
        add(searchToggle);
        if (search.expanded()) {
            var searchBounds = TerminalSearchBox.bounds(bar, bar.y());
            searchField = search.field(font, searchBounds, text("search"), 256, value -> search.edit(value, ticks));
            add(searchField);
            if (focus) this.focus.accept(searchField);
        }
        if (search.expanded() && catalogFailed) {
            button(
                    new TerminalLayout.Rect(
                            layout.list().x() + 4,
                            layout.list().y() + 6,
                            layout.list().width() - 12,
                            20),
                    Component.translatable("omniresonance.terminal.retry"),
                    true,
                    () -> {
                        catalog.clear();
                        catalogFailed = false;
                        request(NodeDirectoryRequest.Action.CATALOG, null);
                        rebuild();
                    });
            return;
        }
        if (page == null || !page.available()) return;
        rowHeight = 26;
        listScroll = Math.clamp(listScroll, 0, Math.max(0, visibleNodes().size() - visibleRows()));
        {
            for (int i = 0; i < visibleRows() && i + listScroll < visibleNodes().size(); i++) {
                var row = visibleNodes().get(i + listScroll);
                var r = new TerminalLayout.Rect(
                        layout.list().x() + 4,
                        layout.list().y() + 6 + i * rowHeight,
                        TerminalLayout.reservedScrollContentWidth(layout.list().width(), 4),
                        20);
                var b = new TerminalButton(
                        r.x(),
                        r.y(),
                        r.width(),
                        20,
                        Component.literal(row.node().nodeName()),
                        ignored -> select(row.node().nodeId()),
                        false);
                b.setSelected(row.node().nodeId().equals(selected));
                b.active = !pending;
                b.setTooltip(Tooltip.create(TerminalText.body(nodeTooltip(row))));
                add(b);
            }
        }
        if (selected != null && page.selected() != null) buildDetail();
    }

    private int visibleRows() {
        return Math.max(1, (layout.list().height() - 12) / rowHeight);
    }

    private static Component status(NodeDirectoryRequest.Status status) {
        return text("status." + status.name().toLowerCase(Locale.ROOT));
    }

    private static Component dimension(ResourceLocation id) {
        return switch (id.toString()) {
            case "minecraft:overworld" -> Component.translatable("omniresonance.chunk_overview.dimension.overworld");
            case "minecraft:the_nether" -> Component.translatable("omniresonance.chunk_overview.dimension.nether");
            case "minecraft:the_end" -> Component.translatable("omniresonance.chunk_overview.dimension.end");
            default -> Component.literal(id.toString());
        };
    }

    private static Component faces(int mask) {
        if (mask == 0) return text("no_faces");
        var result = Component.empty();
        for (var d : net.minecraft.core.Direction.values())
            if ((mask & 1 << d.get3DDataValue()) != 0) {
                if (!result.getSiblings().isEmpty()) result.append("/");
                result.append(Component.translatable("omniresonance.working_faces.direction." + d.getName()));
            }
        return result;
    }

    static Component nodeTooltip(NodeDirectoryPage.Row row) {
        var result = Component.literal(row.node().nodeName());
        for (var line : fullRowLines(row)) result.append("\n").append(line);
        return result;
    }

    record BasicField(Component label, Component value) {}

    static List<BasicField> basicFields(NodeDirectoryPage.Row row) {
        var node = row.node();
        var pos = node.position();
        return List.of(
                new BasicField(text("dimension"), dimension(node.dimension())),
                new BasicField(
                        text("position_label"), Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())),
                new BasicField(
                        Component.translatable("omniresonance.node_menu.mode"),
                        Component.translatable(
                                "omniresonance.node_menu.mode." + node.mode().serializedName())),
                new BasicField(text("status"), status(row.status())),
                new BasicField(
                        text("loading_label"),
                        text(node.chunkLoadingRequested() ? "loading_on" : "loading_state.off")));
    }

    private int basicLabelWidth() {
        int width = 0;
        for (var field : basicFields(page.selected()))
            width = Math.max(width, font.width(TerminalText.body(field.label())));
        return Math.min(width, Math.max(1, (layout.detail().width() - 28) / 3));
    }

    private int basicValueWidth() {
        return Math.max(1, layout.detail().width() - 28 - basicLabelWidth() - 8);
    }

    private int basicFieldHeight(BasicField field) {
        return Math.max(
                        1,
                        font.split(TerminalText.body(field.value()), basicValueWidth())
                                .size())
                * 12;
    }

    private int basicTextHeight() {
        int height = 0;
        var fields = basicFields(page.selected());
        for (int i = 0; i < fields.size() - 1; i++) height += basicFieldHeight(fields.get(i));
        return height;
    }

    private int basicHeight() {
        var fields = basicFields(page.selected());
        return basicTextHeight() + basicFieldHeight(fields.getLast()) + 12;
    }

    private void renderBasicFields(GuiGraphics graphics, int y) {
        var rect = layout.detail();
        int labelWidth = basicLabelWidth();
        for (var field : basicFields(page.selected())) {
            graphics.drawString(
                    font,
                    TerminalText.body(Component.literal(
                            TerminalText.ellipsize(font, field.label().getString(), labelWidth))),
                    rect.x() + 12,
                    y,
                    TerminalTheme.MUTED,
                    false);
            int valueY = y;
            for (var line : font.split(TerminalText.body(field.value()), basicValueWidth())) {
                graphics.drawString(font, line, rect.x() + 12 + labelWidth + 8, valueY, TerminalTheme.TEXT, false);
                valueY += 12;
            }
            y += basicFieldHeight(field);
        }
    }

    private static List<Component> fullRowLines(NodeDirectoryPage.Row row) {
        var n = row.node();
        return List.of(
                status(row.status()).copy().append(" · ").append(dimension(n.dimension())),
                Component.translatable(
                        "omniresonance.chunk_overview.coordinates",
                        n.position().getX(),
                        n.position().getY(),
                        n.position().getZ()),
                faces(row.faces())
                        .copy()
                        .append(" · ")
                        .append(Component.translatable(
                                "omniresonance.node_menu.mode." + n.mode().serializedName()))
                        .append(" · ")
                        .append(text("configuration_count", row.configurations())));
    }

    private UUID key(NodeDirectoryPage.Card card) {
        return card.channel() == null ? selected : card.channel();
    }

    private static Component scopeLabel(String value) {
        if (value.equals("*")) return text("all_resources");
        var result = Component.empty();
        int offset = 0;
        while (offset < value.length()) {
            int end = value.indexOf(", ", offset);
            if (end < 0) end = value.length();
            String part = value.substring(offset, end);
            var id = ResourceLocation.tryParse(part);
            if (offset > 0) result.append(" / ");
            result.append(id == null ? Component.literal(part) : NodeResourcePolicyView.typeName(id));
            offset = end + 2;
        }
        return result;
    }

    static List<Component> cardLines(NodeDirectoryPage.Card card) {
        var result = new ArrayList<Component>();
        if (!card.route().isEmpty()) result.add(text("route", card.route()));
        if (!card.scope().equals("*")) result.add(text("scope", scopeLabel(card.scope())));
        if (!card.preset().isEmpty())
            result.add(text(
                    "preset", card.preset().equals("?") ? text("missing_preset") : Component.literal(card.preset())));
        int defaultInterval = io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.defaults(
                        card.input()
                                ? io.github.loongin.omniresonance.network.TransferDirection.INPUT
                                : io.github.loongin.omniresonance.network.TransferDirection.OUTPUT)
                .intervalTicks();
        if (card.intervalTicks() != defaultInterval) result.add(text("interval", card.intervalTicks()));
        if (card.quantity() != 0) result.add(text(card.input() ? "keep" : "priority", card.quantity()));
        if (!card.redstone().equals("IGNORE"))
            result.add(text(
                    "redstone",
                    Component.translatable("omniresonance.item_policy.redstone."
                            + card.redstone().toLowerCase(Locale.ROOT))));
        result.add(card.faces() == 0 ? faces(0) : text("working_faces", faces(card.faces())));
        if (card.overrides() > 0) result.add(text("overrides", card.overrides()));
        return List.copyOf(result);
    }

    private ConfigurationCard layoutCard(NodeDirectoryPage.Card card) {
        boolean open = key(card).equals(expanded);
        if (!open) return new ConfigurationCard(card, List.of(), 20, false);
        var lines = new ArrayList<FormattedCharSequence>();
        for (var line : cardLines(card))
            lines.addAll(font.split(
                    TerminalText.body(line), Math.max(1, layout.detail().width() - 28)));
        return new ConfigurationCard(card, List.copyOf(lines), 60 + lines.size() * 10, true);
    }

    private int actionColumns() {
        return layout.detail().width() >= 140 ? 2 : 1;
    }

    private int detailTop() {
        return ((4 + actionColumns() - 1) / actionColumns()) * 26 + 8;
    }

    private void buildDetail() {
        var rect = layout.detail();
        var node = page.selected().node();
        int[] operations = {0, 1, 2, 3};
        for (int slot = 0; slot < operations.length; slot++) {
            int i = operations[slot];
            final int action = i;
            int column = slot % actionColumns();
            int row = slot / actionColumns();
            int count = Math.min(actionColumns(), operations.length - row * actionColumns());
            var bounds = TerminalActionLayout.toolbarButton(
                    new TerminalLayout.Rect(rect.x(), rect.y() + row * 26, rect.width(), 36), count, column);
            Component label = text(
                    i == 0
                            ? (node.enabled() ? "disable" : "enable")
                            : i == 1 ? "rename" : i == 2 ? "highlight" : "teleport");
            button(bounds, label, !pending && (action != 3 || canTeleport()), () -> {
                if (action == 0) {
                    if (node.enabled()) {
                        modal = Modal.DISABLE;
                        rebuild();
                    } else request(NodeDirectoryRequest.Action.SET_ENABLED, null);
                } else if (action == 1) request(NodeDirectoryRequest.Action.BEGIN_RENAME, null);
                else navigation.accept(node.nodeId(), action == 3);
            });
            if (action == 3) {
                teleportButton = widgets.getLast();
                updateTeleportButton();
            }
        }
        int full = basicHeight();
        var cards = new ArrayList<ConfigurationCard>();
        if (node.enabled() && !page.cards().isEmpty()) {
            for (var card : page.cards()) {
                var measured = layoutCard(card);
                cards.add(measured);
                full += measured.height() + 6;
            }
        } else {
            for (var line : font.split(
                    TerminalText.body(text(node.enabled() ? "no_configuration" : "disabled_notice")),
                    Math.max(1, rect.width() - 28))) full += 10;
        }
        configurationCards = List.copyOf(cards);
        detailMaximum = Math.max(0, full - Math.max(0, rect.height() - detailTop() - 4));
        detailScroll = Math.clamp(detailScroll, 0, detailMaximum);
        int y = rect.y() + detailTop() - detailScroll;
        chunkStatusBounds = new TerminalLayout.Rect(
                rect.x() + 10,
                y + basicTextHeight(),
                rect.width() - 28,
                basicFieldHeight(basicFields(page.selected()).getLast()));
        y += basicHeight();
        if (!node.enabled()) return;
        for (var measured : configurationCards) {
            var card = measured.card();
            final var entry = card;
            int h = measured.height();
            if (y >= rect.y() + detailTop() - 2 && y + 20 <= rect.bottom() - 6) {
                button(
                                new TerminalLayout.Rect(rect.x() + 8, y, rect.width() - 20, 20),
                                Component.literal(
                                                card.channel() == null
                                                        ? Component.translatable("omniresonance.terminal.home.domain")
                                                                .getString()
                                                        : card.name())
                                        .append(" · ")
                                        .append(text(card.input() ? "input" : "output")),
                                !pending,
                                () -> {
                                    expanded = key(entry).equals(expanded) ? null : key(entry);
                                    rebuild();
                                })
                        .setSelected(measured.expanded());
            }
            if (key(card).equals(expanded)
                    && y + h - 28 >= rect.y() + detailTop() - 2
                    && y + h - 8 <= rect.bottom() - 6) {
                var bounds = new TerminalLayout.Rect(rect.right() - 96, y + h - 28, 80, 20);
                button(
                        bounds,
                        text("edit_configuration"),
                        !pending && card.enabled(),
                        () -> request(NodeDirectoryRequest.Action.EDIT_CONFIGURATION, entry.channel()));
            }
            y += h + 6;
        }
    }

    private Component dialogMessage() {
        var message = text(modal == Modal.DISABLE ? "disable_notice" : "discard_notice");
        return page != null && page.rejected() ? message.copy().append("\n").append(text("rejected")) : message;
    }

    private TerminalLayout.Rect dialog() {
        return modal == Modal.RENAME
                ? TerminalDialogLayout.editor(body)
                : TerminalDialogLayout.confirmation(body, font, dialogMessage());
    }

    private void buildModal() {
        var r = dialog();

        if (modal == Modal.RENAME) {
            nameField = new TerminalEditBox(font, r.x() + 10, r.y() + 35, r.width() - 20, 20, text("rename"));
            nameField.setMaxLength(128);
            nameField.setValue(nameDraft);
            nameField.setResponder(value -> nameDraft = value);
            nameField.active = !pending;
            add(nameField);
            focus.accept(nameField);
        }

        button(
                TerminalActionLayout.of(r).secondary(),
                text(modal == Modal.DISCARD ? "continue_editing" : "cancel"),
                !pending,
                () -> {
                    if (modal == Modal.DISCARD) {
                        modal = Modal.RENAME;
                        closeAfterDiscard = false;
                        rebuild();
                    } else if (modal == Modal.RENAME) back();
                    else {
                        modal = Modal.NONE;
                        rebuild();
                    }
                });
        button(
                TerminalActionLayout.of(r).primary(),
                text(modal == Modal.RENAME ? "save" : modal == Modal.DISCARD ? "discard" : "confirm"),
                !pending,
                () -> {
                    if (modal == Modal.RENAME) request(NodeDirectoryRequest.Action.RENAME, null);
                    else if (modal == Modal.DISABLE) {
                        modal = Modal.NONE;
                        request(NodeDirectoryRequest.Action.SET_ENABLED, null);
                        rebuild();
                    } else {
                        modal = Modal.NONE;
                        if (closeAfterDiscard) closeRoot.run();
                        else request(NodeDirectoryRequest.Action.CANCEL_EDIT, null);
                        rebuild();
                    }
                });
    }

    private boolean searchEligible() {
        return modal == Modal.NONE
                && (!pending
                        || lastAction == NodeDirectoryRequest.Action.QUERY
                        || lastAction == NodeDirectoryRequest.Action.CATALOG);
    }

    private void toggleSearch() {
        if (!searchEligible()) return;
        if (search.expanded()) {
            search.close(ticks);
            search.handled();
            query = "";
            focus.accept(null);
        } else {
            search.open();
            catalog.clear();
            catalogFailed = false;
            if (pending) pending = false;
        }
        applyQuery();
        rebuild();
        if (searchField != null) {
            searchField.setFocused(true);
            focus.accept(searchField);
        }
    }

    boolean back() {
        if (pending
                && lastAction != NodeDirectoryRequest.Action.QUERY
                && lastAction != NodeDirectoryRequest.Action.CATALOG) return true;
        if (pending) pending = false;
        if (modal == Modal.RENAME && dirty()) {
            modal = Modal.DISCARD;
            closeAfterDiscard = false;
            rebuild();
            return true;
        }
        if (modal != Modal.NONE) {
            boolean rename = modal == Modal.RENAME || modal == Modal.DISCARD;
            modal = Modal.NONE;
            if (rename) request(NodeDirectoryRequest.Action.CANCEL_EDIT, null);
            rebuild();
            return true;
        }
        if (search.expanded()) {
            toggleSearch();
            return true;
        }

        return false;
    }

    boolean requestClose() {
        if (new ClientDraftExit(dirty(), pending && lastAction == NodeDirectoryRequest.Action.RENAME)
                        .requiresConfirmation()
                || modal == Modal.DISCARD) {
            modal = Modal.DISCARD;
            closeAfterDiscard = true;
            rebuild();
            return true;
        }
        return false;
    }

    boolean searchExpanded() {
        return search.expanded();
    }

    void finishClick(boolean wasExpanded) {
        if (!wasExpanded && search.expanded() && searchField != null) {
            searchField.setFocused(true);
            focus.accept(searchField);
        } else if (wasExpanded && !search.expanded()) focus.accept(null);
    }

    boolean keyPressed(int key, int modifiers) {
        return ClientSearchState.handleToggleKey(key, modifiers, searchEligible(), this::toggleSearch);
    }

    private void updateTeleportButton() {
        if (teleportButton == null) return;
        boolean allowed = canTeleport();
        teleportButton.active = !pending && allowed;
        teleportButton.setTooltip(Tooltip.create(TerminalText.body(text(allowed ? "teleport" : "teleport_operator"))));
    }

    private static boolean canTeleport() {
        var client = net.minecraft.client.Minecraft.getInstance();
        return client != null && client.player != null && client.player.hasPermissions(2);
    }

    void tick() {
        ticks++;
        if (teleportButton != null && teleportButton.active != (!pending && canTeleport())) updateTeleportButton();
        if (modal == Modal.NONE && search.due(ticks)) {
            query = search.draft();
            search.handled();
            applyQuery();
        }
    }

    boolean wheel(double x, double y, double delta) {
        if (delta == 0 || layout == null) return false;

        if (modal != Modal.NONE || pending || page == null) return false;
        if (contains(layout.list(), x, y)) {
            if (search.expanded()) {
                listScroll = Math.clamp(
                        listScroll + (delta < 0 ? 1 : -1),
                        0,
                        Math.max(0, visibleNodes().size() - visibleRows()));
                rebuild();
                return true;
            }
            var move = PagedListScroll.navigate(
                    listScroll, visibleNodes().size(), visibleRows(), page.previous(), page.next(), delta);
            if (move.pageRequest() == PagedListScroll.PageRequest.NONE) {
                listScroll = move.scroll();
                rebuild();
            } else if (!page.rows().isEmpty()) {
                before = move.pageRequest() == PagedListScroll.PageRequest.PREVIOUS;
                anchor = (before ? page.rows().getFirst() : page.rows().getLast()).number();
                landAtEnd = before;
                listScroll = 0;
                request(NodeDirectoryRequest.Action.QUERY, null);
            }
            return true;
        }
        if (contains(layout.detail(), x, y)) {
            if (delta < 0
                    && detailScroll >= detailMaximum
                    && page.detailOffset() + page.cards().size() < page.detailTotal()) {
                detailOffset = page.detailOffset() + page.cards().size();
                detailScroll = 0;
                request(NodeDirectoryRequest.Action.SELECT, null);
            } else if (delta > 0 && detailScroll == 0 && page.detailOffset() > 0) {
                detailOffset = Math.max(0, page.detailOffset() - 16);
                detailAtEnd = true;
                request(NodeDirectoryRequest.Action.SELECT, null);
            } else {
                detailScroll = Math.clamp(detailScroll + (delta < 0 ? 20 : -20), 0, detailMaximum);
                rebuild();
            }
            return true;
        }
        return false;
    }

    private static boolean contains(TerminalLayout.Rect r, double x, double y) {
        return x >= r.x() && x < r.right() && y >= r.y() && y < r.bottom();
    }

    private void lines(GuiGraphics g, List<Component> lines, int x, int y, int width) {
        for (var line : lines)
            for (var wrapped : font.split(TerminalText.body(line), Math.max(1, width))) {
                g.drawString(font, wrapped, x, y, TerminalTheme.MUTED, false);
                y += 10;
            }
    }

    void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (modal == Modal.NONE
                && page != null
                && page.selected() != null
                && chunkStatusBounds.width() > 0
                && mouseY >= layout.detail().y() + detailTop()
                && mouseY < layout.detail().bottom() - 4
                && contains(chunkStatusBounds, mouseX, mouseY))
            graphics.renderTooltip(
                    font,
                    TerminalText.body(NodeChunkIndicator.tooltip(
                            page.chunkLoading(), page.selected().node().chunkLoadingRequested())),
                    mouseX,
                    mouseY);
    }

    void render(GuiGraphics g) {
        if (layout == null) return;
        TerminalTheme.renderPaneDivider(g, layout.list(), layout.detail());
        if (page != null && page.available()) {
            {
                var r = layout.list();
                if (search.expanded()
                        && !catalog.ready()
                        && !catalogFailed
                        && TerminalInteractionPolicy.loadingVisible(ticks - catalogStartedTick))
                    lines(g, List.of(text("loading")), r.x() + 8, r.y() + 8, r.width() - 16);
                TerminalTheme.renderScrollbar(
                        g,
                        r.right() - 7,
                        r.y() + 6,
                        r.height() - 12,
                        visibleNodes().size(),
                        visibleRows(),
                        listScroll);
            }
            if (selected != null && page.selected() != null) {
                var r = layout.detail();
                g.enableScissor(r.x() + 2, r.y() + detailTop() - 3, r.right() - 2, r.bottom() - 4);
                int infoY = r.y() + detailTop() - detailScroll;
                renderBasicFields(g, infoY);
                int separatorY = infoY + basicHeight() - 6;
                g.fill(r.x() + 10, separatorY, r.right() - 12, separatorY + 1, TerminalTheme.LINE);
                if (!page.selected().node().enabled() || page.cards().isEmpty()) {
                    lines(
                            g,
                            List.of(text(page.selected().node().enabled() ? "no_configuration" : "disabled_notice")),
                            r.x() + 12,
                            infoY + basicHeight(),
                            r.width() - 28);
                } else {
                    int y = infoY + basicHeight();
                    for (var measured : configurationCards) {
                        int h = measured.height();
                        if (measured.expanded()) {
                            int x = r.x() + 8, width = r.width() - 20;
                            TerminalTheme.renderSurface(
                                    g,
                                    new TerminalLayout.Rect(x, y + 20, width, h - 20),
                                    TerminalTheme.BUTTON_RADIUS,
                                    TerminalTheme.INPUT,
                                    TerminalTheme.LINE);
                            g.fill(x + 1, y + 20, x + width - 1, y + 21, TerminalTheme.INPUT);
                            int textY = y + 26;
                            for (var line : measured.lines()) {
                                g.drawString(font, line, r.x() + 12, textY, TerminalTheme.MUTED, false);
                                textY += 10;
                            }
                        }
                        y += h + 6;
                    }
                }
                g.disableScissor();
                TerminalTheme.renderScrollbar(
                        g,
                        r.right() - 7,
                        r.y() + detailTop(),
                        Math.max(0, r.height() - detailTop() - 6),
                        detailMaximum + 1,
                        1,
                        detailScroll);
            }
        } else if (TerminalInteractionPolicy.loadingVisible(ticks))
            lines(
                    g,
                    List.of(text(page == null ? "loading" : "unavailable")),
                    body.x() + 10,
                    layout.list().y() + 10,
                    body.width() - 20);
        if (modal != Modal.NONE) {
            var r = dialog();
            TerminalDialogLayout.render(g, body, r);
            Component heading = text(modal.name().toLowerCase(Locale.ROOT));
            if (page != null && page.selected() != null)
                heading = heading.copy()
                        .append(" · ")
                        .append(page.selected().node().nodeName());
            TerminalText.drawDialogTitle(g, font, heading, r);
            if (modal != Modal.RENAME) lines(g, List.of(dialogMessage()), r.x() + 10, r.y() + 35, r.width() - 20);
            if (modal == Modal.RENAME && page != null && page.rejected())
                TerminalDialogLayout.renderInputError(
                        g,
                        font,
                        nameField,
                        text("rejected"),
                        TerminalActionLayout.of(r).primary().y());
        }
    }
}
