// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.gui.Font;
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
final class NodeResourceTypeSelectionView {
    record Layout(
            TerminalLayout.Rect body, TerminalLayout.Rect kind, TerminalLayout.Rect actions, RoutingListLayout list) {}

    private NodeResourceTypeSelectionView() {}

    static Layout layout(TerminalLayout.Rect body, NodeResourceTypeSelection selection) {
        boolean scope = selection.scope() != null;
        int header = scope ? 28 : 0;
        int footer = scope ? 34 : 0;
        var listBody = new TerminalLayout.Rect(
                body.x(), body.y() + header, body.width(), Math.max(0, body.height() - header - footer));
        var list = NodeRoutingView.tunnelList(
                listBody, selection.search().expanded(), selection.results().size(), selection.scroll());
        selection.viewport(list.visibleRows());
        return new Layout(
                listBody, new TerminalLayout.Rect(body.x() + 4, body.y() + 4, body.width() - 8, 20), body, list);
    }

    @Nullable
    static TerminalSearchBox buildSearch(
            Font font,
            Layout layout,
            NodeResourceTypeSelection selection,
            LongSupplier currentTick,
            Runnable queryChanged) {
        if (!selection.search().expanded()) return null;
        var bounds = NodePresetPickerView.search(layout.body());
        var field = new TerminalSearchBox(
                font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), NodeResourcePolicyView.text("search"));
        field.setMaxLength(256);
        field.setHint(TerminalText.body(NodeResourcePolicyView.text("search")));
        field.setValue(selection.search().draft());
        field.setResponder(value -> {
            selection.editSearch(value, currentTick.getAsLong());
            queryChanged.run();
        });
        return field;
    }

    static void buildRows(
            Layout layout,
            NodeResourceTypeSelection selection,
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
            if (selection.scope() != null) row.setSelected(selection.scope().selected(id));
            row.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label.getString() + "\n" + id))));
            add.accept(row);
        }
    }

    static void buildScopeActions(
            Layout layout,
            NodeResourceTypeSelection selection,
            boolean active,
            Consumer<AbstractWidget> add,
            Runnable selectionChanged,
            Runnable apply,
            Runnable cancel) {
        NodeResourceScopeDraft scope = selection.scope();
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
                });
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
                });
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
}
