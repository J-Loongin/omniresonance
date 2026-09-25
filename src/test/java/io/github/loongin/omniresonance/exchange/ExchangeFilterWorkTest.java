// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ExchangeFilterWorkTest {
    @Test
    void nativeMembersAreCapturedIncrementallyAndInvalidatedBeforeReuse() {
        UUID root = new UUID(301, 1);
        var preset = new ResourceFilterPreset(
                root,
                new ManagedName("Native tags"),
                0,
                List.of(new ResourceFilterRule.Match(
                        new UUID(301, 2),
                        ResourceTypes.ITEM,
                        ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:sample")),
                        ComponentCondition.idOnly())));
        var consent = ExchangeConsent.propose(
                ExchangeConsentTest.INVITE,
                ExchangeConsentTest.A,
                ExchangeConsentTest.SOURCE,
                ExchangeConsentTest.TARGET);
        var agreement = new ExchangeAgreement(
                new UUID(301, 3),
                consent,
                new ExchangeTerms(
                        ResourceScope.all(),
                        FilterMode.WHITELIST,
                        ExchangeFilterSnapshot.capture(root, Map.of(root, preset), 1, 1),
                        64,
                        Map.of(),
                        1));
        AtomicInteger opens = new AtomicInteger(), members = new AtomicInteger();
        boolean[] missing = {false};
        var filter = new ExchangeFilterWork(agreement, key -> {
            opens.incrementAndGet();
            if (missing[0]) return null;
            return new java.util.Iterator<ResourceLocation>() {
                int index;

                public boolean hasNext() {
                    return index < 100;
                }

                public ResourceLocation next() {
                    members.incrementAndGet();
                    return ResourceLocation.fromNamespaceAndPath("example", "item_" + index++);
                }
            };
        });
        assertEquals(0, opens.get());
        assertEquals(0, filter.advance(0));
        for (int step = 0; step < 400 && filter.view().compiled() == null; step++) {
            int before = members.get();
            assertTrue(filter.advance(1) <= 1);
            assertTrue(members.get() - before <= 1);
        }
        assertTrue(filter.view().compiled().valid());
        assertEquals(1, opens.get());
        assertEquals(100, members.get());
        Object token = filter.view().token();
        missing[0] = true;
        filter.tagsChanged();
        assertFalse(token == filter.view().token());
        assertTrue(filter.view().compiled() == null);
        for (int step = 0; step < 100 && filter.view().compiled() == null; step++) filter.advance(1);
        assertFalse(filter.view().compiled().valid());
        filter.close();
        assertThrows(IllegalStateException.class, filter::view);
    }
}
