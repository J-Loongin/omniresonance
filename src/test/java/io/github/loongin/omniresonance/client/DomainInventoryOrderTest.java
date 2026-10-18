// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainInventoryOrderTest {
    private static ResourceVariantKey key(long id) {
        return new ResourceVariantKey(
                ResourceLocation.parse("minecraft:item"),
                ByteBuffer.allocate(8).putLong(id).array());
    }

    @Test
    void rankedEditsAndIdentityReplacementMatchAReferenceList() {
        var actual = new DomainInventoryOrder();
        var expected = new ArrayList<Long>();
        var keys = new ArrayList<ResourceVariantKey>();
        var random = new Random(20261018);
        for (long id = 1; id <= 10000; id++) {
            int index = random.nextInt(expected.size() + 1);
            var key = key(id);
            actual.insert(index, id, key);
            expected.add(index, id);
            keys.add(index, key);
        }
        assertTrue(actual.height() < 20);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i));
            assertEquals(i, actual.position(keys.get(i)));
        }
        for (int step = 0; step < 10000; step++) {
            int index = random.nextInt(expected.size());
            if (step % 3 == 0) {
                long next = 20000 + step;
                actual.replaceId(keys.get(index), next);
                expected.set(index, next);
            }
            var removedKey = keys.remove(index);
            assertEquals(expected.remove(index), actual.remove(index));
            assertEquals(-1, actual.position(removedKey));
            if (!expected.isEmpty()) {
                int probe = random.nextInt(expected.size());
                assertEquals(expected.get(probe), actual.get(probe));
                assertEquals(probe, actual.position(keys.get(probe)));
                assertTrue(actual.height() <= 2 * (32 - Integer.numberOfLeadingZeros(actual.size())));
            }
        }
        assertEquals(0, actual.size());
        assertEquals(0, actual.height());
        assertThrows(IndexOutOfBoundsException.class, () -> actual.get(0));
    }

    @Test
    void sortedInsertionAndLargeRankMovesStayBalanced() {
        var order = new DomainInventoryOrder();
        for (long id = 1; id <= 50000; id++) order.insert(order.size(), id, key(id));
        for (long id = 50000; id > 49872; id--) {
            int index = order.position(key(id));
            assertEquals(id, order.remove(index));
            int[] comparisons = {0};
            int at = order.insertionPoint(-id, (a, b) -> {
                comparisons[0]++;
                return Long.compare(a, b);
            });
            order.insert(at, -id, key(id));
            assertTrue(comparisons[0] <= 32);
            assertTrue(order.height() <= 24);
        }
        long previous = Long.MIN_VALUE;
        for (long id : order) {
            assertTrue(id > previous);
            previous = id;
        }
    }
}
