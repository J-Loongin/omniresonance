// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ExchangeTunnelViewTest {
    @Test
    void boundedPairAndChannelViewsRoundTripAndRejectForeignPerspective() {
        UUID id = new UUID(1, 1), a = new UUID(2, 1), b = new UUID(2, 2);
        var view = new ExchangeTunnelView(id, 3, a, b, "Main", "Peer", true, false, false, true);
        var channel = new ExchangeChannelView(id, "Iron");
        var bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            view.write(bytes);
            channel.write(bytes);
            assertEquals(view, ExchangeTunnelView.read(bytes));
            assertEquals(channel, ExchangeChannelView.read(bytes));
            assertEquals(b, view.peer(a));
            assertThrows(IllegalArgumentException.class, () -> view.peer(id));
        } finally {
            bytes.release();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeTunnelView(id, 3, a, b, null, "Peer", true, false, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ExchangeChannelView(id, " Iron "));
    }
}
