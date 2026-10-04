// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NodeHighlightRendererTest {
    @Test
    void beamHasAVisibleCoreAndKeepsItsUpperPartVisibleInBrightScenes() {
        assertTrue(NodeHighlightRenderer.beamAlpha(5, false) >= 102);
        assertTrue(NodeHighlightRenderer.beamAlpha(5, true) >= 64);
        assertTrue(NodeHighlightRenderer.beamAlpha(0, false) > 0);
    }

    @Test
    void softBeamProfileIsSymmetricTranslucentAndFadesTowardsItsEdges() {
        for (int strip = 0; strip < 12; strip++) {
            int alpha = NodeHighlightRenderer.beamAlpha(strip, false);
            assertEquals(alpha, NodeHighlightRenderer.beamAlpha(11 - strip, false));
            assertTrue(alpha > 0 && alpha < 128);
            assertTrue(NodeHighlightRenderer.beamAlpha(strip, true) <= alpha);
        }
        assertTrue(NodeHighlightRenderer.beamAlpha(5, false) > NodeHighlightRenderer.beamAlpha(0, false) * 10);
    }
}
