// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** One parameter form builder and painter for node and exchange owners; optional batch is data, not a second UI. */
final class TerminalResourceParameterView {
    private TerminalResourceParameterView() {}

    record Batch(Component mode, String quantity, boolean quantityActive) {}

    record Form(
            Component title,
            Component unit,
            String rate,
            int maximumLength,
            boolean active,
            @Nullable Batch batch) {
        Form withRate(String value) {
            return rateForm(title, unit, value, maximumLength, active, batch);
        }
    }

    static Form rateForm(
            Component title, Component unit, String rate, int maximumLength, boolean active, @Nullable Batch batch) {
        return new Form(title, unit, rate, maximumLength, active, batch);
    }

    record Bindings(
            Consumer<String> rateChanged,
            @Nullable Runnable toggle,
            @Nullable Consumer<String> batchChanged) {}

    record Actions(Runnable restore, Runnable cancel, Runnable apply) {}

    static TerminalEditBox build(
            Font font,
            TerminalResourceParameterLayout geometry,
            Form form,
            Bindings bindings,
            Actions actions,
            Consumer<AbstractWidget> add) {
        var r = geometry.rate();
        var field = new TerminalEditBox(font, r.x(), r.y(), r.width(), r.height(), NodeResourcePolicyView.text("rate"));
        field.setMaxLength(form.maximumLength());
        field.setValue(form.rate());
        field.setEditable(form.active());
        field.active = form.active();
        field.setResponder(bindings.rateChanged());
        field.setTooltip(
                Tooltip.create(TerminalText.body(NodeResourcePolicyView.text("shared_rate_help", form.unit()))));
        add.accept(field);
        if (form.batch() != null) {
            if (bindings.toggle() == null || bindings.batchChanged() == null)
                throw new IllegalArgumentException("Input batch requires both bindings");
            var batch = form.batch();
            add.accept(TerminalFormGrid.field(geometry.mode(), batch.mode(), form.active(), bindings.toggle()));
            NodeResourcePolicyView.field(
                    font,
                    add,
                    geometry.batch(),
                    NodeResourcePolicyView.text("batch"),
                    batch.quantity(),
                    form.active() && batch.quantityActive(),
                    NodeResourcePolicyView.text("batch.help"),
                    bindings.batchChanged());
        }
        Runnable[] callbacks = {actions.restore(), actions.cancel(), actions.apply()};
        String[] keys = {"restore_default", "cancel", "apply"};
        for (int i = 0; i < 3; i++) {
            var bounds = TerminalActionLayout.button(geometry.dialog(), 3, i);
            int index = i;
            var button = new TerminalButton(
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    NodeResourcePolicyView.text(keys[i]),
                    ignored -> callbacks[index].run(),
                    i == 2);
            button.active = form.active();
            button.setTooltip(
                    Tooltip.create(TerminalText.body(NodeResourcePolicyView.text("parameter_" + keys[i] + "_help"))));
            add.accept(button);
        }
        return field;
    }

    static void render(
            GuiGraphics graphics,
            Font font,
            TerminalResourceParameterLayout geometry,
            Form form,
            @Nullable Component error) {
        var d = geometry.dialog();
        TerminalTheme.renderDialogPanel(graphics, d);
        TerminalText.drawDialogTitle(graphics, font, form.title(), d);
        NodeResourcePolicyView.label(
                graphics,
                font,
                geometry.rate().x(),
                d.y() + 30,
                geometry.rate().width(),
                NodeResourcePolicyView.text("rate_unit", form.unit()));
        if (form.batch() != null) {
            NodeResourcePolicyView.label(
                    graphics,
                    font,
                    geometry.mode().x(),
                    geometry.mode().y() - 12,
                    geometry.mode().width(),
                    NodeResourcePolicyView.text("batch_mode"));
            NodeResourcePolicyView.label(
                    graphics,
                    font,
                    geometry.batch().x(),
                    geometry.batch().y() - 12,
                    geometry.batch().width(),
                    NodeResourcePolicyView.text("batch"));
        }
        if (error != null)
            TerminalDialogLayout.renderFieldError(
                    graphics,
                    font,
                    form.batch() == null
                            ? geometry.rate()
                            : new TerminalLayout.Rect(
                                    geometry.mode().x(),
                                    geometry.mode().y(),
                                    geometry.rate().width(),
                                    geometry.mode().height()),
                    error,
                    TerminalActionLayout.of(d).primary().y());
        else geometry.renderHint(graphics, font, NodeResourcePolicyView.text("parameter_draft_help"));
    }
}
