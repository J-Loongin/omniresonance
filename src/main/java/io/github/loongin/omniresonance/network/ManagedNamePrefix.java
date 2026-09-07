// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;

/** Immutable bounded localized prefix used only to derive player-visible managed-name suggestions. */
public final class ManagedNamePrefix {
    private static final int MAXIMUM_UNICODE_SCALARS = 48;
    private static final int MAXIMUM_UTF8_BYTES = 192;
    private static final long MAXIMUM_SUGGESTION_NUMBER = 65535;

    private final String value;

    /** Normalizes a caller-owned prefix without accessing authority, persistence or world state. */
    public ManagedNamePrefix(String input) {
        Objects.requireNonNull(input, "input");
        validateCharacters(input);
        String normalized = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > MAXIMUM_UNICODE_SCALARS
                || normalized.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_UTF8_BYTES) {
            throw new IllegalArgumentException("Invalid managed name prefix length");
        }
        value = normalized;
    }

    public String value() {
        return value;
    }

    /** Builds one bounded managed name without reserving or consuming its number. */
    public ManagedName numbered(long number) {
        if (number < 1 || number > MAXIMUM_SUGGESTION_NUMBER) {
            throw new IllegalArgumentException("Invalid managed name suggestion number");
        }
        return new ManagedName(value + " " + number);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ManagedNamePrefix prefix && value.equals(prefix.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    private static void validateCharacters(String input) {
        for (int index = 0; index < input.length(); ) {
            int codePoint = input.codePointAt(index);
            if (Character.isISOControl(codePoint)
                    || codePoint == '§'
                    || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                throw new IllegalArgumentException("Invalid character in managed name prefix");
            }
            index += Character.charCount(codePoint);
        }
    }
}
