// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class NodeChunkButtonTest {
    @Test
    void runtimeStatesOnlyUpdateTooltipWithoutReplacingTheToggleLabel() {
        for (String state : new String[] {"on", "off"}) {
            var button = new TerminalButton(
                    0,
                    0,
                    64,
                    20,
                    Component.translatable("omniresonance.node_menu.chunk_request.short." + state),
                    ignored -> {},
                    false);
            var original = button.getMessage();
            for (var status : ChunkLoadingAllocator.Status.values()) {
                ResonanceNodeScreen.updateChunkTooltip(button, status);
                assertEquals(original, button.getMessage());
                assertNotNull(button.getTooltip());
            }
        }
    }
}
