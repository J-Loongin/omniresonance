// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ManagedNameTest {
    @Test
    void normalizesTrimmedNameAndBuildsAsciiOnlyUniquenessKey() {
        ManagedName name = new ManagedName("  Cafe\u0301  A  ");

        assertEquals("Café  A", name.value());
        assertEquals("café  a", name.uniquenessKey());
    }

    @Test
    void rejectsEmptyNamesAfterTrimming() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedName(""));
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("   "));
    }

    @Test
    void countsSupplementaryCharactersAsSingleUnicodeScalars() {
        assertEquals(64, new ManagedName("\uD83D\uDE00".repeat(64)).value().codePointCount(0, 128));
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("\uD83D\uDE00".repeat(65)));
    }

    @Test
    void measuresLengthAfterNfcNormalization() {
        assertEquals("é".repeat(64), new ManagedName("e\u0301".repeat(64)).value());
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("e\u0301".repeat(65)));
    }

    @Test
    void acceptsNameAtUtf8ByteLimit() {
        assertEquals(256, new ManagedName("\uD83D\uDE00".repeat(64)).value().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void rejectsUnpairedSurrogates() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("\uDC00"));
    }

    @Test
    void rejectsControlsBeforeTrimmingAndMinecraftFormattingControls() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("\tname"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("name\n"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("name\u007f"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("name\u0085"));
        assertThrows(IllegalArgumentException.class, () -> new ManagedName("name§a"));
    }

    @Test
    void keepsNonAsciiCaseDistinctInUniquenessKey() {
        ManagedName upper = new ManagedName("Ä");
        ManagedName lower = new ManagedName("ä");

        assertNotEquals(upper.uniquenessKey(), lower.uniquenessKey());
    }

    @Test
    void equalsAndHashCodeUseCanonicalDisplayValue() {
        ManagedName decomposed = new ManagedName("Cafe\u0301");
        ManagedName composed = new ManagedName("Café");
        ManagedName differingCase = new ManagedName("café");

        assertEquals(decomposed, composed);
        assertEquals(decomposed.hashCode(), composed.hashCode());
        assertNotEquals(decomposed, differingCase);
    }

    @Test
    void rejectsNullInputImmediately() {
        assertThrows(NullPointerException.class, () -> new ManagedName(null));
    }
}
