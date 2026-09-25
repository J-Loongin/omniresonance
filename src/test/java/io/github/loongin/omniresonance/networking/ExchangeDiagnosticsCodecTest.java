// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.exchange.ExchangeTelemetry;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ExchangeDiagnosticsCodecTest {
    @Test
    void measurementsAndDomainIncidentRoundTripWithoutANodeOrResourceKey() {
        var incident = new ExchangeTelemetry.Incident(
                new UUID(1, 1),
                new UUID(2, 1),
                new UUID(2, 2),
                null,
                40,
                ExchangeTelemetry.Stage.TARGET_STORAGE,
                ExchangeTelemetry.Reason.STORAGE_UNAVAILABLE,
                "Iron",
                "Main",
                "Test");
        var snapshot = new ExchangeTelemetry.Snapshot(true, true, 3, 5, false, incident);
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ExchangeDiagnosticsCodec.write(b, snapshot);
            assertEquals(snapshot, ExchangeDiagnosticsCodec.read(b));
            b.clear();
            b.writeByte(2);
            assertThrows(IllegalArgumentException.class, () -> ExchangeDiagnosticsCodec.read(b));
        } finally {
            b.release();
        }
    }
}
