// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

final class ResourceSettingsEntryTest {
    @Test
    void exchangeParentEntryUsesTheNodeDefaultsAndOverrideSummaryForZeroAndNonzeroCounts() {
        for (int count : new int[] {0, 1, 3}) {
            Component expected = NodeResourcePolicyView.text(
                    "settings_entry",
                    count == 0
                            ? NodeResourcePolicyView.text("defaults")
                            : NodeResourcePolicyView.text("override_count", count));
            assertEquals(expected, TerminalResourceSettingsList.entryLabel(count));
        }
    }

    @Test
    void actualParentEntryFactoryUsesTheSameControlTooltipAndInactiveInputGuard() {
        var bounds = new TerminalLayout.Rect(20, 40, 330, 20);
        int[] opens = {0};
        var node = TerminalResourceSettingsList.entry(bounds, 0, true, () -> opens[0]++);
        var exchange = TerminalResourceSettingsList.entry(bounds, 0, true, () -> opens[0]++);
        assertEquals(node.getMessage(), exchange.getMessage());
        assertEquals(node.getWidth(), exchange.getWidth());
        org.junit.jupiter.api.Assertions.assertNotNull(node.getTooltip());
        org.junit.jupiter.api.Assertions.assertNotNull(exchange.getTooltip());
        node.onPress();
        exchange.onPress();
        assertEquals(2, opens[0]);
        var inactive = TerminalResourceSettingsList.entry(bounds, 0, false, () -> opens[0]++);
        inactive.onPress();
        assertEquals(2, opens[0]);
    }
}
