// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ExchangeAgreementTest {
    @Test
    void revisionBindsTermsAndRequiresCounterpartyReapprovalWithoutOnlinePlayers() {
        ExchangeTerms first = new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 64, Map.of(), 1);
        ExchangeConsent consent = ExchangeConsent.propose(
                        ExchangeConsentTest.INVITE,
                        ExchangeConsentTest.A,
                        ExchangeConsentTest.SOURCE,
                        ExchangeConsentTest.TARGET)
                .approve(ExchangeConsent.Side.TARGET, ExchangeConsentTest.B, ExchangeConsentTest.TARGET, 0);
        ExchangeAgreement original = new ExchangeAgreement(new UUID(10, 1), consent, first);
        ExchangeTerms replacement =
                new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 128, Map.of(), 2);
        ExchangeAgreement changed = original.revise(
                ExchangeConsent.Side.SOURCE,
                ExchangeConsentTest.A,
                ExchangeConsentTest.SOURCE,
                consent.revision(),
                replacement);
        assertEquals(replacement, changed.terms());
        assertEquals(first, original.terms());
        assertEquals(original.id(), changed.id());
        assertTrue(original.consent().permitsExecution(ExchangeConsentTest.SOURCE, ExchangeConsentTest.TARGET));
        assertFalse(changed.consent().permitsExecution(ExchangeConsentTest.SOURCE, ExchangeConsentTest.TARGET));
        assertThrows(
                IllegalStateException.class,
                () -> changed.revise(
                        ExchangeConsent.Side.SOURCE,
                        ExchangeConsentTest.A,
                        ExchangeConsentTest.SOURCE,
                        consent.revision(),
                        first));
        assertThrows(
                SecurityException.class,
                () -> original.revise(
                        ExchangeConsent.Side.SOURCE,
                        ExchangeConsentTest.ADMIN,
                        ExchangeConsentTest.SOURCE,
                        consent.revision(),
                        replacement));
    }

    @Test
    void detachedSparseRatesSupportFutureTypesAndLongQuantities() {
        ResourceLocation custom = ResourceLocation.parse("example:future_resource");
        Map<ResourceLocation, Long> rates = new HashMap<>();
        rates.put(custom, Long.MAX_VALUE);
        ExchangeTerms terms = new ExchangeTerms(ResourceScope.all(), FilterMode.BLACKLIST, null, 64, rates, 1);
        rates.clear();
        assertEquals(Long.MAX_VALUE, terms.rate(custom));
        assertEquals(64, terms.rate(ResourceTypes.ITEM));
        assertThrows(UnsupportedOperationException.class, () -> terms.rates().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 0, Map.of(), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 1, Map.of(custom, -1L), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 1, Map.of(), 0));
    }
}
