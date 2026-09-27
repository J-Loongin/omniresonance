// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.loongin.omniresonance.compat.ae2.Ae2InterfacePayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import org.junit.jupiter.api.Test;

class AeInterfaceSelectionTest {
    @Test
    void actualRowsToggleBindingWithoutAnExtraUnbindRowAndWaitForServer() {
        var session = new UUID(1, 1);
        var network = new UUID(2, 2);
        var requests = new ArrayList<Ae2InterfacePayloads.Request>();
        var screen = new Ae2InterfaceScreen(
                new Ae2InterfacePayloads.Frame(
                        session,
                        0,
                        true,
                        null,
                        "unbound",
                        "",
                        List.of(new Ae2InterfacePayloads.Choice(network, "Network")),
                        1,
                        false),
                (key, scan) -> false,
                requests::add);
        var font = new Font(
                id -> {
                    throw new AssertionError("No glyph rendering");
                },
                false);
        screen.build(font, 427, 240);
        var rows = screen.children().stream()
                .filter(TerminalRowButton.class::isInstance)
                .map(TerminalRowButton.class::cast)
                .toList();
        assertEquals(1, rows.size());
        rows.getFirst().onPress();
        assertEquals(1, requests.getLast().action());
        assertEquals(network, requests.getLast().network());
        screen.build(font, 427, 240);
        assertFalse(screen.children().stream()
                .filter(TerminalRowButton.class::isInstance)
                .map(TerminalRowButton.class::cast)
                .findFirst()
                .orElseThrow()
                .active);
        screen.accept(new Ae2InterfacePayloads.Frame(
                session, requests.getLast().sequence(), false, network, "ready", "", List.of(), -1, false));
        screen.children().stream()
                .filter(TerminalRowButton.class::isInstance)
                .map(TerminalRowButton.class::cast)
                .findFirst()
                .orElseThrow()
                .onPress();
        assertEquals(2, requests.getLast().action());
        assertNull(requests.getLast().network());
    }

    @Test
    void sourceUsesNativeLiquidSpriteWithoutChangingItsUnit() {
        var source = io.github.loongin.omniresonance.compat.ars.SourceVariant.INSTANCE;
        assertEquals(
                net.minecraft.resources.ResourceLocation.parse("ars_nouveau:block/source_still"), source.texture());
        assertEquals("Source", source.unit());
    }
}
