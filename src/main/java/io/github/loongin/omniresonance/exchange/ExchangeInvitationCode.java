// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque invitation addressing, never an approval credential. Pure encode/decode methods are thread-safe and own
 * their outputs, with no world access or authority mutation. Identity generation alone consumes platform secure
 * entropy; it does not register, persist, log or copy a code. Invalid/noncanonical input is rejected before lookup.
 */
public final class ExchangeInvitationCode {
    private static final SecureRandom ENTROPY = new SecureRandom();

    private ExchangeInvitationCode() {}

    /** Generates 128 unpredictable bits for later authorized issuance; callers still check bounded collisions. */
    public static UUID newIdentity() {
        return new UUID(ENTROPY.nextLong(), ENTROPY.nextLong());
    }

    /** Encodes all identity bits as a 22-character URL-safe string, without logging or clipboard access. */
    public static String encode(UUID id) {
        Objects.requireNonNull(id);
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Bounded canonical parsing only; possession of the returned identity grants no network permissions. */
    public static UUID decode(String code) {
        Objects.requireNonNull(code);
        if (code.length() != 22) throw new IllegalArgumentException("Invalid invitation code length");
        byte[] bytes = Base64.getUrlDecoder().decode(code);
        if (bytes.length != 16) throw new IllegalArgumentException("Invalid invitation code identity");
        ByteBuffer input = ByteBuffer.wrap(bytes);
        UUID id = new UUID(input.getLong(), input.getLong());
        if (!encode(id).equals(code)) throw new IllegalArgumentException("Noncanonical invitation code");
        return id;
    }
}
