// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.exchange.ExchangeAgreement;
import io.github.loongin.omniresonance.exchange.ExchangeConsent;
import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkMetadata;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ExchangeRuleViewTest {
    private static final UUID A = new UUID(1, 1), B = new UUID(1, 2);
    private static final NetworkMetadata SOURCE =
            new NetworkMetadata(new UUID(2, 1), A, new ManagedName("发送网络"), 0, Set.of());
    private static final NetworkMetadata TARGET =
            new NetworkMetadata(new UUID(2, 2), B, new ManagedName("接收网络"), 1, Set.of());

    private static ExchangeAgreement agreement(UUID invitation) {
        return new ExchangeAgreement(
                new UUID(3, 1),
                new ExchangeConsent(
                        invitation,
                        SOURCE.id(),
                        TARGET.id(),
                        A,
                        B,
                        3,
                        1,
                        new ExchangeConsent.Approval(true, false),
                        new ExchangeConsent.Approval(true, false),
                        false),
                new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, Long.MAX_VALUE, Map.of(), 1));
    }

    @Test
    void invitationIdentityDoesNotAffectClientProjectionAndViewRoundTrips() {
        var first = ExchangeRuleView.from(agreement(new UUID(9, 1)), SOURCE, TARGET);
        var second = ExchangeRuleView.from(agreement(new UUID(9, 2)), SOURCE, TARGET);
        assertEquals(first, second);
        assertEquals(ExchangeRuleView.State.APPROVED, first.state());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            first.write(buffer);
            assertEquals(first, ExchangeRuleView.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void missingOrChangedOwnersCannotLookLikeActiveApprovedRules() {
        var missing = ExchangeRuleView.from(agreement(new UUID(9, 1)), null, TARGET);
        assertEquals(ExchangeRuleView.State.UNAVAILABLE, missing.state());
        assertEquals(null, missing.source().name());
        var changed = new NetworkMetadata(SOURCE.id(), B, SOURCE.name(), 0, Set.of());
        assertEquals(
                ExchangeRuleView.State.UNAVAILABLE,
                ExchangeRuleView.from(agreement(new UUID(9, 1)), changed, TARGET)
                        .state());
        assertThrows(
                IllegalArgumentException.class, () -> ExchangeRuleView.from(agreement(new UUID(9, 1)), TARGET, SOURCE));
    }

    @Test
    void unknownFlagsAndTruncationFailClosed() {
        var view = ExchangeRuleView.from(agreement(new UUID(9, 1)), SOURCE, TARGET);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            view.write(buffer);
            buffer.setByte(32, 32);
            assertThrows(IllegalArgumentException.class, () -> ExchangeRuleView.read(buffer));
            buffer.clear();
            view.write(buffer);
            buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(RuntimeException.class, () -> ExchangeRuleView.read(buffer));
        } finally {
            buffer.release();
        }
    }
}
