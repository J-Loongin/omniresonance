// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.ManagementTransferPool;
import org.junit.jupiter.api.Test;

final class ClientUploadBufferTest {
    @Test
    void fragmentsAreBoundedOrderedAndRetainedUntilExplicitCompletion() {
        var upload = new ClientUploadBuffer();
        int bound = ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES;
        byte[] body = new byte[bound + 3];
        body[bound] = 42;
        upload.begin(body);
        assertThrows(IllegalStateException.class, () -> upload.begin(new byte[1]));
        var first = upload.next();
        assertEquals(0, first.offset());
        assertEquals(bound, first.bytes().length);
        var last = upload.next();
        assertEquals(bound, last.offset());
        assertArrayEquals(new byte[] {42, 0, 0}, last.bytes());
        assertFalse(upload.hasNext());
        assertTrue(upload.active());
        assertThrows(IllegalStateException.class, upload::next);
        upload.clear();
        assertFalse(upload.active());
        assertFalse(upload.hasNext());
        upload.begin(new byte[] {7});
        assertEquals(0, upload.next().offset());
    }

    @Test
    void cancellationDropsRemainingFragmentsAndRejectsInvalidLengths() {
        var upload = new ClientUploadBuffer();
        assertThrows(IllegalArgumentException.class, () -> upload.begin(new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> upload.begin(new byte[ManagementTransferPool.MAXIMUM_OBJECT_BYTES + 1]));
        upload.begin(new byte[] {1, 2});
        upload.clear();
        upload.clear();
        assertThrows(IllegalStateException.class, upload::next);
        assertFalse(upload.active());
    }
}
