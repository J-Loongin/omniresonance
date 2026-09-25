// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkDiagnosticsSnapshotTest {
    @Test
    void incidentMetadataUsesOnlyTheCurrentNetworkAndNeverLoadsAWorldOrDirtiesData() {
        var node = new UUID(3, 3);
        var peer = new UUID(4, 4);
        var data = io.github.loongin.omniresonance.persistence.NetworkSavedData.create(
                new NetworkMetadata(new UUID(1, 1), new UUID(2, 2), new ManagedName("Network"), 0, java.util.Set.of()));
        var position = net.minecraft.core.GlobalPos.of(
                net.minecraft.world.level.Level.OVERWORLD, new net.minecraft.core.BlockPos(1, 70, 3));
        data.createNode(
                node,
                new ManagedName("Known node"),
                position,
                io.github.loongin.omniresonance.node.NodeForm.BLOCK,
                net.minecraft.core.Direction.DOWN);
        data.setDirty(false);
        var incident = io.github.loongin.omniresonance.transfer.TransferIncident.of(
                node,
                peer,
                null,
                io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                io.github.loongin.omniresonance.transfer.TransferIncident.Reason.EXCEPTION,
                io.github.loongin.omniresonance.transfer.ResourceTransferEngine.Stage.NONE);
        var stats = new NetworkDiagnosticsSnapshot.RuntimeStats(
                new io.github.loongin.omniresonance.transfer.TransferTelemetry.Snapshot(
                        10, 0, 0, java.util.List.of(), 0, "direct_transfer", 9, incident),
                0,
                0,
                0,
                new io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Snapshot(true, true, 2, 4, false, null));
        org.junit.jupiter.api.Assertions.assertEquals(
                stats.exchange(), NetworkDiagnosticsService.enrich(data, stats).exchange());
        var enriched = NetworkDiagnosticsService.enrich(data, stats).transfers().incident();
        org.junit.jupiter.api.Assertions.assertEquals(
                "Known node", enriched.node().name());
        org.junit.jupiter.api.Assertions.assertEquals(position, enriched.node().position());
        org.junit.jupiter.api.Assertions.assertEquals("", enriched.peer().name());
        org.junit.jupiter.api.Assertions.assertEquals(null, enriched.peer().position());
        assertFalse(data.isDirty());
        data.removeNode(node, position);
        data.setDirty(false);
        var removed = NetworkDiagnosticsService.enrich(data, stats).transfers().incident();
        org.junit.jupiter.api.Assertions.assertEquals(node, removed.node().id());
        org.junit.jupiter.api.Assertions.assertEquals("", removed.node().name());
        org.junit.jupiter.api.Assertions.assertEquals(null, removed.node().position());
        assertFalse(data.isDirty());
    }

    @Test
    void administrativeListingIsBoundedAndOwnerFilteredWithoutChangingTheIndex() {
        var owner = new UUID(4, 1);
        var metadata = new java.util.ArrayList<NetworkMetadata>();
        for (int i = 0; i < 300; i++)
            metadata.add(
                    new NetworkMetadata(new UUID(5, i), owner, new ManagedName("Network " + i), i, java.util.Set.of()));
        var directory = new NetworkDirectory(metadata);
        var listed = directory.diagnosticList(owner, 128);
        org.junit.jupiter.api.Assertions.assertEquals(128, listed.entries().size());
        org.junit.jupiter.api.Assertions.assertEquals(300, listed.total());
        org.junit.jupiter.api.Assertions.assertEquals(300, directory.ownedCount(owner));
        org.junit.jupiter.api.Assertions.assertEquals(
                0, directory.diagnosticList(new UUID(4, 2), 128).total());
        assertThrows(IllegalArgumentException.class, () -> directory.diagnosticList(null, 129));
    }

    @Test
    void exportIsBoundedAndContainsOnlyExplicitAggregateFields() {
        var value = new NetworkDiagnosticsSnapshot(
                new UUID(1, 1), new UUID(2, 2), 20, 3, 4, 5, 1, "not_loaded", -1, 0, 0, 2, 4, 3, 25, 500);
        String json = value.export("0.1.0-alpha.1");
        assertTrue(json.contains("not_loaded"));
        assertTrue(json.contains("known_variants"));
        assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536);
        assertFalse(json.contains("resources"));
        assertFalse(json.contains("rules"));
        assertFalse(json.contains("path"));
        assertThrows(IllegalArgumentException.class, () -> value.export("x".repeat(257)));
    }
}
