// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.DomainInventoryPublisher;
import io.github.loongin.omniresonance.network.DomainInventorySync;
import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import io.github.loongin.omniresonance.networking.DomainInventoryRecordCodec;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainInventoryTransportTest {
    private static final UUID NETWORK = new UUID(1, 1),
            SESSION = new UUID(2, 2),
            P1 = new UUID(3, 1),
            P2 = new UUID(3, 2);

    private static ResourceVariantKey key(int value) {
        return new ResourceVariantKey(ResourceLocation.parse("example:opaque"), new byte[] {(byte) value});
    }

    private static DomainLedger ledger() {
        return new DomainLedger(NETWORK, Map.of(), i -> StorageBucketData.create(NETWORK, i));
    }

    private static void put(DomainLedger ledger, ResourceVariantKey key, long amount) {
        try (var deposit = ledger.reserveDeposit(key, amount, -1).orElseThrow()) {
            deposit.commit(amount);
        }
    }

    private static boolean deliver(DomainInventoryReceiver client, DomainInventoryFrame frame) {
        if (frame == null) return false;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DomainInventoryFrame.STREAM_CODEC.encode(buffer, frame);
            assertEquals(frame.wireSize(), buffer.readableBytes() + DomainInventoryFrame.ENVELOPE_BYTES);
            assertTrue(client.accept(DomainInventoryFrame.STREAM_CODEC.decode(buffer)));
            return true;
        } finally {
            buffer.release();
        }
    }

    @Test
    void writeReadinessRequiresDeliveredEndAndTheExactLiveGeneration() {
        var ledger = ledger();
        put(ledger, key(1), 10);
        try (var sync = new DomainInventorySync(network -> ledger, (player, frame) -> {})) {
            sync.request(P1, NETWORK, SESSION, 1);
            assertFalse(sync.ready(P1, SESSION, 1));
            sync.tick(new DomainInventorySync.Limits(4096, 4096, 1, 16), 1, () -> true);
            assertFalse(sync.ready(P1, SESSION, 1));
            sync.tick(new DomainInventorySync.Limits(4096, 4096, 1, 16), 100, () -> true);
            assertTrue(sync.ready(P1, SESSION, 1));
            assertFalse(sync.ready(P1, SESSION, 2));
            assertFalse(sync.ready(P2, SESSION, 1));
            sync.fail(P1, DomainInventoryFrame.Reason.UNAVAILABLE);
            assertFalse(sync.ready(P1, SESSION, 1));
            sync.request(P1, NETWORK, SESSION, 2);
            assertFalse(sync.ready(P1, SESSION, 1));
            assertFalse(sync.ready(P1, SESSION, 2));
            sync.tick(new DomainInventorySync.Limits(4096, 4096, 1, 16), 100, () -> true);
            assertTrue(sync.ready(P1, SESSION, 2));
            ledger.invalidate();
            assertFalse(sync.ready(P1, SESSION, 2));
            sync.cancel(P1);
            assertFalse(sync.ready(P1, SESSION, 2));
        }
    }

    @Test
    void runtimeLookupNeverRetargetsADeletedIdToItsReintroducedVariant() {
        var ledger = ledger();
        put(ledger, key(1), 10);
        long old = ledger.sequence(key(1));
        assertEquals(10, ledger.findSequence(old).orElseThrow().amount());
        try (var withdrawal = ledger.withdraw(key(1), 10).orElseThrow()) {}
        put(ledger, key(1), 20);
        assertTrue(ledger.findSequence(old).isEmpty());
        assertEquals(
                20, ledger.findSequence(ledger.sequence(key(1))).orElseThrow().amount());
        assertTrue(ledger.findSequence(0).isEmpty());
        ledger.invalidate();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> ledger.findSequence(old));
    }

    @Test
    void twoReceiversShareOneBaseAndCatchUpAcrossMaximumKeyFragmentsAndReintroducedIds() {
        var ledger = ledger();
        var big = new ResourceVariantKey(ResourceLocation.parse("example:big"), new byte[262144]);
        put(ledger, key(1), 10);
        put(ledger, big, Long.MAX_VALUE);
        try (var publisher = new DomainInventoryPublisher(ledger, 32)) {
            var a = publisher.subscribe(P1, SESSION, 1);
            var b = publisher.subscribe(P2, SESSION, 2);
            var ca = new DomainInventoryReceiver();
            ca.request(SESSION, 1);
            var cb = new DomainInventoryReceiver();
            cb.request(SESSION, 2);
            boolean updatedLive = false;
            for (int i = 0; i < 5000; i++) {
                publisher.work();
                deliver(ca, a.poll(4096));
                deliver(cb, b.poll(512));
                assertTrue(publisher.baseRecords() <= 2, "Shared base duplicated or scanned beyond its ceiling");
                if (i == 5) {
                    try (var removed = ledger.withdraw(key(1), 10).orElseThrow()) {}
                    put(ledger, key(1), 30);
                    put(ledger, key(3), 40);
                }
                if (ca.mirror().ready() && !cb.mirror().ready() && !updatedLive) {
                    put(ledger, key(3), 1);
                    updatedLive = true;
                }
                if (ca.mirror().ready() && cb.mirror().ready() && a.pendingCount() == 0) break;
            }
            assertTrue(updatedLive);
            for (var client : List.of(ca, cb)) {
                assertTrue(client.mirror().ready());
                assertEquals(3, client.mirror().entries().size());
                Map<ResourceVariantKey, Long> actual = new HashMap<>();
                client.mirror().entries().values().forEach(entry -> actual.put(entry.key(), entry.amount()));
                assertEquals(Map.of(big, Long.MAX_VALUE, key(1), 30L, key(3), 41L), actual);
                assertEquals(0, client.assemblingBytes());
            }
            assertFalse(publisher.hasSnapshot());
            assertEquals(0, publisher.baseRecords());
            a.close();
            b.close();
            assertEquals(0, publisher.receiverCount());
        }
        try (var journal = ledger.openChanges(1)) {
            assertEquals(0, journal.pendingCount());
        }
    }

    @Test
    void schedulerHonorsExactPlayerAndServerBytesAndQueuesWithoutActivatingDuplicates() {
        var ledger = ledger();
        put(ledger, key(1), 10);
        int[] activations = {0};
        var frames = new HashMap<UUID, List<DomainInventoryFrame>>();
        var clients = Map.of(P1, new DomainInventoryReceiver(), P2, new DomainInventoryReceiver());
        clients.get(P1).request(SESSION, 2);
        clients.get(P2).request(SESSION, 1);
        try (var sync = new DomainInventorySync(
                network -> {
                    activations[0]++;
                    return ledger;
                },
                (player, frame) -> {
                    frames.computeIfAbsent(player, ignored -> new ArrayList<>()).add(frame);
                    deliver(clients.get(player), frame);
                })) {
            sync.request(P1, NETWORK, SESSION, 1);
            sync.request(P1, NETWORK, SESSION, 2);
            sync.request(P2, NETWORK, SESSION, 1);
            assertEquals(2, sync.waitingCount());
            assertEquals(0, activations[0]);
            var limits = new DomainInventorySync.Limits(150, 220, 1, 16);
            assertEquals(0, sync.tick(limits, 100, () -> false));
            assertEquals(0, activations[0]);
            for (int i = 0; i < 100; i++) {
                frames.clear();
                long sent = sync.tick(limits, 100, () -> true);
                assertTrue(sent <= 220);
                assertEquals(
                        sent,
                        frames.values().stream()
                                .flatMap(List::stream)
                                .mapToInt(DomainInventoryFrame::wireSize)
                                .sum());
                for (var list : frames.values())
                    assertTrue(list.stream()
                                    .mapToInt(DomainInventoryFrame::wireSize)
                                    .sum()
                            <= 150);
                assertTrue(sync.activeFullCount() <= 1);
                if (clients.values().stream().allMatch(c -> c.mirror().ready())) break;
            }
            assertTrue(clients.values().stream().allMatch(c -> c.mirror().ready()));
            assertEquals(1, activations[0]);
            assertEquals(1, sync.publisherCount());
            sync.cancel(P1);
            sync.cancel(P2);
            assertEquals(0, sync.publisherCount());
            assertEquals(0, sync.waitingCount());
        }
    }

    @Test
    void brokenOffsetsOrPrematureEndDropThePartialRecordAndRequireManualRequest() {
        var client = new DomainInventoryReceiver();
        client.request(SESSION, 1);
        assertTrue(client.accept(new DomainInventoryFrame.Begin(SESSION, 1, 0, 1, 1)));
        byte[] bytes = DomainInventoryRecordCodec.encode(new DomainLedger.Change(1, key(1), 2, 0));
        assertTrue(client.accept(new DomainInventoryFrame.Data(
                SESSION, 1, 1, true, bytes.length, 0, java.util.Arrays.copyOf(bytes, 10))));
        assertEquals(bytes.length, client.assemblingBytes());
        assertFalse(client.accept(new DomainInventoryFrame.End(SESSION, 1, 2, 1, 0)));
        assertTrue(client.mirror().failed());
        assertEquals(0, client.assemblingBytes());
        assertFalse(client.accept(new DomainInventoryFrame.Begin(SESSION, 1, 0, 1, 1)));
        client.request(SESSION, 2);
        assertTrue(client.accept(new DomainInventoryFrame.Begin(SESSION, 2, 0, 1, 1)));
        assertFalse(client.accept(new DomainInventoryFrame.Data(SESSION, 2, 1, true, bytes.length, 1, new byte[] {1})));
        assertTrue(client.mirror().failed());
        assertEquals(0, client.assemblingBytes());
    }

    @Test
    void sharedCatchupOverflowFailsOnlyTheFullReceiverWhileLiveDeltasContinue() {
        var ledger = ledger();
        put(ledger, key(1), 1);
        try (var publisher = new DomainInventoryPublisher(ledger, 2)) {
            var live = publisher.subscribe(P1, SESSION, 1);
            var slow = publisher.subscribe(P2, SESSION, 2);
            var client = new DomainInventoryReceiver();
            client.request(SESSION, 1);
            var waiting = new DomainInventoryReceiver();
            waiting.request(SESSION, 2);
            deliver(waiting, slow.poll(1024));
            for (int i = 0; i < 20 && !client.mirror().ready(); i++) {
                publisher.work();
                deliver(client, live.poll(1024));
            }
            assertTrue(client.mirror().ready());
            assertTrue(publisher.hasSnapshot());
            for (int i = 2; i <= 4; i++) {
                put(ledger, key(i), i);
                publisher.work();
                deliver(client, live.poll(1024));
            }
            var failure = slow.poll(1024);
            assertTrue(failure instanceof DomainInventoryFrame.Failed);
            assertFalse(waiting.accept(failure));
            assertTrue(waiting.mirror().failed());
            assertEquals(DomainInventoryFrame.Reason.CHANGING_TOO_FAST, waiting.failureReason());
            assertTrue(client.mirror().ready());
            assertEquals(4, client.mirror().entries().size());
            assertFalse(publisher.hasSnapshot());
            assertEquals(4, ledger.variantCount());
            live.close();
            slow.close();
        }
    }

    @Test
    void inFlightDeltaCountsTowardOverflowAndFailureReleasesClientAssembly() {
        var ledger = ledger();
        try (var publisher = new DomainInventoryPublisher(ledger, 1)) {
            var source = publisher.subscribe(P1, SESSION, 1);
            var client = new DomainInventoryReceiver();
            client.request(SESSION, 1);
            for (int i = 0; i < 10 && !client.mirror().ready(); i++) {
                publisher.work();
                deliver(client, source.poll(1024));
            }
            var big = new ResourceVariantKey(ResourceLocation.parse("example:big"), new byte[262144]);
            put(ledger, big, 1);
            publisher.work();
            deliver(client, source.poll(100));
            assertTrue(client.assemblingBytes() > 0);
            assertEquals(1, source.pendingCount());
            put(ledger, key(2), 1);
            publisher.work();
            assertFalse(client.accept(source.poll(1024)));
            assertTrue(client.mirror().failed());
            assertEquals(0, client.assemblingBytes());
            assertEquals(2, ledger.variantCount());
            source.close();
        }
    }

    @Test
    void globalByteExhaustionResumesAtTheNextPlayer() {
        var ledger = ledger();
        put(ledger, key(1), 1);
        var recipients = new ArrayList<UUID>();
        try (var sync = new DomainInventorySync(id -> ledger, (player, frame) -> recipients.add(player))) {
            sync.request(P1, NETWORK, SESSION, 1);
            sync.request(P2, NETWORK, SESSION, 2);
            int bytes = DomainInventoryFrame.HEADER_BYTES + 12;
            var limits = new DomainInventorySync.Limits(bytes, bytes, 2, 8);
            assertEquals(bytes, sync.tick(limits, 100, () -> true));
            assertEquals(bytes, sync.tick(limits, 100, () -> true));
            assertEquals(List.of(P1, P2), recipients);
        }
    }
}
