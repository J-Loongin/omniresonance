// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Objects;

/**
 * Immutable canonical display name for a network-managed object.
 *
 * <p>This value object is thread-safe and owns only its immutable normalized text. Construction performs no
 * simulation or external modification. The caller owns the authoritative input and must enforce scope-specific
 * uniqueness and operation preconditions separately. Invalid input fails with {@link IllegalArgumentException}; a
 * missing input fails with {@link NullPointerException}.
 */
public final class ManagedName {
    private static final int MAXIMUM_UNICODE_SCALARS = 64;
    private static final int MAXIMUM_UTF8_BYTES = 256;

    private final String value;

    /**
     * Creates a normalized managed name.
     *
     * <p>This operation is thread-safe, pure, and non-mutating. Ownership of {@code input} remains with the caller;
     * no simulation is performed. Invalid characters or lengths cause {@link IllegalArgumentException}, while a
     * missing input causes {@link NullPointerException}.
     *
     * @param input the caller-owned display name to validate and normalize
     */
    public ManagedName(String input) {
        Objects.requireNonNull(input, "input");
        validateInputCharacters(input);

        String normalized = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > MAXIMUM_UNICODE_SCALARS
                || normalized.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_UTF8_BYTES) {
            throw new IllegalArgumentException("Invalid managed name length");
        }
        this.value = normalized;
    }

    /**
     * Returns the immutable canonical display name.
     *
     * <p>This thread-safe accessor performs no simulation or modification. The returned {@link String} is immutable
     * and owned by this value object; callers must still apply operation-specific authorization and preconditions.
     *
     * @return the NFC-normalized display name
     */
    public String value() {
        return value;
    }

    /**
     * Returns the ASCII-case-folded key used for uniqueness checks.
     *
     * <p>This thread-safe accessor is pure and non-mutating. The returned {@link String} is immutable and owned by
     * this value object; this key does not replace scope-specific uniqueness checks or operation preconditions.
     *
     * @return the canonical display name with only ASCII uppercase letters folded to lowercase
     */
    public String uniquenessKey() {
        StringBuilder uniquenessKey = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 'A' && character <= 'Z') {
                uniquenessKey.append((char) (character + ('a' - 'A')));
            } else {
                uniquenessKey.append(character);
            }
        }
        return uniquenessKey.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ManagedName managedName && value.equals(managedName.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    private static void validateInputCharacters(String input) {
        for (int index = 0; index < input.length(); ) {
            int codePoint = input.codePointAt(index);
            if (Character.isISOControl(codePoint)
                    || codePoint == '§'
                    || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                throw new IllegalArgumentException("Invalid character in managed name");
            }
            index += Character.charCount(codePoint);
        }
    }
}
