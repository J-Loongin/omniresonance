// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ResourceParameterPageTest {
    @Test
    void exchangeParameterPageHasTheSameViewportAsTheNodePageWithoutItsOwnFooter() {
        var body = TerminalLayout.terminal(640, 360).content();
        assertEquals(
                TerminalResourceSettingsList.page(body, 2, 0).list(), ExchangeScreen.parameterPageLayout(body, 2, 0));
    }

    @Test
    void onePageBuilderOnlyCreatesConfiguredTypeRowsAndNoAdditionalDefaultOrFooterWidgets() {
        var widgets = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        var body = TerminalLayout.terminal(640, 360).content();
        int[] opens = {0};
        var entries = new TerminalResourceSettingsList.Entries() {
            @Override
            public int size() {
                return 2;
            }

            @Override
            public TerminalResourceSettingsList.Row row(int index) {
                var label = net.minecraft.network.chat.Component.literal(index == 0 ? "Items" : "Fluid");
                return new TerminalResourceSettingsList.Row(label, label, null, () -> opens[0]++);
            }
        };
        TerminalResourceSettingsList.buildRows(body, entries, 0, true, widgets::add);
        assertEquals(2, widgets.size());
        for (var widget : widgets) {
            org.junit.jupiter.api.Assertions.assertInstanceOf(TerminalRowButton.class, widget);
            ((net.minecraft.client.gui.components.Button) widget).onPress();
        }
        assertEquals(2, opens[0]);
    }
}
