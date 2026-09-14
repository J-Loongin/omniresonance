// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Client-owned header context and transient dropdown widgets, discarded when leaving the network home. */
final class TerminalNetworkContext {
    private static final TerminalLayout.Rect EMPTY_BOUNDS = new TerminalLayout.Rect(0, 0, 0, 0);
    private @Nullable NetworkTerminalState state;
    boolean open;
    TerminalLayout.Rect bounds = EMPTY_BOUNDS;
    final List<TerminalButton> buttons = new ArrayList<>();

    void apply(@Nullable NetworkTerminalState next) {
        NetworkSummary previous = network();
        state = next;
        if (!selectable() || previous == null || !previous.id().equals(network().id())) clearDropdown();
    }

    void clearDropdown() {
        open = false;
        bounds = EMPTY_BOUNDS;
        for (TerminalButton button : buttons) {
            button.active = false;
            button.visible = false;
        }
        buttons.clear();
    }

    boolean selectable() {
        return state instanceof NetworkTerminalState.NetworkRoot;
    }

    boolean intercepts() {
        if (!selectable()) clearDropdown();
        return open;
    }

    @Nullable
    TerminalButton buildSelector(TerminalLayout.Rect bounds, String text, boolean enabled, Runnable rebuild) {
        if (!selectable()) return null;
        java.util.UUID networkId = network().id();
        TerminalButton selector = new TerminalButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                Component.literal(text),
                ignored -> {
                    if (!selectable() || !enabled || !networkId.equals(network().id())) return;
                    open = !open;
                    rebuild.run();
                },
                false);
        selector.active = enabled;
        selector.setSelected(open);
        selector.setTooltip(
                Tooltip.create(TerminalText.body(Component.literal(network().name()))));
        return selector;
    }

    Component label() {
        return Component.literal(network().name());
    }

    @Nullable
    NetworkSummary network() {
        return switch (state) {
            case null -> null;
            case NetworkTerminalState.NetworkRoot value -> value.network();
            case NetworkTerminalState.TunnelList value -> value.network();
            case NetworkTerminalState.TunnelEdit value -> value.network();
            case NetworkTerminalState.ChannelList value -> value.network();
            case NetworkTerminalState.TunnelSettings value -> value.network();
            case NetworkTerminalState.DeleteConfirmation value -> value.network();
            case NetworkTerminalState.Filters value -> value.network();
            case NetworkTerminalState.Preset value -> value.network();
            case NetworkTerminalState.PresetEdit value -> value.network();
            case NetworkTerminalState.Members value -> value.network();
            case NetworkTerminalState.AdministratorCandidates value -> value.network();
            case NetworkTerminalState.RemoveAdministrator value -> value.network();
            case NetworkTerminalState.NetworkSettings value -> value.settings().network();
            case NetworkTerminalState.NetworkRename value -> value.settings().network();
            case NetworkTerminalState.NetworkDelete value -> value.settings().network();
        };
    }

    static TerminalLayout.Rect layout(TerminalLayout.Rect remaining, boolean compact, boolean selectable) {
        int width = Math.min(remaining.width(), compact ? 72 : 110);
        return new TerminalLayout.Rect(remaining.right() - width, remaining.y(), width, 20);
    }
}
