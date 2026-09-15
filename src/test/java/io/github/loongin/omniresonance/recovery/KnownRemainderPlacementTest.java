// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class KnownRemainderPlacementTest {
    private static final ResourceVariantKey KEY =
            new ResourceVariantKey(ResourceLocation.parse("example:opaque"), new byte[] {1});

    @Test
    void acceptedDomainRemainderDoesNotOccupyOrDirtyBuffer() {
        AtomicInteger dirty = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        RecoveryBuffer buffer = new RecoveryBuffer(dirty::incrementAndGet);
        buffer.onDomainReturn((key, amount) -> {
            assertEquals(KEY, key);
            calls.incrementAndGet();
            return amount;
        });
        try (var reservation = buffer.reserve(KEY, 64, 1, 1000).orElseThrow()) {
            var placement = reservation.placeKnownRemainder(4);
            assertEquals(4, placement.stored());
            assertEquals(0, placement.buffered());
        }
        assertEquals(1, calls.get());
        assertEquals(0, dirty.get());
        assertTrue(buffer.isEmpty());
        assertTrue(!buffer.hasActiveReservations());
    }

    @Test
    void partialDomainAdmissionPersistsOnlyTheKnownRestAndZeroNeverActivates() {
        RecoveryBuffer buffer = new RecoveryBuffer(() -> {});
        AtomicInteger calls = new AtomicInteger();
        buffer.onDomainReturn((key, amount) -> {
            calls.incrementAndGet();
            return Math.min(2, amount);
        });
        try (var reservation = buffer.reserve(KEY, 64, 1, 1000).orElseThrow()) {
            assertThrows(IllegalArgumentException.class, () -> reservation.placeKnownRemainder(65));
            assertEquals(0, calls.get());
            var placement = reservation.placeKnownRemainder(4);
            assertEquals(2, placement.stored());
            assertEquals(2, placement.buffered());
        }
        try (var reservation = buffer.reserve(KEY, 1, 1, 1000).orElseThrow()) {
            reservation.placeKnownRemainder(0);
        }
        assertEquals(1, calls.get());
        assertEquals(2, buffer.amount(KEY));
        buffer.onDomainReturn(null);
        try (var reservation = buffer.reserve(KEY, 1, 1, 1000).orElseThrow()) {
            assertEquals(1, reservation.placeKnownRemainder(1).buffered());
        }
        assertEquals(3, buffer.amount(KEY));
    }
}
