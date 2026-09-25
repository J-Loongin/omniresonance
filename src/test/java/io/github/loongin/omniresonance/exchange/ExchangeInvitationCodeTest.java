// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeInvitationCodeTest {
    @Test
    void canonicalCodeRoundTripsAllIdentityBits() {
        for (UUID id : new UUID[] {new UUID(0, 0), new UUID(-1, -1), new UUID(Long.MIN_VALUE, Long.MAX_VALUE)}) {
            String code = ExchangeInvitationCode.encode(id);
            assertEquals(22, code.length());
            assertEquals(id, ExchangeInvitationCode.decode(code));
        }
    }

    @Test
    void rejectsUnboundedNonCanonicalAndPaddedForms() {
        String code = ExchangeInvitationCode.encode(new UUID(0, 0));
        for (String invalid : new String[] {
            "", code + "==", " " + code, code.substring(1), "!".repeat(22), "A".repeat(21) + "B", "A".repeat(100000)
        }) assertThrows(IllegalArgumentException.class, () -> ExchangeInvitationCode.decode(invalid));
    }
}
