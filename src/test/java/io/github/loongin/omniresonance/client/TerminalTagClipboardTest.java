// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalTagClipboardTest {
    @Test
    void emptyCopyDoesNotOverwriteAndDisconnectReleasesTheLastStructuredCandidate() {
        TerminalTagClipboard.clear();
        try {
            var writes = new ArrayList<String>();
            TerminalTagClipboard.copy("minecraft:item", List.of("c:ingots/iron"), writes::add);
            TerminalTagClipboard.copy("minecraft:fluid", List.of(), writes::add);
            assertEquals(List.of("c:ingots/iron"), writes);
            assertEquals("minecraft:item", TerminalTagClipboard.recent().resourceType());
            assertThrows(
                    IllegalStateException.class,
                    () -> TerminalTagClipboard.copy("minecraft:fluid", List.of("c:water"), value -> {
                        throw new IllegalStateException();
                    }));
            assertEquals("c:ingots/iron", TerminalTagClipboard.recent().text());
        } finally {
            TerminalTagClipboard.clear();
        }
        assertNull(TerminalTagClipboard.recent());
    }
}
