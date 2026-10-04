// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Detached scope/type overlay widgets, sharing existing search and wheel-list geometry. The Screen supplies its
 * global magnifier, input routing, modal backdrop and focus handling; this view creates no extra titlebar controls.
 * Search updates replace rows only. Scope has explicit local Apply/Cancel; the type picker has no save footer.
 */
final class ResourceTypeSelectionView {
    record Layout(
            TerminalLayout.Rect body, TerminalLayout.Rect kind, TerminalLayout.Rect actions, RoutingListLayout list) {}

    private ResourceTypeSelectionView() {}

    static Layout layout(TerminalLayout.Rect body, ResourceTypeSelection selection) {
        boolean scope = selection.scope() != null;
        int header = scope ? 28 : 0;
        int footer = scope ? 34 : 0;
        var listBody = new TerminalLayout.Rect(
                body.x(), body.y() + header, body.width(), Math.max(0, body.height() - header - footer));
        var list = selection.search().expanded()
                ? NodeRoutingView.tunnelList(listBody, true, selection.results().size(), selection.scroll())
                : RoutingListLayout.calculateRows(
                        new TerminalLayout.Rect(
                                listBody.x(),
                                listBody.y() + (scope ? 0 : 4),
                                listBody.width(),
                                Math.max(0, listBody.height() - (scope ? 0 : 4))),
                        selection.results().size(),
                        selection.scroll());
        selection.viewport(list.visibleRows());
        return new Layout(
                listBody, new TerminalLayout.Rect(body.x() + 4, body.y() + 4, body.width() - 8, 20), body, list);
    }

    @Nullable
    static TerminalSearchBox buildSearch(
            Font font,
            Layout layout,
            ResourceTypeSelection selection,
            LongSupplier currentTick,
            Runnable queryChanged) {
        if (!selection.search().expanded()) return null;
        var bounds = NodePresetPickerView.search(layout.body());
        var field = selection.search().field(font, bounds, NodeResourcePolicyView.text("search"), 256, value -> {
            selection.editSearch(value, currentTick.getAsLong());
            queryChanged.run();
        });
        return field;
    }

    static void buildRows(
            Layout layout,
            ResourceTypeSelection selection,
            boolean active,
            Consumer<TerminalRowButton> add,
            Runnable changed) {
        int end = Math.min(
                selection.results().size(), selection.scroll() + layout.list().visibleRows());
        for (int index = selection.scroll(); index < end; index++) {
            ResourceLocation id = selection.results().get(index);
            Component label = selection.unavailable(id)
                    ? NodeResourcePolicyView.text("unavailable_type", id.toString())
                    : NodeResourcePolicyView.typeName(id);
            var row = new TerminalRowButton(layout.list().row(index - selection.scroll()), label, ignored -> {
                selection.choose(id);
                changed.run();
            });
            row.active = active;
            if (selection.unavailable(id))
                row.statusSuffix(Component.literal(id.toString()), NodeResourcePolicyView.text("unavailable_type", ""));
            if (selection.scope() != null) row.setSelected(selection.scope().selected(id));
            row.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label.getString() + "\n" + id))));
            add.accept(row);
        }
    }

    static void buildScopeActions(
            Layout layout,
            ResourceTypeSelection selection,
            boolean active,
            Consumer<AbstractWidget> add,
            Runnable selectionChanged,
            Runnable apply,
            Runnable cancel) {
        ResourceScopeSelectionDraft scope = selection.scope();
        if (scope == null) return;
        int half = (layout.kind().width() - 8) / 2;
        NodeResourcePolicyView.button(
                        add,
                        new TerminalLayout.Rect(layout.kind().x(), layout.kind().y(), half, 20),
                        NodeResourcePolicyView.text("scope_all"),
                        active,
                        () -> {
                            scope.all();
                            selectionChanged.run();
                        })
                .setSelected(scope.kind() == io.github.loongin.omniresonance.transfer.ResourceScope.Kind.ALL);
        NodeResourcePolicyView.button(
                        add,
                        new TerminalLayout.Rect(
                                layout.kind().x() + half + 8,
                                layout.kind().y(),
                                layout.kind().width() - half - 8,
                                20),
                        NodeResourcePolicyView.text("scope_custom", scope.selectedCount()),
                        active,
                        () -> {
                            scope.custom();
                            selectionChanged.run();
                        })
                .setSelected(scope.kind() == io.github.loongin.omniresonance.transfer.ResourceScope.Kind.CUSTOM_SET);
        NodeResourcePolicyView.button(
                add,
                TerminalActionLayout.of(layout.actions()).secondary(),
                NodeResourcePolicyView.text("cancel"),
                active,
                cancel);
        var bounds = TerminalActionLayout.of(layout.actions()).primary();
        var submit = new TerminalButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                NodeResourcePolicyView.text("apply"),
                ignored -> apply.run(),
                true);
        submit.active = active
                && (scope.kind() == io.github.loongin.omniresonance.transfer.ResourceScope.Kind.ALL
                        || scope.selectedCount() > 0);
        add.accept(submit);
    }

    static void render(
            GuiGraphics graphics, Font font, TerminalLayout.Rect body, ResourceTypeSelection selection, long tick) {
        var rows = layout(body, selection).list();
        TerminalTheme.renderScrollbar(
                graphics,
                rows.scrollbar().x(),
                rows.scrollbar().y(),
                rows.scrollbar().height(),
                selection.results().size(),
                rows.visibleRows(),
                selection.scroll());
        if (!selection.results().isEmpty() || selection.search().due(tick)) return;
        String query = ClientSearchState.normalizedQuery(selection.search().draft());
        if (query == null) return;
        Component message = NodeResourcePolicyView.text(
                !query.isBlank()
                        ? "no_matching_types"
                        : selection.scope() == null ? "no_addable_types" : "no_selectable_types");
        NodeResourcePolicyView.label(
                graphics,
                font,
                rows.rows().x() + 4,
                rows.rows().y() + 8,
                Math.max(0, rows.rows().width() - 8),
                message);
    }
}
