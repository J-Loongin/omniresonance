// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.persistence.StorageBucketData;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.EnergyVariant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Ae2DomainAccessTest {
    @Test
    void simulationsDoNotCreateBucketsOrChangeInventoryAndLongTransfersAreSingleOperations() {
        UUID id = new UUID(1, 1);
        var buckets = new HashMap<Integer, StorageBucketData>();
        var ledger = new DomainLedger(id, Map.of(), index -> {
            var b = StorageBucketData.create(id, index);
            buckets.put(index, b);
            return b;
        });
        var access = new Ae2DomainAccess(() -> ledger, () -> -1);
        var key = EnergyVariant.INSTANCE.key();
        long amount = (long) Integer.MAX_VALUE + 100;
        assertEquals(amount, access.insert(key, amount, true));
        assertEquals(0, access.extract(key, amount, true));
        assertTrue(buckets.isEmpty());
        assertEquals(0, ledger.revision());
        assertEquals(amount, access.insert(key, amount, false));
        assertEquals(1, ledger.revision());
        buckets.values().forEach(b -> b.setDirty(false));
        assertEquals(amount, access.extract(key, amount, true));
        assertEquals(amount, ledger.amount(key));
        assertTrue(buckets.values().stream().noneMatch(StorageBucketData::isDirty));
        assertEquals(amount, access.extract(key, amount, false));
        assertEquals(2, ledger.revision());
        assertEquals(0, ledger.amount(key));
        assertFalse(ledger.hasReservations());
    }

    @Test
    void disabledOrReboundAccessCannotUseThePreviouslyAuthorizedLedger() {
        UUID id = new UUID(1, 1);
        var ledger = new DomainLedger(id, Map.of(), index -> StorageBucketData.create(id, index));
        int[] reads = {0};
        var access = new Ae2DomainAccess(() -> ++reads[0] == 1 ? ledger : null, () -> -1);
        assertEquals(0, access.insert(EnergyVariant.INSTANCE.key(), 10, false));
        assertEquals(0, ledger.variantCount());
    }
}
