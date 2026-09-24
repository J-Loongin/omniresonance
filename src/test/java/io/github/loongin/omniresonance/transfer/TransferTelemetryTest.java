// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferTelemetryTest {
    @Test
    void incidentKeepsItsSourceAndReasonWithoutRetainingAnExceptionOrStaleContext() {
        var telemetry = new TransferTelemetry(List.of(ResourceTypes.ITEM));
        var network = new UUID(1, 1);
        var incident = TransferIncident.of(
                new UUID(2, 2),
                new UUID(3, 3),
                new UUID(4, 4),
                ResourceTypes.ITEM,
                TransferIncident.Reason.SLOW_CALL,
                ResourceTransferEngine.Stage.NONE);
        telemetry.failure(network, 10, "direct_transfer", incident);
        assertEquals(incident, telemetry.snapshot(network, 10).incident());
        assertEquals(
                TransferIncident.Reason.SLOW_CALL,
                telemetry.snapshot(network, 11).incident().reason());
        telemetry.failure(network, 12, "domain_input");
        assertEquals(null, telemetry.snapshot(network, 12).incident());
    }

    @Test
    void measurementsExpireWithoutReadSideEffectsAndSaturateOnlyDiagnostics() {
        var telemetry = new TransferTelemetry(List.of(ResourceTypes.ITEM));
        var network = new UUID(1, 1);
        telemetry.work(network, 10, 2, 50);
        telemetry.work(network, 10, 3, 70);
        telemetry.moved(network, ResourceTypes.ITEM, 10, Long.MAX_VALUE);
        telemetry.moved(network, ResourceTypes.ITEM, 10, 1);
        var first = telemetry.snapshot(network, 10);
        assertEquals(5, first.calls());
        assertEquals(120, first.nanos());
        assertEquals(Long.MAX_VALUE, first.moved().getFirst().amount());
        assertTrue(first.moved().getFirst().saturated());
        assertEquals(first, telemetry.snapshot(network, 10));
        assertEquals(0, telemetry.snapshot(network, 11).calls());
        assertEquals(1, telemetry.snapshot(network, 29).moved().size());
        assertTrue(telemetry.snapshot(network, 30).moved().isEmpty());
        telemetry.remove(network);
        assertTrue(telemetry.snapshot(network, 10).moved().isEmpty());
    }
}
