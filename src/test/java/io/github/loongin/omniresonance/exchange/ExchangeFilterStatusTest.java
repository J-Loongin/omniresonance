// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExchangeFilterStatusTest {
    @Test
    void diagnosticsDistinguishMissingInvalidPreparingAndValidSnapshots() {
        var snapshot = io.github.loongin.omniresonance.exchange.fixtures.ExchangeFilters.allResources();
        var terms = new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, snapshot, 1, Map.of(), 1);
        assertEquals(ExchangeFilterStatus.PREPARING, ExchangeFilterStatus.of(terms, null));
        var owner = new ResourceFilterCompiler.OwnerSnapshot(new UUID(1, 1), snapshot.presets());
        var compiled = ResourceFilterCompiler.compile(
                snapshot.root(), owner, (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0));
        assertEquals(ExchangeFilterStatus.READY, ExchangeFilterStatus.of(terms, compiled));
        var invalid = ResourceFilterCompiler.compile(
                new UUID(0, 0), owner, (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0));
        assertEquals(ExchangeFilterStatus.INVALID, ExchangeFilterStatus.of(terms, invalid));
        assertTrue(ExchangeFilterStatus.of(terms, invalid).blocked());
        assertEquals(
                ExchangeFilterStatus.MISSING,
                ExchangeFilterStatus.of(
                        new ExchangeTerms(ResourceScope.all(), FilterMode.WHITELIST, null, 1, Map.of(), 1), compiled));
    }
}
