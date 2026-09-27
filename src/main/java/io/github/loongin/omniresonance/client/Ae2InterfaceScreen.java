// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.compat.ae2.Ae2InterfacePayloads;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Immediate owner-only network selector; no configuration drafts, inventory slots or redundant save footer. */
final class Ae2InterfaceScreen extends Screen {
    private final UUID session;
    private final java.util.function.Consumer<Ae2InterfacePayloads.Request> sender;
    private final BiPredicate<Integer, Integer> terminalKey;
    private final ClientSearchState search = new ClientSearchState();
    private final LinkedHashMap<UUID, Ae2InterfacePayloads.Choice> catalog = new LinkedHashMap<>();
    private final TerminalResultRows widgets =
            new TerminalResultRows(this, this::addRenderableWidget, this::removeWidget);
    private TerminalLayout layout;
    private TerminalLayout.Rect listBounds;
    private @Nullable UUID selected;
    private String status = "preparing", error = "";
    private int total = -1, scroll, visible;
    private long next = 1, lastWrite = -1, ticks;
    private boolean ready, writing, closed;
    private List<Ae2InterfacePayloads.Choice> filtered = List.of();

    Ae2InterfaceScreen(Ae2InterfacePayloads.Frame first, BiPredicate<Integer, Integer> terminalKey) {
        this(first, terminalKey, request -> PacketDistributor.sendToServer(request));
    }

    Ae2InterfaceScreen(
            Ae2InterfacePayloads.Frame first,
            BiPredicate<Integer, Integer> terminalKey,
            java.util.function.Consumer<Ae2InterfacePayloads.Request> sender) {
        super(text("title"));
        this.sender = sender;
        session = first.session();
        this.terminalKey = terminalKey;
        accept(first);
    }

    private static Component text(String key) {
        return Component.translatable("omniresonance.ae_interface." + key);
    }

    private void request(int action, @Nullable UUID network) {
        if (closed) return;
        long seq = next++;
        if (action == 1 || action == 2) {
            lastWrite = seq;
            writing = true;
        }
        sender.accept(new Ae2InterfacePayloads.Request(
                session, seq, action, network, text("default_name").getString()));
        if (action == 1 || action == 2) rebuild();
    }

    void accept(Ae2InterfacePayloads.Frame frame) {
        if (closed || !session.equals(frame.session()) || frame.sequence() < lastWrite) return;
        boolean changed = !java.util.Objects.equals(selected, frame.selected())
                || !status.equals(frame.status())
                || !error.equals(frame.error());
        selected = frame.selected();
        status = frame.status();
        error = frame.error();
        if (frame.sequence() == lastWrite) {
            writing = false;
            changed = true;
        }
        if (!ready && frame.total() >= 0) {
            if (total < 0) total = frame.total();
            if (total != frame.total() || frame.entries().isEmpty() && frame.more()) {
                error = "unavailable";
                rebuild();
                return;
            }
            for (var choice : frame.entries())
                if (catalog.putIfAbsent(choice.id(), choice) != null) {
                    error = "unavailable";
                    rebuild();
                    return;
                }
            if (catalog.size() > total) {
                error = "unavailable";
                rebuild();
                return;
            }
            if (frame.more()) request(0, frame.entries().getLast().id());
            else ready = catalog.size() == total;
            changed = true;
        }
        if (changed) rebuild();
    }

    @Override
    protected void init() {
        build(TerminalText.font(minecraft), width, height);
    }

    void build(net.minecraft.client.gui.Font font, int width, int height) {
        this.font = font;
        this.width = width;
        this.height = height;
        layout = TerminalLayout.terminal(width, height);
        rebuild();
    }

    private TerminalLayout.Rect searchBounds() {
        var c = layout.content();
        return new TerminalLayout.Rect(c.x() + 4, c.y() + 34, c.width() - 14, 20);
    }

    private void toggleSearch() {
        if (!ready || writing || !error.isEmpty()) return;
        search.toggle(ticks);
        scroll = 0;
        rebuild();
        if (search.expanded())
            setFocused(search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, ticks)));
    }

    private void rebuild() {
        if (layout == null) return;
        boolean focused = getFocused() instanceof TerminalSearchBox;
        widgets.clear();
        clearWidgets();
        setFocused(null);
        var header = TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(layout.window()), true);
        var button = addRenderableWidget(new TerminalSearchButton(
                header.action(), search.expanded(), text("search"), ignored -> toggleSearch()));
        button.active = ready && !writing && error.isEmpty();
        var c = layout.content();
        int top = c.y() + 34;
        if (search.expanded()) {
            var field = search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, ticks));
            addRenderableWidget(field);
            if (focused) setFocused(field);
            top += 26;
        }
        listBounds = new TerminalLayout.Rect(c.x() + 4, top, c.width() - 8, Math.max(0, c.bottom() - top));
        visible = Math.max(1, listBounds.height() / 26);
        filtered = ClientTextSearch.filter(
                (query, matcher, revision) -> catalog.values().stream()
                        .filter(choice -> matcher.test(ClientTextSearch.fold(choice.name()), query))
                        .toList(),
                ClientTextSearch.fold(search.draft()));
        int count = filtered.size();
        scroll = Math.clamp(scroll, 0, Math.max(0, count - visible));
        if (!ready) return;
        for (int i = scroll; i < Math.min(count, scroll + visible); i++) {
            var choice = filtered.get(i);
            var label = Component.literal(choice.name());
            var row = new TerminalRowButton(
                    listBounds.x(),
                    top + (i - scroll) * 26,
                    listBounds.width() - 8,
                    20,
                    label,
                    ignored -> request(
                            choice.id().equals(selected) ? 2 : 1, choice.id().equals(selected) ? null : choice.id()));
            row.active = !writing && error.isEmpty();
            row.setSelected(choice.id().equals(selected));
            row.setTooltip(Tooltip.create(TerminalText.body(label)));
            widgets.add(row);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, TerminalTheme.WORLD_DIM);
        TerminalTheme.renderWindow(g, layout);
        TerminalTheme.renderPanel(g, layout.content());
        var top = TerminalHeaderLayout.topBarContent(layout.window());
        var h = TerminalHeaderLayout.atRightEdge(top, true);
        var name = TerminalNetworkContext.layout(h.remaining(), false, false);
        TerminalText.drawHeaderTitle(
                g, font, title.getString(), new TerminalLayout.Rect(top.x(), top.y(), name.x() - top.x() - 6, 20));
        var current = selected == null ? null : catalog.get(selected);
        String nameText = current == null ? text("unbound").getString() : current.name();
        TerminalText.drawNetworkLabel(
                nameText,
                name,
                font::width,
                TerminalTheme.TEXT,
                (value, x, y, color, shadow) -> g.drawString(font, value, x, y, color, shadow));
        var c = layout.content();
        g.drawString(
                font,
                TerminalText.ellipsize(font, text("authorization").getString(), c.width() - 12),
                c.x() + 6,
                c.y() + 4,
                TerminalTheme.MUTED,
                false);
        String state = !error.isEmpty() ? "unavailable" : !ready ? "loading" : status;
        g.drawString(
                font,
                TerminalText.ellipsize(font, text(state).getString(), c.width() - 12),
                c.x() + 6,
                c.y() + 19,
                state.equals("conflict") || state.equals("unavailable") ? TerminalTheme.ERROR : TerminalTheme.MUTED,
                false);
        if (listBounds != null)
            TerminalTheme.renderScrollbar(
                    g, listBounds.right() - 6, listBounds.y(), listBounds.height(), filtered.size(), visible, scroll);
    }

    @Override
    public void tick() {
        ticks++;
        if (search.due(ticks)) {
            search.handled();
            scroll = 0;
            rebuild();
        }
        if (ready && !writing && error.isEmpty() && ticks % 20 == 0) request(4, null);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (NetworkSetupScreen.routeKey(
                getFocused(),
                key,
                scan,
                mods,
                () -> TerminalInteractionPolicy.inventoryShortcut(
                                minecraft.options.keyInventory, getFocused(), key, scan)
                        || terminalKey.test(key, scan),
                this::onClose,
                () -> ClientSearchState.handleToggleKey(
                        key, mods, ready && !writing && error.isEmpty(), this::toggleSearch))) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (search.close(ticks)) rebuild();
            else onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        boolean old = search.expanded();
        boolean result = super.mouseClicked(x, y, button);
        search.finishToggleClick(
                old,
                this,
                search.expanded()
                        ? search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, ticks))
                        : null);
        return result;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (listBounds != null
                && x >= listBounds.x()
                && x < listBounds.right()
                && y >= listBounds.y()
                && y < listBounds.bottom()
                && dy != 0) {
            scroll = Math.clamp(scroll + (dy > 0 ? -1 : 1), 0, Math.max(0, filtered.size() - visible));
            rebuild();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (!closed) {
            if (net.minecraft.client.Minecraft.getInstance().getConnection() != null) request(3, null);
            closed = true;
        }
    }
}
