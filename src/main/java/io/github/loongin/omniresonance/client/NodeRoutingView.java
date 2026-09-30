// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeChannelSummary;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/** Node-specific labels and search placement, using the shared routing-row geometry. */
final class NodeRoutingView {
    record EditorLabels(String context, String field) {}

    static EditorLabels editorLabels(NodeMenuInteractionPolicy.EditKind kind) {
        if (kind == NodeMenuInteractionPolicy.EditKind.CHANNEL)
            return new EditorLabels("omniresonance.node_menu.tunnel", "omniresonance.node_menu.channel.name");
        return new EditorLabels(
                "omniresonance.node_menu.network",
                kind == NodeMenuInteractionPolicy.EditKind.MODE
                        ? "omniresonance.node_menu.mode"
                        : "omniresonance.node_menu.name");
    }

    private static final int SEARCH_TOP = 18;
    private static final int SEARCH_HEIGHT = 20;

    private NodeRoutingView() {}

    static TerminalLayout.Rect tunnelSearchBounds(TerminalLayout.Rect body) {
        return TerminalSearchBox.bounds(body, body.y() + SEARCH_TOP);
    }

    static RoutingListLayout tunnelList(
            TerminalLayout.Rect body, boolean searchExpanded, int entryCount, int requestedScroll) {
        if (!searchExpanded) {
            return RoutingListLayout.calculate(body, entryCount, requestedScroll);
        }
        int top = tunnelSearchBounds(body).bottom() + TerminalLayout.GAP;
        TerminalLayout.Rect listBody =
                new TerminalLayout.Rect(body.x(), top, body.width(), Math.max(0, body.bottom() - top));
        return RoutingListLayout.calculateRows(listBody, entryCount, requestedScroll);
    }

    static Component tunnelLabel(NodeTunnelSummary tunnel) {
        Objects.requireNonNull(tunnel, "tunnel");
        return tunnel.enabled()
                ? Component.literal(tunnel.name())
                : Component.translatable("omniresonance.node_menu.tunnel.summary.disabled", tunnel.name());
    }

    static Component channelLabel(NodeChannelSummary channel, String currentNodeState) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(currentNodeState, "currentNodeState");
        return Component.translatable("omniresonance.node_menu.channel.summary", channel.name(), currentNodeState);
    }
}
