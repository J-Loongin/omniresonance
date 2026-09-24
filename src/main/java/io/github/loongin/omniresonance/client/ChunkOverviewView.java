// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.ChunkOverviewPage;
import io.github.loongin.omniresonance.networking.ChunkOverviewRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Read-only, bounded node overview. Selection never changes node state or initiates travel. */
final class ChunkOverviewView implements AutoCloseable {
    private final UUID view, session;
    private final boolean nodeView;

    boolean isNodeView() {
        return nodeView;
    }

    private final long generation;
    private final Consumer<ChunkOverviewRequest> sender;
    private Runnable onNavigate = () -> {};

    void onNavigate(Runnable callback) {
        onNavigate = callback;
    }

    private final List<AbstractWidget> widgets = new ArrayList<>();
    private @Nullable ChunkOverviewPage page;
    private @Nullable UUID selected;
    private @Nullable Font font;
    private @Nullable Consumer<AbstractWidget> add;
    private @Nullable Consumer<GuiEventListener> remove;
    private TerminalLayout.Rect body = new TerminalLayout.Rect(0, 0, 0, 0);
    private long sequence;
    private boolean pending, tail, pageChange;
    private int scroll, loadingTicks, rowHeight = 76;

    ChunkOverviewView(UUID view, UUID session, long generation, Consumer<ChunkOverviewRequest> sender) {
        this(view, session, generation, sender, false);
    }

    ChunkOverviewView(
            UUID view, UUID session, long generation, Consumer<ChunkOverviewRequest> sender, boolean nodeView) {
        this.nodeView = nodeView;
        this.view = view;
        this.session = session;
        this.generation = generation;
        this.sender = sender;
    }

    void open() {
        send(nodeView ? ChunkOverviewRequest.Action.OPEN_NODES : ChunkOverviewRequest.Action.OPEN, 0, false);
    }

    void accept(ChunkOverviewPage value) {
        if (!value.session().equals(session) || value.generation() != generation || value.sequence() != sequence)
            return;
        boolean changed = page == null
                || !page.entries().equals(value.entries())
                || page.available() != value.available()
                || pending;
        if (pending && pageChange) scroll = tail ? Integer.MAX_VALUE : 0;
        page = value;
        pending = false;
        tail = false;
        pageChange = false;
        if (changed) rebuildRows();
    }

    void build(Font font, TerminalLayout.Rect body, Consumer<AbstractWidget> add, Consumer<GuiEventListener> remove) {
        this.font = font;
        this.body = body;
        this.add = add;
        this.remove = remove;
        widgets.clear();
        rebuildRows();
    }

    private int listTop() {
        return nodeView ? 8 : 36;
    }

    private int visibleRows() {
        return Math.max(1, (body.height() - listTop() - 4 - (selected == null ? 0 : 34)) / rowHeight);
    }

    @Nullable
    UUID selectedNode() {
        return selected;
    }

    private void rebuildRows() {
        if (add == null || remove == null || font == null) return;
        for (var widget : widgets) remove.accept(widget);
        widgets.clear();
        if (page == null || !page.available()) {
            selected = null;
            return;
        }
        int textWidth = Math.max(1, body.width() - 24);
        rowHeight = 76;
        boolean found = false;
        for (var entry : page.entries()) {
            found |= entry.id().equals(selected);
            int lines = 0;
            for (var value : details(entry))
                lines += font.split(TerminalText.body(value), textWidth).size();
            rowHeight = Math.max(rowHeight, 32 + lines * 10);
        }
        if (!found) selected = null;
        scroll = Math.clamp(scroll, 0, Math.max(0, page.entries().size() - visibleRows()));
        if (nodeView)
            for (int row = 0;
                    row < visibleRows() && row + scroll < page.entries().size();
                    row++) {
                var entry = page.entries().get(row + scroll);
                var button = new TerminalButton(
                        body.x() + 8,
                        body.y() + listTop() + row * rowHeight,
                        body.width() - 24,
                        20,
                        Component.literal(entry.name()),
                        ignored -> {
                            selected = entry.id();
                            rebuildRows();
                        },
                        false);
                button.setSelected(entry.id().equals(selected));
                button.active = !pending;
                button.setTooltip(Tooltip.create(TerminalText.body(Component.literal(entry.name()))));
                widgets.add(button);
                add.accept(button);
            }
        if (selected != null)
            for (int i = 0; i < 2; i++) {
                var bounds = TerminalActionLayout.button(body, 2, i);
                var action = i == 0 ? ChunkOverviewRequest.Action.HIGHLIGHT : ChunkOverviewRequest.Action.TELEPORT;
                var button = new TerminalButton(
                        bounds.x(),
                        bounds.y(),
                        bounds.width(),
                        bounds.height(),
                        text(i == 0 ? "highlight" : "teleport"),
                        ignored -> navigate(action),
                        false);
                button.active = !pending;
                widgets.add(button);
                add.accept(button);
            }
    }

    private void navigate(ChunkOverviewRequest.Action action) {
        if (!nodeView || pending || selected == null) return;
        sequence = Math.incrementExact(sequence);
        pending = true;
        sender.accept(new ChunkOverviewRequest(view, session, generation, sequence, action, 0, false, selected));
        onNavigate.run();
    }

    private void send(ChunkOverviewRequest.Action action, long anchor, boolean before) {
        if (pending && action != ChunkOverviewRequest.Action.CLOSE) return;
        sequence = Math.incrementExact(sequence);
        pending = true;
        pageChange = action == ChunkOverviewRequest.Action.PAGE;
        tail = pageChange && before;
        sender.accept(new ChunkOverviewRequest(view, session, generation, sequence, action, anchor, before));
        for (var widget : widgets) widget.active = false;
    }

    boolean wheel(double x, double y, double delta) {
        if (page == null
                || pending
                || !page.available()
                || x < body.x()
                || x >= body.right()
                || y < body.y() + listTop() - 4
                || y >= body.bottom()) return false;
        var result = PagedListScroll.navigate(
                scroll, page.entries().size(), visibleRows(), page.previous(), page.next(), delta);
        if (result.pageRequest() == PagedListScroll.PageRequest.NONE) {
            scroll = result.scroll();
            rebuildRows();
        } else {
            boolean back = result.pageRequest() == PagedListScroll.PageRequest.PREVIOUS;
            send(
                    ChunkOverviewRequest.Action.PAGE,
                    (back ? page.entries().getFirst() : page.entries().getLast()).number(),
                    back);
        }
        return true;
    }

    void tick() {
        if (!TerminalInteractionPolicy.loadingVisible(loadingTicks)) loadingTicks++;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("omniresonance.chunk_overview." + key, args);
    }

    private static String limit(int value) {
        return value < 0 ? "∞" : Integer.toString(value);
    }

    static Component dimension(ChunkOverviewPage.Entry entry) {
        return switch (entry.dimension().toString()) {
            case "minecraft:overworld" -> text("dimension.overworld");
            case "minecraft:the_nether" -> text("dimension.nether");
            case "minecraft:the_end" -> text("dimension.end");
            default -> Component.literal(entry.dimension().toString());
        };
    }

    static Component coordinates(ChunkOverviewPage.Entry entry) {
        return text(
                "coordinates",
                entry.position().getX(),
                entry.position().getY(),
                entry.position().getZ());
    }

    private List<Component> details(ChunkOverviewPage.Entry entry) {
        var state = Component.translatable(
                "omniresonance.chunk_loading." + entry.status().name().toLowerCase(java.util.Locale.ROOT));
        if (nodeView)
            state = Component.translatable(
                    entry.enabled() ? "omniresonance.node_menu.enabled" : "omniresonance.node_menu.disabled");
        var mode = Component.translatable(
                "omniresonance.node_menu.mode." + entry.mode().serializedName());
        return List.of(mode.append(" · ").append(state), coordinates(entry), dimension(entry));
    }

    void render(GuiGraphics graphics, Font font) {
        TerminalTheme.renderPanel(graphics, body);
        if (page == null || !page.available()) {
            if (page != null || TerminalInteractionPolicy.loadingVisible(loadingTicks))
                graphics.drawString(
                        font,
                        TerminalText.body(text(page == null ? "loading" : "unavailable")),
                        body.x() + 8,
                        body.y() + 8,
                        TerminalTheme.MUTED,
                        false);
            return;
        }
        if (!nodeView)
            graphics.drawString(
                    font,
                    TerminalText.body(text(
                            "quotas",
                            page.ownerUsed(),
                            limit(page.ownerLimit()),
                            page.serverUsed(),
                            limit(page.serverLimit()))),
                    body.x() + 8,
                    body.y() + 8,
                    TerminalTheme.TEXT,
                    false);
        graphics.enableScissor(body.x(), body.y() + listTop() - 4, body.right(), body.bottom());
        for (int row = 0; row < visibleRows() && row + scroll < page.entries().size(); row++) {
            var entry = page.entries().get(row + scroll);
            int x = body.x() + 10, y = body.y() + listTop() + row * rowHeight;
            if (!nodeView)
                graphics.drawString(
                        font, TerminalText.body(Component.literal(entry.name())), x, y + 4, TerminalTheme.TEXT, false);
            int dy = y + 24;
            for (var value : details(entry))
                for (var line : font.split(TerminalText.body(value), Math.max(1, body.width() - 24))) {
                    graphics.drawString(font, line, x, dy, TerminalTheme.MUTED, false);
                    dy += 10;
                }
        }
        graphics.disableScissor();
        TerminalTheme.renderScrollbar(
                graphics,
                body.right() - 7,
                body.y() + listTop(),
                visibleRows() * rowHeight,
                page.entries().size(),
                visibleRows(),
                scroll);
    }

    public void close() {
        sender.accept(new ChunkOverviewRequest(
                view, session, generation, Math.incrementExact(sequence), ChunkOverviewRequest.Action.CLOSE, 0, false));
        widgets.clear();
        page = null;
        selected = null;
        add = null;
        remove = null;
    }
}
