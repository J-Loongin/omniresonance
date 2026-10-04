// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TerminalResourceSettingsListTest {
    @Test
    void bothOwnersUseTheSameAddTargetHintAndRowWidthsWithoutASeparateTextAction() {
        var body = TerminalLayout.terminal(640, 360).content();
        var node = TerminalResourceSettingsList.page(body, 4, 0);
        var exchange = TerminalResourceSettingsList.page(body, 5, 0);
        assertEquals(node.list().row(0).x(), exchange.list().row(0).x());
        assertEquals(node.list().row(0).width(), exchange.list().row(0).width());
        assertEquals(node.list().row(0).y(), exchange.list().row(0).y());
        assertEquals(node.notice(), exchange.notice());
        assertEquals(node.list().rows(), exchange.list().rows());
        int[] calls = {0};
        var bounds = TerminalHeaderLayout.atRightEdge(
                        TerminalHeaderLayout.topBarContent(
                                TerminalLayout.terminal(640, 360).window()),
                        true)
                .action();
        var first = TerminalResourceSettingsList.add(bounds, true, () -> calls[0]++);
        var second = TerminalResourceSettingsList.add(bounds, true, () -> calls[0]++);
        assertEquals(first.getMessage(), second.getMessage());
        assertEquals(20, first.getWidth());
        first.onPress();
        second.onPress();
        assertEquals(2, calls[0]);
    }
}
