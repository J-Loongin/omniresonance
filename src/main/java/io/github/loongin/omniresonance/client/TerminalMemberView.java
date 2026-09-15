// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkMemberSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.OnlinePlayerSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** Member/candidate presentation owned by one terminal Screen; actions contain no connection, actor or authority. */
final class TerminalMemberView {
    sealed interface Action {
        record OpenCandidates() implements Action {}

        record Add(UUID target) implements Action {}

        record Remove(UUID target) implements Action {}

        record ConfirmRemove() implements Action {}

        record Back() implements Action {}

        record PageMembers(UUID anchor, boolean backwards) implements Action {}

        record ContinueCandidates(UUID snapshotId, int offset) implements Action {}
    }

    private final ContainerEventHandler owner;
    private final Runnable rebuild;
    private final ClientSearchState search = new ClientSearchState();
    private final OnlinePlayerCatalog candidates = new OnlinePlayerCatalog();
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private @Nullable NetworkTerminalState state;
    private @Nullable UUID selected;
    private @Nullable TerminalSearchBox field;
    private @Nullable TerminalResultRows rows;
    private @Nullable Component searchError;
    private Font font;
    private TerminalLayout layout;
    private Consumer<AbstractWidget> addWidget;
    private Consumer<GuiEventListener> removeWidget;
    private Consumer<Action> actions;
    private UUID actor;
    private boolean pending;
    private boolean compactDetails;
    private boolean landAtEnd;
    private int listScroll;
    private int detailScroll;
    private int detailMaximumScroll;
    private long clientTick;
    private String query = "";

    TerminalMemberView(ContainerEventHandler owner, Runnable rebuild) {
        this.owner = owner;
        this.rebuild = rebuild;
    }

    static boolean supports(@Nullable NetworkTerminalState state) {
        return state instanceof NetworkTerminalState.Members
                || state instanceof NetworkTerminalState.AdministratorCandidates
                || state instanceof NetworkTerminalState.RemoveAdministrator;
    }

    void apply(@Nullable NetworkTerminalState next) {
        NetworkTerminalState previous = state;
        if (!supports(next)) {
            reset();
            return;
        }
        state = next;
        if (next instanceof NetworkTerminalState.AdministratorCandidates online) {
            if (!(previous instanceof NetworkTerminalState.AdministratorCandidates)) {
                search.reset();
                query = "";
                listScroll = 0;
                searchError = null;
            }
            if (online.page().offset() == 0) candidates.begin(online.page());
            else candidates.append(online.page());
        } else {
            candidates.reset();
            search.reset();
            query = "";
            searchError = null;
        }
        if (next instanceof NetworkTerminalState.Members members) {
            selected = members.selectedMemberId();
            compactDetails = previous instanceof NetworkTerminalState.AdministratorCandidates;
            listScroll = landAtEnd ? Integer.MAX_VALUE : 0;
            detailScroll = 0;
            landAtEnd = false;
        }
    }

    void reset() {
        state = null;
        selected = null;
        field = null;
        search.reset();
        candidates.reset();
        query = "";
        listScroll = 0;
        detailScroll = 0;
        compactDetails = false;
        landAtEnd = false;
        searchError = null;
    }

    boolean expanded() {
        return search.expanded();
    }

    boolean candidatesReady() {
        return candidates.ready();
    }

    void requestFailed() {
        if (!candidates.ready()) candidates.fail();
    }

    void notice(Component message) {
        searchError = message;
    }

    @Nullable
    TerminalSearchBox searchField() {
        return field;
    }

    @Nullable
    Action.ContinueCandidates continuation() {
        if (state instanceof NetworkTerminalState.AdministratorCandidates online
                && !candidates.ready()
                && !candidates.failed()
                && online.page().hasNext())
            return new Action.ContinueCandidates(online.page().snapshotId(), candidates.received());
        return null;
    }

    void build(
            Font font,
            TerminalLayout layout,
            UUID actor,
            boolean pending,
            Consumer<AbstractWidget> addWidget,
            Consumer<GuiEventListener> removeWidget,
            Consumer<Action> actions) {
        if (rows != null) rows.clear();
        if (this.removeWidget != null) for (AbstractWidget widget : widgets) this.removeWidget.accept(widget);
        widgets.clear();
        field = null;
        this.font = font;
        this.layout = layout;
        this.actor = actor;
        this.pending = pending;
        this.addWidget = addWidget;
        this.removeWidget = removeWidget;
        this.actions = actions;
        rows = new TerminalResultRows(owner, addWidget::accept, removeWidget);
        if (state instanceof NetworkTerminalState.Members members) buildMembers(members);
        else if (state instanceof NetworkTerminalState.AdministratorCandidates) buildCandidates();
        else if (state instanceof NetworkTerminalState.RemoveAdministrator) buildRemoval();
    }

    private void add(AbstractWidget widget) {
        widgets.add(widget);
        addWidget.accept(widget);
    }

    private void buildMembers(NetworkTerminalState.Members members) {
        TerminalMemberLayout panes = TerminalMemberLayout.calculate(layout);
        if (!panes.compact() || !compactDetails) {
            RoutingListLayout list = RoutingListLayout.calculate(
                    panes.list(), members.page().entries().size(), listScroll);
            listScroll = list.scroll();
            for (int row = 0;
                    row < list.visibleRows()
                            && listScroll + row < members.page().entries().size();
                    row++) {
                NetworkMemberSummary member = members.page().entries().get(listScroll + row);
                String label = member.name() + " · " + role(member).getString() + " · "
                        + status(member).getString();
                TerminalRowButton button = new TerminalRowButton(list.row(row), Component.literal(label), ignored -> {
                    selected = member.playerId();
                    detailScroll = 0;
                    compactDetails = panes.compact();
                    rebuild.run();
                });
                button.setSelected(member.playerId().equals(selected));
                button.active = !pending;
                button.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label))));
                rows.add(button);
            }
        }
        if ((!panes.compact() || compactDetails) && members.network().ownerId().equals(actor)) {
            NetworkMemberSummary member = selectedMember(members);
            if (member.role() == NetworkMemberSummary.Role.ADMINISTRATOR) {
                TerminalLayout.Rect detail = panes.detail();
                TerminalButton remove = new TerminalButton(
                        detail.x() + 10,
                        detail.bottom() - 28,
                        Math.min(140, detail.width() - 20),
                        20,
                        text("remove"),
                        ignored -> actions.accept(new Action.Remove(member.playerId())),
                        false);
                remove.active = !pending;
                add(remove);
            }
        }
    }

    private void buildCandidates() {
        if (!candidates.ready()) {
            if (candidates.failed()) {
                TerminalLayout.Rect content = layout.content();
                TerminalButton retry = new TerminalButton(
                        content.x() + 12,
                        content.y() + 60,
                        90,
                        20,
                        Component.translatable("omniresonance.terminal.retry"),
                        ignored -> actions.accept(new Action.OpenCandidates()),
                        true);
                retry.active = !pending;
                add(retry);
            }
            return;
        }
        if (search.expanded()) {
            TerminalLayout.Rect bounds = NodeRoutingView.tunnelSearchBounds(layout.content());
            field = new TerminalSearchBox(
                    font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), text("search"));
            field.setMaxLength(256);
            field.setValue(search.draft());
            field.setHint(TerminalText.body(text("search")));
            field.setResponder(value -> {
                search.edit(value, clientTick);
                searchError = null;
            });
            field.active = !pending;
            add(field);
        }
        replaceCandidates();
    }

    private void replaceCandidates() {
        if (rows == null) return;
        rows.clear();
        List<OnlinePlayerSummary> filtered = candidates.filter(query);
        RoutingListLayout list =
                NodeRoutingView.tunnelList(layout.content(), search.expanded(), filtered.size(), listScroll);
        listScroll = list.scroll();
        for (int row = 0; row < list.visibleRows() && listScroll + row < filtered.size(); row++) {
            OnlinePlayerSummary player = filtered.get(listScroll + row);
            String label = player.name() + " · " + text("add").getString();
            TerminalRowButton button = new TerminalRowButton(
                    list.row(row),
                    Component.literal(label),
                    ignored -> actions.accept(new Action.Add(player.playerId())));
            button.active = !pending;
            button.setTooltip(Tooltip.create(TerminalText.body(Component.literal(player.name()))));
            rows.add(button);
        }
    }

    private TerminalLayout.Rect removalBounds() {
        var remove = (NetworkTerminalState.RemoveAdministrator) state;
        return TerminalDialogLayout.confirmation(
                layout.content(),
                font,
                Component.translatable(
                        "omniresonance.terminal.members.remove_warning",
                        remove.target().name()));
    }

    private void buildRemoval() {
        TerminalLayout.Rect modal = removalBounds();
        var footer = TerminalActionLayout.of(modal);
        int width = footer.primary().width();
        TerminalButton cancel = new TerminalButton(
                footer.secondary().x(),
                footer.secondary().y(),
                width,
                20,
                Component.translatable("omniresonance.terminal.cancel"),
                ignored -> actions.accept(new Action.Back()),
                false);
        TerminalButton confirm = new TerminalButton(
                footer.primary().x(),
                footer.primary().y(),
                width,
                20,
                text("remove"),
                ignored -> actions.accept(new Action.ConfirmRemove()),
                true);
        cancel.active = !pending;
        confirm.active = !pending;
        add(cancel);
        add(confirm);
    }

    void tick(long tick) {
        clientTick = tick;
        if (!(state instanceof NetworkTerminalState.AdministratorCandidates)
                || pending
                || !candidates.ready()
                || !search.due(tick)) return;
        search.handled();
        String normalized = ClientSearchState.normalizedQuery(search.draft());
        if (normalized == null) {
            searchError = Component.translatable("omniresonance.terminal.error.invalid_request");
            return;
        }
        query = normalized;
        listScroll = 0;
        replaceCandidates();
    }

    void toggleSearch() {
        if (!(state instanceof NetworkTerminalState.AdministratorCandidates) || pending || !candidates.ready()) return;
        if (search.expanded()) closeLocalLayer();
        else {
            search.open();
            rebuild.run();
        }
    }

    boolean keyPressed(int keyCode, int modifiers) {
        if (search.openFromKey(
                keyCode,
                modifiers,
                state instanceof NetworkTerminalState.AdministratorCandidates && candidates.ready() && !pending)) {
            rebuild.run();
            return true;
        }
        return false;
    }

    boolean closeLocalLayer() {
        if (state instanceof NetworkTerminalState.AdministratorCandidates && search.close(clientTick)) {
            search.handled();
            query = "";
            listScroll = 0;
            searchError = null;
            rebuild.run();
            return true;
        }
        if (state instanceof NetworkTerminalState.Members && layout.compact() && compactDetails) {
            compactDetails = false;
            rebuild.run();
            return true;
        }
        return false;
    }

    void finishClick(boolean wasExpanded) {
        search.finishToggleClick(wasExpanded, owner, field);
        if (owner.getFocused() != null && !owner.children().contains(owner.getFocused())) owner.setFocused(null);
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (pending || state == null) return false;
        if (state instanceof NetworkTerminalState.AdministratorCandidates
                && contains(layout.content(), mouseX, mouseY)) {
            if (!candidates.ready()) return true;
            int count = candidates.filter(query).size();
            RoutingListLayout list = NodeRoutingView.tunnelList(layout.content(), search.expanded(), count, listScroll);
            int next = PagedListScroll.navigate(listScroll, count, list.visibleRows(), false, false, scrollY)
                    .scroll();
            if (next != listScroll) {
                listScroll = next;
                replaceCandidates();
            }
            return true;
        }
        if (state instanceof NetworkTerminalState.Members members) {
            TerminalMemberLayout panes = TerminalMemberLayout.calculate(layout);
            if ((!panes.compact() || !compactDetails) && contains(panes.list(), mouseX, mouseY)) {
                RoutingListLayout list = RoutingListLayout.calculate(
                        panes.list(), members.page().entries().size(), listScroll);
                PagedListScroll.Result result = PagedListScroll.navigate(
                        listScroll,
                        members.page().entries().size(),
                        list.visibleRows(),
                        members.page().hasPrevious(),
                        members.page().hasNext(),
                        scrollY);
                listScroll = result.scroll();
                if (result.pageRequest() != PagedListScroll.PageRequest.NONE) {
                    boolean backwards = result.pageRequest() == PagedListScroll.PageRequest.PREVIOUS;
                    landAtEnd = backwards;
                    UUID anchor = (backwards
                                    ? members.page().entries().getFirst()
                                    : members.page().entries().getLast())
                            .playerId();
                    actions.accept(new Action.PageMembers(anchor, backwards));
                } else rebuild.run();
                return true;
            }
            if ((!panes.compact() || compactDetails) && contains(panes.detail(), mouseX, mouseY)) {
                detailScroll =
                        Math.clamp(detailScroll + (scrollY < 0 ? 10 : scrollY > 0 ? -10 : 0), 0, detailMaximumScroll);
                return true;
            }
        }
        return false;
    }

    void render(GuiGraphics graphics, Font font, TerminalLayout layout) {
        if (state instanceof NetworkTerminalState.Members members) {
            TerminalMemberLayout panes = TerminalMemberLayout.calculate(layout);
            if (!panes.compact() || !compactDetails) {
                TerminalTheme.renderPanel(graphics, panes.list());
                header(graphics, font, panes.list(), text("title"));
                scrollbar(
                        graphics,
                        RoutingListLayout.calculate(
                                panes.list(), members.page().entries().size(), listScroll),
                        members.page().entries().size());
            }
            if (!panes.compact() || compactDetails)
                renderMember(graphics, font, panes.detail(), selectedMember(members));
        } else if (state instanceof NetworkTerminalState.AdministratorCandidates) {
            TerminalTheme.renderPanel(graphics, layout.content());
            header(graphics, font, layout.content(), text("candidates"));
            if (!candidates.ready()) {
                Component message = candidates.failed()
                        ? text("load_failed")
                        : Component.translatable(
                                "omniresonance.terminal.members.loading", candidates.received(), candidates.total());
                graphics.drawString(
                        font,
                        message,
                        layout.content().x() + 12,
                        layout.content().y() + 34,
                        TerminalTheme.MUTED,
                        false);
            } else {
                int count = candidates.filter(query).size();
                RoutingListLayout list =
                        NodeRoutingView.tunnelList(layout.content(), search.expanded(), count, listScroll);
                if (count == 0)
                    graphics.drawString(
                            font,
                            text("no_candidates"),
                            layout.content().x() + 12,
                            list.rows().y() + 8,
                            TerminalTheme.MUTED,
                            false);
                scrollbar(graphics, list, count);
            }
            if (searchError != null)
                graphics.drawString(
                        font,
                        searchError,
                        layout.content().x() + 8,
                        layout.content().bottom() - 14,
                        TerminalTheme.ERROR,
                        false);
        } else if (state instanceof NetworkTerminalState.RemoveAdministrator remove) {
            TerminalLayout.Rect modal = removalBounds();
            TerminalTheme.renderPanel(graphics, modal);
            header(graphics, font, modal, text("remove"));
            int y = modal.y() + 32;
            for (FormattedCharSequence line : font.split(
                    Component.translatable(
                            "omniresonance.terminal.members.remove_warning",
                            remove.target().name()),
                    modal.width() - 20)) {
                graphics.drawString(font, line, modal.x() + 10, y, TerminalTheme.MUTED, false);
                y += 10;
            }
        }
    }

    private void renderMember(
            GuiGraphics graphics, Font font, TerminalLayout.Rect detail, NetworkMemberSummary member) {
        TerminalTheme.renderPanel(graphics, detail);
        header(graphics, font, detail, Component.literal(member.name()));
        int top = detail.y() + 28;
        int bottom = detail.bottom() - 34;
        graphics.enableScissor(detail.x() + 8, top, detail.right() - 8, Math.max(top, bottom));
        int y = top - detailScroll;
        for (Component value : List.of(
                role(member),
                status(member),
                Component.literal(member.playerId().toString()),
                text("permissions"))) {
            for (FormattedCharSequence line : font.split(value, Math.max(1, detail.width() - 20))) {
                graphics.drawString(font, line, detail.x() + 10, y, TerminalTheme.MUTED, false);
                y += 10;
            }
            y += 8;
        }
        graphics.disableScissor();
        detailMaximumScroll = Math.max(0, y + detailScroll - bottom);
        detailScroll = Math.min(detailScroll, detailMaximumScroll);
    }

    private NetworkMemberSummary selectedMember(NetworkTerminalState.Members members) {
        for (NetworkMemberSummary member : members.page().entries())
            if (member.playerId().equals(selected)) return member;
        return members.page().entries().getFirst();
    }

    private static Component text(String key) {
        return Component.translatable("omniresonance.terminal.members." + key);
    }

    private static Component role(NetworkMemberSummary member) {
        return text(member.role() == NetworkMemberSummary.Role.OWNER ? "owner" : "administrator");
    }

    private static Component status(NetworkMemberSummary member) {
        return text(member.online() ? "online" : "offline");
    }

    private static void header(GuiGraphics graphics, Font font, TerminalLayout.Rect bounds, Component title) {
        TerminalText.drawHeaderTitle(graphics, font, title.getString(), TerminalHeaderLayout.contentTitle(bounds));
    }

    private static boolean contains(TerminalLayout.Rect rect, double x, double y) {
        return x >= rect.x() && x < rect.right() && y >= rect.y() && y < rect.bottom();
    }

    private static void scrollbar(GuiGraphics graphics, RoutingListLayout layout, int count) {
        TerminalTheme.renderScrollbar(
                graphics,
                layout.scrollbar().x(),
                layout.scrollbar().y(),
                layout.scrollbar().height(),
                count,
                layout.visibleRows(),
                layout.scroll());
    }
}
