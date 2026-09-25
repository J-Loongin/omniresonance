// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeTermsDraftTest {
    @Test
    void intentOwnsRatesAndRejectsInvalidRevisionAndQuantities() {
        var rates = new HashMap<net.minecraft.resources.ResourceLocation, Long>();
        rates.put(ResourceTypes.ENERGY, Long.MAX_VALUE);
        var draft = new ExchangeTermsDraft(
                ResourceScope.all(),
                FilterMode.WHITELIST,
                new ExchangeTermsDraft.OwnerPreset(new UUID(1, 1), 1),
                64,
                rates,
                1);
        rates.clear();
        assertEquals(Long.MAX_VALUE, draft.rates().get(ResourceTypes.ENERGY));
        assertThrows(UnsupportedOperationException.class, () -> draft.rates().clear());
        assertThrows(IllegalArgumentException.class, () -> new ExchangeTermsDraft.OwnerPreset(new UUID(1, 1), -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeTermsDraft(
                        ResourceScope.all(), FilterMode.WHITELIST, new ExchangeTermsDraft.None(), 0, Map.of(), 1));
    }
}
