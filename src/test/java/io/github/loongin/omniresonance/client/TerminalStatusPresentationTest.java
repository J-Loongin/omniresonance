// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.NetworkDiagnosticsSnapshot;
import io.github.loongin.omniresonance.transfer.TransferTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class TerminalStatusPresentationTest {
    private static NetworkDiagnosticsSnapshot snapshot(String storage, int recovery) {
        return new NetworkDiagnosticsSnapshot(
                new UUID(1, 1),
                new UUID(2, 2),
                20,
                1,
                2,
                3,
                1,
                storage,
                storage.equals("available") ? 0 : -1,
                recovery,
                recovery * 10L,
                1,
                2,
                2,
                25,
                500);
    }

    @Test
    void optionalChemicalDetailsUseRegisteredUnitsAndTranslatedType() {
        var type = ResourceLocation.parse("mekanism:chemical");
        var t = new TransferTelemetry(List.of(type));
        var network = new UUID(1, 1);
        t.moved(network, type, 20, 1000);
        var s = snapshot("available", 0)
                .withRuntime(new NetworkDiagnosticsSnapshot.RuntimeStats(t.snapshot(network, 20), 0, 0, 0));
        var rows = TerminalStatusPresentation.details(s, ignored -> "mB");
        assertTrue(rows.stream()
                .anyMatch(row -> row.value().getString().equals("1 B")
                        && row.label().getContents() instanceof TranslatableContents c
                        && c.getKey().equals("omniresonance.resource_type.mekanism.chemical")));
    }

    @Test
    void exchangeOverviewIsOneSummaryAndDetailsIdentifyBothNetworksWithoutFakeNodes() {
        var incident = new io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Incident(
                new UUID(10, 1),
                new UUID(11, 1),
                new UUID(11, 2),
                io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                15,
                io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Stage.DECODE,
                io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Reason.DECODE_FAILED,
                "Iron",
                "Main",
                "Peer");
        var s = snapshot("available", 0)
                .withRuntime(new NetworkDiagnosticsSnapshot.RuntimeStats(
                        new TransferTelemetry.Snapshot(20, 0, 0, List.of(), 0, "", -1),
                        0,
                        0,
                        0,
                        new io.github.loongin.omniresonance.exchange.ExchangeTelemetry.Snapshot(
                                true, true, 4, 2, false, incident)));
        var overview = TerminalStatusPresentation.overview(s);
        assertEquals(
                1,
                overview.stream()
                        .filter(r -> r.label().getContents() instanceof TranslatableContents t
                                && t.getKey().equals("omniresonance.status.exchange.title"))
                        .count());
        var detail = TerminalStatusPresentation.details(s);
        assertTrue(detail.stream().anyMatch(r -> r.value().getString().equals("Main")));
        assertTrue(detail.stream().anyMatch(r -> r.value().getString().equals("Peer")));
        assertTrue(detail.stream().anyMatch(r -> r.value().getString().equals("Iron")));
        org.junit.jupiter.api.Assertions.assertFalse(detail.stream()
                .anyMatch(r -> r.label().getContents() instanceof TranslatableContents t
                        && t.getKey().equals("omniresonance.status.incident.node")));
        var export = com.google.gson.JsonParser.parseString(s.export("test"))
                .getAsJsonObject()
                .getAsJsonObject("exchange");
        assertEquals(4, export.get("sent_commits").getAsLong());
        assertEquals(
                "Iron", export.getAsJsonObject("incident").get("channel_name").getAsString());
    }

    @Test
    void slowIncidentShowsItsNodeAndCauseInsteadOfCallingItATransferFailure() {
        var position = net.minecraft.core.GlobalPos.of(
                net.minecraft.world.level.Level.OVERWORLD, new net.minecraft.core.BlockPos(1, 70, 2));
        var incident = new io.github.loongin.omniresonance.transfer.TransferIncident(
                new io.github.loongin.omniresonance.transfer.TransferIncident.Endpoint(
                        new UUID(4, 4), "Input A", position),
                null,
                new UUID(5, 5),
                "Channel 1",
                io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM,
                io.github.loongin.omniresonance.transfer.TransferIncident.Reason.SLOW_CALL,
                io.github.loongin.omniresonance.transfer.ResourceTransferEngine.Stage.NONE);
        var s = snapshot("available", 0)
                .withRuntime(new NetworkDiagnosticsSnapshot.RuntimeStats(
                        new TransferTelemetry.Snapshot(20, 1, 1000, List.of(), 0, "direct_transfer", 15, incident),
                        0,
                        0,
                        0));
        var summary = (TranslatableContents)
                TerminalStatusPresentation.overview(s).getLast().value().getContents();
        assertEquals("Input A", ((net.minecraft.network.chat.Component) summary.getArgs()[0]).getString());
        assertEquals(
                "omniresonance.status.incident.reason.slow_call",
                ((TranslatableContents) ((net.minecraft.network.chat.Component) summary.getArgs()[1]).getContents())
                        .getKey());
        var rows = TerminalStatusPresentation.details(s);
        assertTrue(rows.stream().anyMatch(row -> row.value().getString().equals("Input A")));
        assertTrue(rows.stream().anyMatch(row -> row.value().getString().equals("Channel 1")));
        assertTrue(rows.stream().anyMatch(row -> row.value().getString().contains("1, 70, 2")));
    }

    @Test
    void overviewUsesOneClearInventoryStatusWithoutTreatingUnknownAsEmpty() {
        var unknown = TerminalStatusPresentation.overview(snapshot("not_loaded", 0));
        assertEquals(2, unknown.size());
        assertEquals(
                "omniresonance.status.inventory_unknown",
                ((TranslatableContents) unknown.get(1).value().getContents()).getKey());
        var empty = TerminalStatusPresentation.overview(snapshot("available", 0));
        var value = (TranslatableContents) empty.get(1).value().getContents();
        assertEquals("omniresonance.status.inventory_count", value.getKey());
        assertEquals(0L, value.getArgs()[0]);
        var unavailable = TerminalStatusPresentation.overview(snapshot("unavailable", 0));
        assertEquals(
                "omniresonance.status.inventory_unavailable",
                ((TranslatableContents) unavailable.get(1).value().getContents()).getKey());
    }

    @Test
    void overviewHasConstantRowsForAnyResourceCountAndDoesNotSumAmounts() {
        var moved = new ArrayList<TransferTelemetry.Movement>();
        for (int index = 0; index < 128; index++)
            moved.add(
                    new TransferTelemetry.Movement(ResourceLocation.parse("test:type_" + index), Long.MAX_VALUE, true));
        var s = snapshot("available", 0)
                .withRuntime(new NetworkDiagnosticsSnapshot.RuntimeStats(
                        new TransferTelemetry.Snapshot(20, 5, 1000, moved, 9, "", -1), 1, 2, 3));
        var rows = TerminalStatusPresentation.overview(s);
        assertEquals(2, rows.size());
        assertEquals(137L, TerminalStatusPresentation.transferredTypes(s));
        var value = (TranslatableContents) rows.getFirst().value().getContents();
        assertEquals("omniresonance.status.activity", value.getKey());
        assertEquals(137L, value.getArgs()[0]);
        assertTrue(TerminalStatusPresentation.details(s).size() > 128);
    }

    @Test
    void idleUnknownRecoveryAndHistoricalErrorsRemainDistinct() {
        var missing = TerminalStatusPresentation.overview(snapshot("not_loaded", 0));
        assertEquals(
                "omniresonance.status.transfer_unknown",
                ((TranslatableContents) missing.getFirst().value().getContents()).getKey());
        var s = snapshot("unavailable", 2)
                .withRuntime(new NetworkDiagnosticsSnapshot.RuntimeStats(
                        new TransferTelemetry.Snapshot(20, 0, 0, List.of(), 0, "direct_transfer", 10), 0, 0, 0));
        var rows = TerminalStatusPresentation.overview(s);
        assertEquals(4, rows.size());
        assertEquals(TerminalStatusPresentation.Tone.MUTED, rows.getFirst().tone());
        assertEquals(TerminalStatusPresentation.Tone.ERROR, rows.get(1).tone());
        assertEquals(TerminalStatusPresentation.Tone.WARNING, rows.getLast().tone());
        assertEquals(
                "omniresonance.status.history",
                ((TranslatableContents) rows.getLast().label().getContents()).getKey());
    }
}
