// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerminalStatusSuffixTest {
    @Test
    void longResourceIdsCannotTruncateTheirAvailabilityStatus() {
        for (String status : new String[] {"(unavailable)", "（不可用）"}) {
            var shown = TerminalText.statusText(
                    "example:ultra_dense_neutronium_storage_medium", status, 26, String::length);
            assertEquals(status, shown.status());
            assertTrue(shown.prefix().endsWith("…"));
            assertTrue(shown.prefix().length() + 3 + shown.status().length() <= 26);
        }
    }

    @Test
    void anExtremelyNarrowRowPrioritizesStatusWithoutLeakingOutsideItsWidth() {
        var shown = TerminalText.statusText("example:resource", "unavailable", 6, String::length);
        assertEquals("", shown.prefix());
        assertEquals("unava…", shown.status());
    }
}
