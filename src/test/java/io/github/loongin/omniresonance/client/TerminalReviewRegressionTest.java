// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.compat.ae2.Ae2InterfacePayloads;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

final class TerminalReviewRegressionTest {
    static Font font() {
        return new Font(
                id -> {
                    throw new AssertionError("No glyph rendering");
                },
                false) {
            @Override
            public int width(String text) {
                return text.length();
            }

            @Override
            public int width(FormattedText text) {
                return text.getString().length();
            }

            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.min(text.length(), Math.max(0, width)));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean reverse) {
                return plainSubstrByWidth(text, width);
            }

            @Override
            public List<FormattedCharSequence> split(FormattedText text, int width) {
                return List.of(Component.literal(text.getString()).getVisualOrderText());
            }
        };
    }

    private static final class Batch implements TerminalTheme.RoundedBatch {
        int started, ended, quads;

        public void begin() {
            started++;
        }

        public void end() {
            ended++;
        }

        public void draw(int left, int top, int right, int bottom, int color) {
            assertTrue(right > left && bottom > top);
            quads++;
        }
    }

    @Test
    void fourTimesRoundedSlotGeometryHasTwoLayerSubmissionsAtBothInventoryScales() {
        for (int screenHeight : new int[] {216, 372}) {
            var layout = DomainInventoryLayout.window(640, screenHeight);
            var geometry = new DomainInventoryLayout(layout.content(), true);
            int resources = geometry.columns() * geometry.rows();
            assertEquals(screenHeight == 216 ? 27 : 99, resources);
            int slots = 36 + resources * 2;
            var batch = new Batch();
            for (int index = 0; index < slots; index++) {
                TerminalTheme.fillRounded(0, 0, 72, 72, 8, TerminalTheme.LINE, batch);
                TerminalTheme.fillRounded(4, 4, 64, 64, 4, TerminalTheme.INPUT, batch);
            }
            assertEquals(slots * 26, batch.quads, "Keep every quarter-pixel span");
            assertEquals(slots * 2, batch.started, "Open one batch per layer");
            assertEquals(slots * 2, batch.ended, "Submit once per layer, not once per span");
        }
    }

    @Test
    void emptyGeometryOpensNoBatchAndAnEmissionFailureStillClosesTheLayer() {
        int[] boundaries = {0, 0};
        var batch = new TerminalTheme.RoundedBatch() {
            public void begin() {
                boundaries[0]++;
            }

            public void end() {
                boundaries[1]++;
            }

            public void draw(int left, int top, int right, int bottom, int color) {
                throw new IllegalStateException("Vertex emission failed");
            }
        };
        TerminalTheme.fillRounded(0, 0, 0, 72, 8, TerminalTheme.LINE, batch);
        assertEquals(0, boundaries[0]);
        assertEquals(0, boundaries[1]);
        assertThrows(
                IllegalStateException.class,
                () -> TerminalTheme.fillRounded(0, 0, 72, 72, 8, TerminalTheme.LINE, batch));
        assertEquals(1, boundaries[0]);
        assertEquals(1, boundaries[1]);
    }

    @Test
    void ordinaryDisabledSelectionsDoNotBecomeActiveSnapshotRows() {
        var row = new TerminalRowButton(0, 0, 100, 20, Component.literal("Readout"), ignored -> {});
        row.setSelected(true);
        row.active = false;
        assertFalse(row.selectionMarked());
        assertEquals(TerminalTheme.DISABLED_TEXT, row.textColor());
        row.setReadOnly();
        assertFalse(row.selectionMarked());
        assertEquals(TerminalTheme.TEXT, row.textColor());
    }

    @Test
    void aeUnbindWaitKeepsItsOldMarkButUnavailabilityRemovesTheBindingAssertion() {
        UUID session = new UUID(11, 1), first = new UUID(12, 2);
        var requests = new ArrayList<Ae2InterfacePayloads.Request>();
        var screen = new Ae2InterfaceScreen(
                new Ae2InterfacePayloads.Frame(
                        session,
                        0,
                        true,
                        first,
                        "ready",
                        "",
                        List.of(new Ae2InterfacePayloads.Choice(first, "First")),
                        1,
                        false),
                (key, scan) -> false,
                requests::add);
        screen.build(font(), 427, 240);
        row(screen, "First").onPress();
        var request = requests.getLast();
        assertEquals(2, request.action());
        assertTrue(row(screen, "First").selectionMarked());
        assertFalse(row(screen, "First").active);
        screen.accept(new Ae2InterfacePayloads.Frame(
                session, request.sequence(), false, null, "unavailable", "unavailable", List.of(), -1, false));
        assertFalse(row(screen, "First").selectionMarked());
        assertFalse(row(screen, "First").active);
        screen.tick();
        assertEquals(1, requests.size(), "Unavailable pages must not issue more requests");
    }

    @Test
    void actualDangerButtonKeepsItsSurfaceAndHasAKeyboardFocusBorder() {
        var button = new TerminalButton(
                0, 0, 80, 20, Component.translatable("omniresonance.exchange.terminate"), ignored -> {}, false);
        assertEquals(TerminalTheme.DANGER_LINE, button.controlStyle().border());
        button.setFocused(true);
        assertEquals(TerminalTheme.DANGER, button.controlStyle().surface());
        assertEquals(TerminalTheme.ACCENT, button.controlStyle().border());
        button.active = false;
        assertEquals(TerminalTheme.LINE, button.controlStyle().border());
        assertEquals(TerminalTheme.DISABLED_TEXT, button.controlStyle().text());
    }

    @Test
    void actualSelectedWorkingFaceUsesTheSelectionSurfaceWhileFixedFacesStayReadable() {
        var bounds = new TerminalLayout.Rect(0, 0, 100, 70);
        var selected = new TerminalPreviewCard(
                bounds,
                Component.literal("East"),
                Component.literal("Chest"),
                ItemStack.EMPTY,
                true,
                false,
                Component.empty(),
                () -> {});
        assertEquals(TerminalTheme.ACCENT_SOFT, selected.controlStyle().surface());
        var fixed = new TerminalPreviewCard(
                bounds,
                Component.literal("North"),
                Component.literal("Chest"),
                ItemStack.EMPTY,
                true,
                true,
                Component.empty(),
                () -> {});
        fixed.active = false;
        assertEquals(TerminalTheme.INPUT, fixed.controlStyle().surface());
        assertEquals(TerminalTheme.TEXT, fixed.controlStyle().text());
    }

    private static TerminalRowButton row(Ae2InterfaceScreen screen, String name) {
        return screen.children().stream()
                .filter(TerminalRowButton.class::isInstance)
                .map(TerminalRowButton.class::cast)
                .filter(row -> row.getMessage().getString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aeWriteWaitKeepsOnlyTheLastConfirmedSelectionMarked() {
        UUID session = new UUID(1, 1), first = new UUID(2, 2), second = new UUID(3, 3);
        var requests = new ArrayList<Ae2InterfacePayloads.Request>();
        var screen = new Ae2InterfaceScreen(
                new Ae2InterfacePayloads.Frame(
                        session,
                        0,
                        true,
                        first,
                        "ready",
                        "",
                        List.of(
                                new Ae2InterfacePayloads.Choice(first, "First"),
                                new Ae2InterfacePayloads.Choice(second, "Second")),
                        2,
                        false),
                (key, scan) -> false,
                requests::add);
        screen.build(font(), 427, 240);
        row(screen, "Second").onPress();
        assertEquals(second, requests.getLast().network());
        assertFalse(row(screen, "First").active);
        assertFalse(row(screen, "Second").active);
        assertTrue(row(screen, "First").selectionMarked());
        assertFalse(row(screen, "Second").selectionMarked());
        assertFalse(row(screen, "First").highlighted(), "Disabled snapshots must not turn active blue");
        assertEquals(TerminalTheme.DISABLED_TEXT, row(screen, "First").textColor());
        for (int tick = 0; tick < 400; tick++) screen.tick();
        assertFalse(row(screen, "First").selectionMarked(), "Unknown write results cannot assert a binding");
    }

    @Test
    void actualExchangeInputAndConfirmationUseTheSameContentCentre() {
        var screen = new ExchangeScreen(null, null);
        screen.build(font(), 427, 240);
        var layout = TerminalLayout.terminal(427, 240);
        screen.input(Component.literal("Name"), "Original", value -> {});
        assertEquals(TerminalDialogLayout.editor(layout.content()), screen.dialogBounds());
        screen.cancelDialog();
        screen.confirm(Component.literal("Confirm"), () -> {});
        assertEquals(
                TerminalDialogLayout.confirmation(layout.content(), font(), Component.literal("Confirm")),
                screen.dialogBounds());
    }

    @Test
    void actualPendingTankRowsUseDisabledTextAndCannotSelect() {
        var picker = new TerminalSamplePicker();
        picker.open();
        var actions = new ArrayList<TerminalFilterView.Action>();
        picker.offerTanks(
                List.of(
                        new TerminalSamplePicker.Tank(0, new FluidStack(Fluids.WATER, 1000)),
                        new TerminalSamplePicker.Tank(1, new FluidStack(Fluids.LAVA, 1000))),
                ResourceTypes.FLUID,
                actions::add);
        var widgets = new ArrayList<AbstractWidget>();
        picker.build(
                font(),
                new TerminalLayout.Rect(0, 0, 350, 180),
                ResourceTypes.FLUID,
                true,
                widgets::add,
                actions::add,
                () -> {});
        assertEquals(2, widgets.size());
        for (var widget : widgets) {
            var button = (TerminalSamplePicker.FluidButton) widget;
            assertFalse(button.active);
            assertEquals(TerminalTheme.DISABLED_TEXT, button.textColor());
            assertFalse(button.mouseClicked(button.getX() + 2, button.getY() + 2, 0));
        }
        assertTrue(actions.isEmpty());
    }
}
