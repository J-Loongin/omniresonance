// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class NodeEditorLabelsTest {
    @Test
    void channelEditorLabelsDescribeTunnelAndChannelRatherThanNetworkAndNode() {
        var labels = NodeRoutingView.editorLabels(NodeMenuInteractionPolicy.EditKind.CHANNEL);
        assertEquals("omniresonance.node_menu.tunnel", labels.context());
        assertEquals("omniresonance.node_menu.channel.name", labels.field());
    }

    @Test
    void nodeAndModeEditorsKeepTheirOwnLabels() {
        for (var kind : NodeMenuInteractionPolicy.EditKind.values()) {
            if (kind == NodeMenuInteractionPolicy.EditKind.CHANNEL) continue;
            var labels = NodeRoutingView.editorLabels(kind);
            assertEquals("omniresonance.node_menu.network", labels.context());
            assertEquals(
                    kind == NodeMenuInteractionPolicy.EditKind.MODE
                            ? "omniresonance.node_menu.mode"
                            : "omniresonance.node_menu.name",
                    labels.field());
        }
    }
}
