// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DomainInventoryMirrorTest {
    private static final UUID SESSION = new UUID(3, 5);

    private static ResourceVariantKey key(int value) {
        return new ResourceVariantKey(ResourceLocation.parse("example:unknown"), new byte[] {(byte) value});
    }

    @Test
    void partialMirrorIsHiddenUntilEndAndCatchupUsesAbsoluteAmounts() {
        var mirror = new DomainInventoryMirror();
        mirror.begin(SESSION, 1, 2);
        assertTrue(mirror.base(SESSION, 1, 0, new DomainLedger.Cursor(1, key(1), Long.MAX_VALUE)));
        assertFalse(mirror.ready());
        assertTrue(mirror.entries().isEmpty());
        assertTrue(mirror.change(SESSION, 1, 1, new DomainLedger.Change(1, key(1), 0, 5)));
        assertTrue(mirror.change(SESSION, 1, 2, new DomainLedger.Change(3, key(2), 7, 6)));
        assertTrue(mirror.finish(SESSION, 1, 3, 1, 6));
        assertTrue(mirror.ready());
        assertEquals(7, mirror.entries().get(3L).amount());
        assertTrue(mirror.change(SESSION, 1, 4, new DomainLedger.Change(3, key(2), 8, 7)));
        assertEquals(8, mirror.entries().get(3L).amount());
        assertTrue(mirror.change(SESSION, 1, 5, new DomainLedger.Change(3, key(2), 0, 8)));
        assertTrue(mirror.entries().isEmpty());
    }

    @Test
    void sequenceOrFinalCountErrorsDiscardEverythingAndRequireExplicitBegin() {
        var mirror = new DomainInventoryMirror();
        mirror.begin(SESSION, 1, 2);
        assertFalse(mirror.base(SESSION, 1, 1, new DomainLedger.Cursor(1, key(1), 2)));
        assertTrue(mirror.failed());
        assertTrue(mirror.entries().isEmpty());
        assertFalse(mirror.finish(SESSION, 1, 0, 0, 0));
        mirror.begin(SESSION, 2, 2);
        assertTrue(mirror.base(SESSION, 2, 0, new DomainLedger.Cursor(1, key(1), 2)));
        assertFalse(mirror.finish(SESSION, 2, 1, 2, 0));
        assertTrue(mirror.failed());
        mirror.close();
        assertFalse(mirror.ready());
        assertTrue(mirror.entries().isEmpty());
    }

    @Test
    void staleSessionsCannotReplaceNewViewAndIdentityAliasesFailClosed() {
        var mirror = new DomainInventoryMirror();
        mirror.begin(SESSION, 2, 4);
        assertFalse(mirror.base(SESSION, 1, 0, new DomainLedger.Cursor(1, key(1), 2)));
        assertFalse(mirror.failed());
        assertTrue(mirror.base(SESSION, 2, 0, new DomainLedger.Cursor(1, key(1), 2)));
        assertFalse(mirror.base(SESSION, 2, 1, new DomainLedger.Cursor(2, key(1), 3)));
        assertTrue(mirror.failed());
    }

    @Test
    void oldViewsAreClearedAndCannotExposeTheNextPartialSync() {
        var mirror = new DomainInventoryMirror();
        mirror.begin(SESSION, 1, 1);
        mirror.base(SESSION, 1, 0, new DomainLedger.Cursor(1, key(1), 2));
        mirror.finish(SESSION, 1, 1, 1, 0);
        var old = mirror.entries();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> old.clear());
        mirror.begin(SESSION, 2, 2);
        mirror.base(SESSION, 2, 0, new DomainLedger.Cursor(2, key(2), 3));
        assertTrue(old.isEmpty());
        assertTrue(mirror.entries().isEmpty());
        assertTrue(mirror.finish(SESSION, 2, 1, 1, 0));
        assertEquals(3, mirror.entries().get(2L).amount());
    }

    @Test
    void incompleteFinalRevisionAndBaseAfterCatchupCannotBeEnabled() {
        var mirror = new DomainInventoryMirror();
        mirror.begin(SESSION, 1, 1);
        mirror.change(SESSION, 1, 0, new DomainLedger.Change(2, key(2), 1, 5));
        assertFalse(mirror.finish(SESSION, 1, 1, 1, 4));
        assertTrue(mirror.failed());
        mirror.begin(SESSION, 2, 1);
        mirror.change(SESSION, 2, 0, new DomainLedger.Change(2, key(2), 1, 5));
        assertFalse(mirror.base(SESSION, 2, 1, new DomainLedger.Cursor(1, key(1), 1)));
        assertTrue(mirror.failed());
    }
}
