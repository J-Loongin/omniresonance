// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ManagedNamePrefixTest {
    @Test
    void normalizesAndBuildsBoundedNumberedNames() {
        ManagedNamePrefix prefix = new ManagedNamePrefix("  频\u9053  ");

        assertEquals("频道", prefix.value());
        assertEquals("频道 2", prefix.numbered(2).value());
        assertEquals(
                "a".repeat(48) + " 65535",
                new ManagedNamePrefix("a".repeat(48)).numbered(65535).value());
    }

    @Test
    void rejectsInvalidCharactersLengthsAndNumbers() {
        for (String value : java.util.List.of(" ", "bad\nname", "bad§name", "a".repeat(49), "😀".repeat(49))) {
            assertThrows(IllegalArgumentException.class, () -> new ManagedNamePrefix(value));
        }
        ManagedNamePrefix prefix = new ManagedNamePrefix("Channel");
        assertThrows(IllegalArgumentException.class, () -> prefix.numbered(0));
        assertThrows(IllegalArgumentException.class, () -> prefix.numbered(-1));
    }
}
