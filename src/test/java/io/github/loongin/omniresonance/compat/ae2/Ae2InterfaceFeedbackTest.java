// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class Ae2InterfaceFeedbackTest {
    @Test
    void rejectionEchoesOnlyTheRequestCorrelationWithoutDirectoryOrBindingFacts() {
        var request = new Ae2InterfacePayloads.Request(new UUID(1, 2), 7, 1, new UUID(3, 4), "Node");
        var frame = Ae2InterfacePayloads.unavailable(request);
        assertEquals(request.session(), frame.session());
        assertEquals(request.sequence(), frame.sequence());
        assertFalse(frame.initial());
        assertNull(frame.selected());
        assertEquals("unavailable", frame.status());
        assertEquals("unavailable", frame.error());
        assertEquals(0, frame.entries().size());
        assertEquals(-1, frame.total());
        assertFalse(frame.more());
    }
}
