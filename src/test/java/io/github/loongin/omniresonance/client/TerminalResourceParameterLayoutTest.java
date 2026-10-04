// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import org.junit.jupiter.api.Test;

final class TerminalResourceParameterLayoutTest {
    @Test
    void optionalBatchDoesNotPutTheCommonHintInsideItsControls() {
        var body = TerminalLayout.terminal(640, 360).content();
        var rate = TerminalResourceParameterLayout.of(body, false, false);
        var batch = TerminalResourceParameterLayout.of(body, true, false);
        assertTrue(batch.hint().y() >= batch.batch().bottom() + 6);
        assertEquals(
                rate.dialog().bottom() - rate.hint().y(),
                batch.dialog().bottom() - batch.hint().y());
    }

    @Test
    void rateOnlyAndBatchFormsKeepTheSameFirstFieldAndActionTemplate() {
        var body = TerminalLayout.terminal(640, 360).content();
        var rate = TerminalResourceParameterLayout.of(body, false, false);
        var batch = TerminalResourceParameterLayout.of(body, true, false);
        assertEquals(rate.rate().width(), batch.rate().width());
        assertEquals(rate.rate().x(), batch.rate().x());
        assertEquals(42, rate.rate().y() - rate.dialog().y());
        assertEquals(42, batch.rate().y() - batch.dialog().y());
        assertEquals(6, batch.batch().x() - batch.mode().right());
        assertTrue(batch.mode().bottom()
                < TerminalActionLayout.button(batch.dialog(), 3, 0).y());
        assertTrue(rate.hint().bottom()
                < TerminalActionLayout.button(rate.dialog(), 3, 0).y());
        assertEquals(80, TerminalActionLayout.button(rate.dialog(), 3, 0).width());
    }

    @Test
    void exchangeUsesTheNodeNativeUnitsAndLocalizesItems() {
        var item = (net.minecraft.network.chat.contents.TranslatableContents)
                ExchangeScreen.parameterUnit(ResourceTypes.ITEM, "item").getContents();
        assertEquals("omniresonance.resource_policy.unit.item", item.getKey());
        assertEquals(
                "mB", ExchangeScreen.parameterUnit(ResourceTypes.FLUID, "mB").getString());
        assertEquals(
                "FE", ExchangeScreen.parameterUnit(ResourceTypes.ENERGY, "FE").getString());
    }
}
