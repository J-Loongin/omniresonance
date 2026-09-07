// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ClientSearchStateTest {
    @Test
    void normalizedQueriesKeepTheExistingUnicodeAndLengthBoundaries() {
        assertEquals("é", ClientSearchState.normalizedQuery("e\u0301"));
        assertEquals("", ClientSearchState.normalizedQuery(""));
        assertEquals("😀".repeat(64), ClientSearchState.normalizedQuery("😀".repeat(64)));
        assertNull(ClientSearchState.normalizedQuery("😀".repeat(65)));
        assertNull(ClientSearchState.normalizedQuery("a\nb"));
        assertNull(ClientSearchState.normalizedQuery("§a"));
        assertNull(ClientSearchState.normalizedQuery("\uD800"));
    }
}
