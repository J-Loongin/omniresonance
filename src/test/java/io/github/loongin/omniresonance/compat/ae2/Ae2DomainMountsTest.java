// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class Ae2DomainMountsTest {
    private static final UUID A = new UUID(1, 1), B = new UUID(1, 2), DOMAIN = new UUID(2, 1), OTHER = new UUID(2, 2);

    @Test
    void duplicateDomainBlocksEveryMemberAndRemovingOneRestoresTheSurvivor() {
        var mounts = new Ae2DomainMounts(8);
        Object grid = new Object();
        mounts.bind(A, grid, DOMAIN);
        assertTrue(mounts.permits(A, grid, DOMAIN));
        var change = mounts.bind(B, grid, DOMAIN);
        assertEquals(java.util.Set.of(A, B), change.affected());
        assertFalse(mounts.permits(A, grid, DOMAIN));
        assertFalse(mounts.permits(B, grid, DOMAIN));
        assertEquals(2, mounts.count(grid, DOMAIN));
        mounts.remove(B);
        assertTrue(mounts.permits(A, grid, DOMAIN));
        assertFalse(mounts.permits(B, grid, DOMAIN));
    }

    @Test
    void gridMergeSplitAndRebindOnlyAffectTheDuplicateDomain() {
        var mounts = new Ae2DomainMounts(8);
        Object first = new Object(), second = new Object();
        mounts.bind(A, first, DOMAIN);
        mounts.bind(B, second, DOMAIN);
        assertTrue(mounts.permits(A, first, DOMAIN));
        assertTrue(mounts.permits(B, second, DOMAIN));
        mounts.bind(B, first, DOMAIN);
        assertFalse(mounts.permits(A, first, DOMAIN));
        mounts.bind(B, first, OTHER);
        assertTrue(mounts.permits(A, first, DOMAIN));
        assertTrue(mounts.permits(B, first, OTHER));
        assertFalse(mounts.permits(B, second, DOMAIN));
    }

    @Test
    void capacityFailurePreservesCurrentMembershipAndReadsArePure() {
        var mounts = new Ae2DomainMounts(1);
        Object grid = new Object();
        mounts.bind(A, grid, DOMAIN);
        assertThrows(IllegalStateException.class, () -> mounts.bind(B, grid, DOMAIN));
        for (int i = 0; i < 20; i++) assertTrue(mounts.permits(A, grid, DOMAIN));
        assertEquals(1, mounts.size());
        assertEquals(1, mounts.count(grid, DOMAIN));
    }
}
