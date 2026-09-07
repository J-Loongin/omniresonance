// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.loongin.omniresonance.networking.NodeChannelSummary;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

final class NodeRoutingLabelTest {
    @Test
    void enabledTunnelOmitsCountsAndDefaultStatus() {
        Component label = NodeRoutingView.tunnelLabel(tunnel(true));

        PlainTextContents contents = assertInstanceOf(PlainTextContents.class, label.getContents());
        assertEquals("Ore processing", contents.text());
    }

    @Test
    void disabledTunnelKeepsOnlyItsNameAndRestriction() {
        Component label = NodeRoutingView.tunnelLabel(tunnel(false));

        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, label.getContents());
        assertEquals("omniresonance.node_menu.tunnel.summary.disabled", contents.getKey());
        assertArrayEquals(new Object[] {"Ore processing"}, contents.getArgs());
    }

    @Test
    void channelOmitsGlobalCountsButKeepsCurrentNodeState() {
        NodeChannelSummary channel = new NodeChannelSummary(new UUID(2, 1), "Items", 0, 41, 37, null);

        Component label = NodeRoutingView.channelLabel(channel, "Not joined");

        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, label.getContents());
        assertEquals("omniresonance.node_menu.channel.summary", contents.getKey());
        assertArrayEquals(new Object[] {"Items", "Not joined"}, contents.getArgs());
    }

    private static NodeTunnelSummary tunnel(boolean enabled) {
        return new NodeTunnelSummary(new UUID(1, 1), "Ore processing", 0, enabled, 23, 19, 7);
    }
}
