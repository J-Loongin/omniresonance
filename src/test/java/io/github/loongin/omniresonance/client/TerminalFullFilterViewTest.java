// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.filter.ComponentCondition;
import io.github.loongin.omniresonance.filter.PresetEditOperation;
import io.github.loongin.omniresonance.filter.ResourceFilterPreset;
import io.github.loongin.omniresonance.filter.ResourceFilterRule;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.networking.FilterImpactSummary;
import io.github.loongin.omniresonance.networking.FilterPresetPage;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.FilterRulePage;
import io.github.loongin.omniresonance.networking.FullFilterCodec;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class TerminalFullFilterViewTest {
    private static final UUID PRESET = new UUID(1, 1), RULE = new UUID(2, 2);
    private static final NetworkSummary NETWORK = new NetworkSummary(new UUID(3, 3), new UUID(4, 4), "Network");
    private static final FilterPresetSummary SUMMARY = new FilterPresetSummary(PRESET, "Preset", 0, 1, true);
    private static final NetworkTerminalState.Preset STATE = new NetworkTerminalState.Preset(
            NETWORK, SUMMARY, new FilterRulePage(List.of("minecraft:old_typo"), 0, 1, 0, List.of(RULE)));

    private static Font font() {
        return new Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false) {
            @Override
            public int width(String text) {
                return text.length();
            }

            @Override
            public String plainSubstrByWidth(String text, int width) {
                return text.substring(0, Math.max(0, Math.min(width, text.length())));
            }

            @Override
            public String plainSubstrByWidth(String text, int width, boolean reverse) {
                return plainSubstrByWidth(text, width);
            }
        };
    }

    private static List<AbstractWidget> build(
            TerminalFilterView view,
            TerminalLayout layout,
            NetworkTerminalState state,
            List<TerminalFilterView.Action> actions) {
        List<AbstractWidget> widgets = new ArrayList<>();
        view.build(font(), layout, state, "", false, widgets::add, value -> {}, actions::add);
        return widgets;
    }

    private static String key(AbstractWidget widget) {
        return widget.getMessage().getContents()
                        instanceof net.minecraft.network.chat.contents.TranslatableContents text
                ? text.getKey()
                : "";
    }

    @Test
    void actualThreePaneControlsReadAndEditPinnedRuleWithoutDisplayingUuid() {
        for (int[] size : new int[][] {{320, 240}, {640, 360}, {960, 540}}) {
            TerminalFilterView view = new TerminalFilterView(() -> {});
            view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0)));
            view.apply(STATE);
            var actions = new ArrayList<TerminalFilterView.Action>();
            var layout = TerminalLayout.calculate(size[0], size[1]);
            var widgets = build(view, layout, STATE, actions);
            assertEquals(
                    1,
                    widgets.stream()
                            .filter(TerminalSearchButton.class::isInstance)
                            .count());
            assertFalse(widgets.stream().anyMatch(widget -> key(widget).endsWith(".save")));
            var rows =
                    widgets.stream().filter(TerminalRowButton.class::isInstance).toList();
            assertEquals(2, rows.size());
            assertTrue(rows.get(0).getRight() < rows.get(1).getX(), "Library and rules overlap");
            ((TerminalRowButton) rows.get(1)).onPress();
            var read = (NetworkTerminalRequest.ReadResourceRule)
                    NetworkSetupScreen.resourceRuleRequest(actions.getLast(), PRESET, RULE, 1);
            assertEquals(RULE, read.ruleId());
            assertFalse(rows.get(1).getMessage().getString().contains(RULE.toString()));
            var rule = new ResourceFilterRule.Match(
                    RULE,
                    ResourceTypes.ITEM,
                    ResourceFilterRule.Selector.exact(ResourceLocation.parse("minecraft:old_typo")),
                    ComponentCondition.idOnly());
            view.acceptFull(new NetworkTerminalResponse.FullRule(
                    PRESET,
                    RULE,
                    1,
                    null,
                    0,
                    0,
                    "",
                    FullFilterCodec.snapshot(
                            new ResourceFilterPreset(PRESET, new ManagedName("Preset"), 0, List.of(rule)))));
            widgets = build(view, layout, STATE, actions);
            ((TerminalButton) widgets.stream()
                            .filter(widget -> key(widget).endsWith(".edit_rule_full"))
                            .findFirst()
                            .orElseThrow())
                    .onPress();
            var begin = (NetworkTerminalRequest.BeginResourceRule)
                    NetworkSetupScreen.resourceRuleRequest(actions.getLast(), PRESET, RULE, 2);
            assertEquals(RULE, begin.ruleId());
            var edit = new NetworkTerminalState.PresetEdit(
                    NETWORK,
                    SUMMARY,
                    PresetEditOperation.EDIT_RULE,
                    new FilterImpactSummary(0, 0, 0, true),
                    RULE.toString());
            view.apply(edit);
            widgets = build(view, layout, edit, actions);
            var footer = TerminalActionLayout.of(layout.content());
            for (var widget : widgets) {
                if (key(widget).endsWith(".save") || key(widget).endsWith(".cancel")) {
                    assertEquals(footer.primary().y(), widget.getY());
                    assertEquals(footer.primary().width(), widget.getWidth());
                    assertEquals(
                            key(widget).endsWith(".save")
                                    ? footer.primary().x()
                                    : footer.secondary().x(),
                            widget.getX());
                } else {
                    assertTrue(widget.getBottom() <= footer.content().bottom(), "Scrollable content overlaps actions");
                }
            }
            var field = (TerminalEditBox) widgets.stream()
                    .filter(widget -> key(widget).endsWith(".selector_value"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("minecraft:old_typo", field.getValue());
            field.setValue("minecraft:stone");
            assertTrue(view.resourceDirty());
            ((TerminalButton) widgets.stream()
                            .filter(widget -> key(widget).endsWith(".save"))
                            .findFirst()
                            .orElseThrow())
                    .onPress();
            var save = (NetworkTerminalRequest.SaveResourceRule)
                    NetworkSetupScreen.resourceRuleRequest(actions.getLast(), PRESET, RULE, 3);
            assertTrue(
                    TerminalInteractionPolicy.submitsDraft(save), "Typed save must share the in-flight close policy");
            assertFalse(
                    TerminalInteractionPolicy.submitsDraft(
                            new NetworkTerminalRequest.PrepareResourceRuleUpload(PRESET, RULE, 4, RULE, 262061)),
                    "Upload preparation must not claim the draft was submitted");
            var match = (io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match) save.intent();
            assertEquals(
                    ResourceLocation.parse("minecraft:stone"),
                    ((ResourceFilterRule.Exact) match.selector()).resourceId());
            for (AbstractWidget widget : widgets) {
                assertTrue(widget.getX() >= layout.content().x()
                        && widget.getRight() <= layout.content().right());
                assertTrue(widget.getY() >= layout.content().y()
                        && widget.getBottom() <= layout.content().bottom());
            }
        }
    }

    @Test
    void searchIsCollapsedAndOneRequestInFlightWithStaleQueryRejection() {
        TerminalFilterView view = new TerminalFilterView(() -> {});
        view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0)));
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var widgets = build(view, layout, STATE, actions);
        assertFalse(view.searchExpanded());
        ((TerminalSearchButton) widgets.stream()
                        .filter(TerminalSearchButton.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        widgets = build(view, layout, STATE, actions);
        var field = (TerminalSearchBox) widgets.stream()
                .filter(TerminalSearchBox.class::isInstance)
                .findFirst()
                .orElseThrow();
        field.setValue("first");
        view.tick(2, false);
        view.tick(3, false);
        assertEquals(1, actions.size());
        field.setValue("second");
        view.tick(4, false);
        assertEquals(1, actions.size());
        view.acceptLibrary(new NetworkTerminalResponse.FilterLibrary(
                PRESET, RULE, 1, "first", new FilterPresetPage(List.of(), 0, 0, 0)));
        view.tick(5, false);
        assertEquals(2, actions.size());
        assertEquals("second", ((TerminalFilterView.Action.Query) actions.getLast()).query());
        assertTrue(view.closeSearch());
        assertFalse(view.searchExpanded());
        assertTrue(view.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0));
        assertFalse(view.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0));
    }

    @Test
    void finalUploadFragmentWaitsForDiscardDecisionAndNeverRunsAfterClose() {
        var ready = new NetworkTerminalResponse.RuleTransferReady(
                PRESET, RULE, 1, new UUID(9, 9), 262061, true, null, 0, 0);
        byte[] bytes = new byte[ready.length()];
        var sent = new ArrayList<io.github.loongin.omniresonance.networking.ManagementTransferMessage>();
        boolean[] submitted = {false};
        assertEquals(
                TerminalInteractionPolicy.BackAction.CONFIRM_DRAFT,
                TerminalInteractionPolicy.shortcutAction(false, true));
        var paused = NetworkSetupScreen.advanceFilterUpload(
                ready, bytes, bytes.length - 1, true, false, sent::add, () -> submitted[0] = true);
        assertEquals(bytes.length - 1, paused.offset());
        assertFalse(paused.finished());
        assertTrue(sent.isEmpty());
        assertFalse(submitted[0]);
        var closed = NetworkSetupScreen.advanceFilterUpload(
                ready, bytes, paused.offset(), false, true, sent::add, () -> submitted[0] = true);
        assertEquals(paused.offset(), closed.offset());
        assertTrue(sent.isEmpty());
        assertFalse(submitted[0]);
        var continued = NetworkSetupScreen.advanceFilterUpload(
                ready,
                bytes,
                paused.offset(),
                false,
                false,
                message -> {
                    if (message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish)
                        assertTrue(submitted[0], "Finish must atomically change the close policy before dispatch");
                    sent.add(message);
                },
                () -> submitted[0] = true);
        assertTrue(continued.finished());
        assertEquals(bytes.length, continued.offset());
        assertEquals(2, sent.size());
        assertTrue(
                sent.getFirst() instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk);
        assertTrue(
                sent.getLast() instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish);
        assertEquals(
                TerminalInteractionPolicy.BackAction.CLOSE_SCREEN,
                TerminalInteractionPolicy.shortcutAction(submitted[0], true));
    }
}
