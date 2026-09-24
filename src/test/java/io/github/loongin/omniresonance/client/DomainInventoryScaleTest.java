// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.network.DomainInventoryPublisher;
import io.github.loongin.omniresonance.network.DomainInventorySync;
import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Deterministic scale acceptance, not a wall-clock benchmark or a claim about multiplayer TPS. */
@Tag("scale")
class DomainInventoryScaleTest {
    private static final UUID NETWORK = new UUID(100, 1);
    private static final ResourceLocation TYPE = ResourceLocation.parse("example:opaque");

    private static ResourceVariantKey key(int value) {
        return new ResourceVariantKey(
                TYPE, new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    private static DomainLedger populated(int count) {
        var ledger = new DomainLedger(NETWORK, Map.of(), index -> StorageBucketData.create(NETWORK, index));
        for (int value = 0; value < count; value++) {
            try (var reserved = ledger.reserveDeposit(key(value), 1, -1).orElseThrow()) {
                reserved.commit(1);
            }
        }
        assertEquals(count, ledger.variantCount());
        return ledger;
    }

    private static long deliver(DomainInventoryReceiver receiver, DomainInventoryFrame frame) {
        if (frame == null) return 0;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            DomainInventoryFrame.STREAM_CODEC.encode(buffer, frame);
            assertEquals(frame.wireSize(), buffer.readableBytes() + DomainInventoryFrame.ENVELOPE_BYTES);
            assertTrue(receiver.accept(DomainInventoryFrame.STREAM_CODEC.decode(buffer)));
            return frame.wireSize();
        } finally {
            buffer.release();
        }
    }

    @Test
    void hundredThousandVariantsStreamExactlyOnceAndReleaseTheBaseSnapshot() {
        var ledger = populated(100_000);
        var session = new UUID(101, 1);
        try (var publisher = new DomainInventoryPublisher(ledger, 1024);
                var client = new DomainInventoryReceiver()) {
            var receiver = publisher.subscribe(new UUID(102, 1), session, 1);
            client.request(session, 1);
            long bytes = 0;
            int steps = 0, maximumBase = 0;
            for (; steps < 300_000 && !client.mirror().ready(); steps++) {
                publisher.work();
                maximumBase = Math.max(maximumBase, publisher.baseRecords());
                var frame = receiver.poll(65536);
                if (frame != null) assertTrue(frame.wireSize() <= 65536);
                bytes += deliver(client, frame);
            }
            assertTrue(client.mirror().ready());
            assertEquals(100_000, client.mirror().entries().size());
            assertEquals(
                    100_000,
                    client.mirror().entries().values().stream()
                            .mapToLong(entry -> entry.amount())
                            .sum());
            assertTrue(maximumBase <= 100_000);
            assertEquals(0, client.assemblingBytes());
            assertFalse(publisher.hasSnapshot());
            assertEquals(100_000, ledger.variantCount());
            assertFalse(ledger.hasReservations());
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info(
                            "Scale acceptance: variants=100000, receivers=1, steps={}, wireBytes={}, maxBaseRecords={}",
                            steps,
                            bytes,
                            maximumBase);
        }
    }

    @Test
    void sixteenLargeTerminalsShareOnePublisherAndRespectEveryTickByteLimit() {
        var ledger = populated(10_000);
        var clients = new ArrayList<DomainInventoryReceiver>();
        long[] playerBytes = new long[16];
        long[] totalBytes = {0};
        int[] admissions = {0};
        var limits = new DomainInventorySync.Limits(16384, 65536, 2, 1024);
        try (var sync = new DomainInventorySync(
                network -> {
                    admissions[0]++;
                    return ledger;
                },
                (player, frame) -> {
                    int index = (int) player.getLeastSignificantBits();
                    playerBytes[index] += deliver(clients.get(index), frame);
                    totalBytes[0] += frame.wireSize();
                })) {
            for (int index = 0; index < 16; index++) {
                var client = new DomainInventoryReceiver();
                var session = new UUID(103, index);
                client.request(session, 1);
                clients.add(client);
                sync.request(new UUID(104, index), NETWORK, session, 1);
            }
            assertEquals(0, admissions[0], "Queued requests must not activate storage");
            int ticks = 0;
            long maxServerBytes = 0, maxPlayerBytes = 0;
            for (;
                    ticks < 5000
                            && clients.stream()
                                    .anyMatch(client -> !client.mirror().ready());
                    ticks++) {
                Arrays.fill(playerBytes, 0);
                int[] workChecks = {0};
                long emitted = sync.tick(limits, 512, () -> {
                    workChecks[0]++;
                    return true;
                });
                assertTrue(workChecks[0] <= 512);
                assertEquals(Arrays.stream(playerBytes).sum(), emitted);
                assertTrue(emitted <= limits.bytesServer());
                maxServerBytes = Math.max(maxServerBytes, emitted);
                for (long bytes : playerBytes) {
                    assertTrue(bytes <= limits.bytesPerPlayer());
                    maxPlayerBytes = Math.max(maxPlayerBytes, bytes);
                }
                assertTrue(sync.activeFullCount() <= 2);
                assertEquals(1, sync.publisherCount());
            }
            for (var client : clients) {
                assertTrue(
                        client.mirror().ready(),
                        "Every admitted terminal must finish, including the last queued player");
                assertEquals(10_000, client.mirror().entries().size());
                assertEquals(0, client.assemblingBytes());
            }
            assertEquals(0, sync.waitingCount());
            assertEquals(0, sync.activeFullCount());
            for (int index = 0; index < 16; index++) sync.cancel(new UUID(104, index));
            assertEquals(0, sync.publisherCount());
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info(
                            "Scale acceptance: variants=10000, receivers=16, ticks={}, wireBytes={}, maxServerBytes={}, maxPlayerBytes={}",
                            ticks,
                            totalBytes[0],
                            maxServerBytes,
                            maxPlayerBytes);
        } finally {
            for (var client : clients) client.close();
        }
    }
}
