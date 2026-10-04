// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TerminalForegroundLayerTest {
    @Test
    void wholeDropdownIsDrawnAfterCardsAndTheirLabels() {
        var events = new ArrayList<String>();
        TerminalForegroundLayer.ordered(
                () -> events.addAll(List.of("window", "cards", "labels")),
                () -> events.addAll(List.of("popup_surface", "popup_rows", "popup_scrollbar")));
        assertEquals(List.of("window", "cards", "labels", "popup_surface", "popup_rows", "popup_scrollbar"), events);
    }
}
