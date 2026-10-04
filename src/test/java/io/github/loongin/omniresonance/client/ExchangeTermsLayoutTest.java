// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ExchangeTermsLayoutTest {
    @Test
    void creationAndModificationShowAllFieldsAndKeepWarningAboveActions() {
        var body = TerminalLayout.terminal(640, 360).content();
        for (boolean warning : new boolean[] {false, true}) {
            var layout = ExchangeTermsLayout.of(body, warning, false);
            assertTrue(layout.form().height() >= 96);
            assertEquals(layout.name().x(), layout.form().x());
            assertEquals(layout.name().right(), layout.form().right());
            assertTrue(layout.peer().bottom() + 4 <= layout.form().y());
            if (warning) {
                assertTrue(layout.form().bottom() <= layout.warning().y());
                assertTrue(layout.warning().bottom() + 4
                        <= layout.footer().primary().y());
            }
        }
    }
}
