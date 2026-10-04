// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

final class TerminalResourceParameterViewTest {
    @Test
    void clearedNativeQuantityIsInvalidAndDoesNotRestoreDefaultsImplicitly() {
        var editor = new ResourceParameterDraft(
                io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.InputOverride(
                        128, io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.BatchMode.GREEDY, 64));
        var input = TerminalResourceParameterView.build(
                font(),
                TerminalResourceParameterLayout.of(
                        TerminalLayout.terminal(640, 360).content(), true, false),
                editor.form(Component.literal("items"), true, true),
                editor.bindings(() -> {}, () -> {}),
                new TerminalResourceParameterView.Actions(() -> {}, () -> {}, () -> {}),
                ignored -> {});
        input.setValue("");
        assertEquals("", editor.rate);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, editor::value);
        assertEquals("64", editor.batch);
    }

    @Test
    void sameBuilderBindsBothOwnersAndKeepsReadOnlyBatchAndDraftActions() {
        var body = TerminalLayout.terminal(640, 360).content();
        for (boolean batch : new boolean[] {false, true}) {
            var widgets = new ArrayList<AbstractWidget>();
            String[] values = {"128", "64"};
            int[] actions = {0, 0, 0, 0};
            var form = TerminalResourceParameterView.rateForm(
                    Component.literal("Items"),
                    Component.literal("items"),
                    values[0],
                    64,
                    true,
                    batch
                            ? new TerminalResourceParameterView.Batch(Component.literal("Greedy"), values[1], false)
                            : null);
            var input = TerminalResourceParameterView.build(
                    font(),
                    TerminalResourceParameterLayout.of(body, batch, false),
                    form,
                    new TerminalResourceParameterView.Bindings(
                            value -> values[0] = value, () -> actions[3]++, value -> values[1] = value),
                    new TerminalResourceParameterView.Actions(
                            () -> actions[0]++, () -> actions[1]++, () -> actions[2]++),
                    widgets::add);
            input.setValue("256");
            assertEquals("256", values[0]);
            assertEquals(batch ? 6 : 4, widgets.size());
            if (batch) {
                assertFalse(widgets.get(2).active);
                ((Button) widgets.get(1)).onPress();
                assertEquals(1, actions[3]);
            }
            for (int i = 0; i < 3; i++) {
                var button = assertInstanceOf(TerminalButton.class, widgets.get(widgets.size() - 3 + i));
                button.onPress();
                assertEquals(1, actions[i]);
            }
        }
    }

    private static Font font() {
        return new Font(id -> null, false) {
            @Override
            public int width(String text) {
                return text.length() * 6;
            }

            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.clamp(width / 6, 0, text.length()));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean tail) {
                int count = Math.clamp(width / 6, 0, text.length());
                return tail ? text.substring(text.length() - count) : text.substring(0, count);
            }
        };
    }
}
