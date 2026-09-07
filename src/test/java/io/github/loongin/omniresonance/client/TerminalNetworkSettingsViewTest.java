// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NetworkSettingsSummary;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TerminalNetworkSettingsViewTest {
    private static final UUID NETWORK = new UUID(880, 1);
    private static final UUID OWNER = new UUID(880, 2);

    @Test
    void normalWindowUsesNonoverlappingThirtyFiveSixtyFivePanes() {
        TerminalLayout terminal = TerminalLayout.calculate(640, 360);
        TerminalNetworkSettingsView.Layout layout = TerminalNetworkSettingsView.calculate(terminal);
        int usable = terminal.content().width() - TerminalLayout.GAP;

        assertFalse(layout.compact());
        assertEquals(usable * 35 / 100, layout.information().width());
        assertEquals(terminal.content().right(), layout.actions().right());
        assertEquals(
                layout.information().right() + TerminalLayout.GAP,
                layout.actions().x());
        assertEquals(terminal.content().height(), layout.information().height());
        assertEquals(terminal.content().height(), layout.actions().height());
    }

    @Test
    void compactWindowStacksFullWidthPanesInsideTheFixedBody() {
        TerminalLayout terminal = TerminalLayout.calculate(320, 240);
        TerminalNetworkSettingsView.Layout layout = TerminalNetworkSettingsView.calculate(terminal);

        assertTrue(layout.compact());
        assertEquals(terminal.content().x(), layout.information().x());
        assertEquals(terminal.content().width(), layout.information().width());
        assertEquals(terminal.content().width(), layout.actions().width());
        assertEquals(
                layout.information().bottom() + TerminalLayout.GAP,
                layout.actions().y());
        assertEquals(terminal.content().bottom(), layout.actions().bottom());
        assertTrue(layout.information().height() >= 96);
        assertTrue(layout.actions().height() >= 68);
    }

    @Test
    void ownerAndAdministratorModelsExposeOnlyTheirAllowedActions() {
        NetworkSettingsSummary owner = summary(true, false);
        NetworkSettingsSummary administrator = summary(false, false);

        TerminalNetworkSettingsView.Model ownerModel = TerminalNetworkSettingsView.model(owner);
        assertTrue(ownerModel.renameEnabled());
        assertTrue(ownerModel.showDefaultAction());
        assertTrue(ownerModel.setDefaultEnabled());
        assertTrue(ownerModel.showDeleteAction());

        TerminalNetworkSettingsView.Model administratorModel = TerminalNetworkSettingsView.model(administrator);
        assertTrue(administratorModel.renameEnabled());
        assertFalse(administratorModel.showDefaultAction());
        assertFalse(administratorModel.setDefaultEnabled());
        assertFalse(administratorModel.showDeleteAction());

        assertFalse(TerminalNetworkSettingsView.model(summary(true, true)).setDefaultEnabled());
    }

    private static NetworkSettingsSummary summary(boolean ownerActions, boolean defaultNetwork) {
        return new NetworkSettingsSummary(
                new NetworkSummary(NETWORK, OWNER, "Network"), "Owner", 0, 0, ownerActions, defaultNetwork);
    }
}
