// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ExchangeRateInitialTest {
    @Test
    void newTypeUsesNodeRateGreedyModeAndCatalogBatchSample() {
        var value = ExchangeScreen.initialParameter(Map.of(), ResourceTypes.FLUID, ExchangeTerms.DEFAULT_RATE, 1000);
        assertEquals(ResourceTransferPolicy.DEFAULT_RATE, value.rate());
        assertEquals(ResourceTransferPolicy.BatchMode.GREEDY, value.batchMode());
        assertEquals(1000, value.batchSize());
        var editor = new ResourceParameterDraft(ResourceTypes.FLUID, value);
        var form = editor.form(ExchangeScreen.parameterUnit(ResourceTypes.FLUID, "mB"), true, true);
        assertEquals("2147483647", form.rate());
        assertEquals("mB", form.unit().getString());
        assertEquals("1000", form.batch().quantity());
    }

    @Test
    void existingCompleteParametersAndExplicitMaximumAreRetained() {
        var existing =
                new ResourceTransferPolicy.InputOverride(Long.MAX_VALUE, ResourceTransferPolicy.BatchMode.EXACT, 500);
        var value =
                ExchangeScreen.initialParameter(Map.of(ResourceTypes.FLUID, existing), ResourceTypes.FLUID, 128, 1000);
        assertEquals(existing, value);
        var editor = new ResourceParameterDraft(ResourceTypes.FLUID, value);
        assertEquals(Long.toString(Long.MAX_VALUE), editor.rate);
        assertEquals(existing, editor.value());
    }

    @Test
    void nativeFluidQuantitiesRoundTripExactlyWithoutBucketConversion() {
        var value = ExchangeScreen.initialParameter(Map.of(), ResourceTypes.FLUID, 1001, 1000);
        var editor = new ResourceParameterDraft(ResourceTypes.FLUID, value);
        assertEquals("1001", editor.rate);
        assertEquals(1001, editor.value().rate());
        assertEquals(ResourceTransferPolicy.BatchMode.GREEDY, editor.value().batchMode());
    }
}
