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
    void typedPrefixesRebuildTheRealFormWithoutLosingTextFocusAndSubmitTypedRules() {
        var layout = TerminalLayout.calculate(960, 540);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        var actions = new ArrayList<TerminalFilterView.Action>();
        var viewRef = new java.util.concurrent.atomic.AtomicReference<TerminalFilterView>();
        var widgetsRef = new java.util.concurrent.atomic.AtomicReference<List<AbstractWidget>>();
        var view = new TerminalFilterView(() -> widgetsRef.set(build(viewRef.get(), layout, edit, actions)));
        viewRef.set(view);
        view.apply(STATE);
        view.apply(edit);
        widgetsRef.set(build(view, layout, edit, actions));
        TerminalTagClipboard.copy("minecraft:fluid", List.of("c:water"), ignored -> {});
        var copiedField = (TerminalEditBox) widgetsRef.get().stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        copiedField.setFocused(true);
        copiedField.setValue("#water");
        var restoredField = (TerminalEditBox) widgetsRef.get().stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        assertTrue(restoredField.isFocused());
        assertEquals("#c:water", restoredField.getValue());
        assertEquals(8, restoredField.getCursorPosition());
        TerminalTagClipboard.clear();
        for (String value : List.of("fluid:water", "gas:mekanism:hydrogen", "fluid:#minecraft:water")) {
            var field = (TerminalEditBox) widgetsRef.get().stream()
                    .filter(w -> key(w).endsWith(".selector_value"))
                    .findFirst()
                    .orElseThrow();
            field.setFocused(true);
            field.setValue(value);
            var current = (TerminalEditBox) widgetsRef.get().stream()
                    .filter(w -> key(w).endsWith(".selector_value"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(current.isFocused());
            assertTrue(current.active);
            assertEquals(value, current.getValue());
            assertEquals(value.length(), current.getCursorPosition());
        }
        ((TerminalButton) widgetsRef.get().stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        var intent = ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent();
        assertEquals(TerminalRuleInput.explicit("fluid:#minecraft:water"), intent);
    }

    @Test
    void typedClipboardCreatesOnlyAnUnsavedRuleThroughThePresetPage() {
        var view = new TerminalFilterView(() -> {});
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        build(view, layout, STATE, actions);
        assertTrue(view.pasteTag(null, "energy"));
        assertEquals(1, actions.size());
        assertTrue(actions.getFirst() instanceof TerminalFilterView.Action.BeginFull);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        assertEquals(
                TerminalRuleInput.explicit("energy"),
                ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent());
    }

    @Test
    void sourcePresetUsesWholeTypeWithoutTagOrComponentInputs() {
        var view = new TerminalFilterView(() -> {});
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        build(view, layout, STATE, actions);
        assertTrue(view.pasteTag(null, "source"));
        assertEquals(1, actions.size());
        assertTrue(actions.getFirst() instanceof TerminalFilterView.Action.BeginFull);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        assertFalse(widgets.stream().anyMatch(w -> key(w).endsWith(".selector_value")));
        assertFalse(widgets.stream().anyMatch(w -> key(w).endsWith(".components_id_only")));
        assertTrue(widgets.stream().filter(w -> key(w).endsWith(".selector_3")).noneMatch(w -> w.active));
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        assertEquals(
                TerminalRuleInput.explicit("source"),
                ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent());
    }

    @Test
    void soulPresetUsesWholeTypeWithoutTagOrComponentInputs() {
        var view = new TerminalFilterView(() -> {});
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        build(view, layout, STATE, actions);
        assertTrue(view.pasteTag(null, "soul"));
        assertEquals(1, actions.size());
        assertTrue(actions.getFirst() instanceof TerminalFilterView.Action.BeginFull);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        assertFalse(widgets.stream().anyMatch(w -> key(w).endsWith(".selector_value")));
        assertFalse(widgets.stream().anyMatch(w -> key(w).endsWith(".components_id_only")));
        assertTrue(widgets.stream().filter(w -> key(w).endsWith(".selector_3")).noneMatch(w -> w.active));
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        assertEquals(
                TerminalRuleInput.explicit("soul"), ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent());
    }

    @Test
    void energyGhostDropCreatesAWholeEnergyRule() {
        var view = new TerminalFilterView(() -> {});
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        build(view, layout, STATE, actions);
        assertTrue(view.acceptGhost(
                view.ghostTarget(),
                RecipeGhostTarget.ingredient(io.github.loongin.omniresonance.transfer.EnergyVariant.INSTANCE)));
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        assertFalse(widgets.stream().anyMatch(w -> key(w).endsWith(".selector_value")));
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        var intent = (io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match)
                ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent();
        assertEquals(ResourceTypes.ENERGY, intent.typeId());
        assertEquals(ResourceFilterRule.Selector.wholeType(), intent.selector());
    }

    @Test
    void pastingShortRecipeSearchIntoTheEditorRestoresTheTypedFullTag() {
        TerminalTagClipboard.clear();
        try {
            TerminalTagClipboard.copy("minecraft:fluid", List.of("c:water"), ignored -> {});
            var view = new TerminalFilterView(() -> {});
            view.apply(STATE);
            var edit = new NetworkTerminalState.PresetEdit(
                    NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
            view.apply(edit);
            var actions = new ArrayList<TerminalFilterView.Action>();
            var layout = TerminalLayout.terminal(640, 360);
            var widgets = build(view, layout, edit, actions);
            var field = (TerminalEditBox) widgets.stream()
                    .filter(w -> key(w).endsWith(".selector_value"))
                    .findFirst()
                    .orElseThrow();
            field.setValue(TerminalTagClipboard.recent().text());
            widgets = build(view, layout, edit, actions);
            field = (TerminalEditBox) widgets.stream()
                    .filter(w -> key(w).endsWith(".selector_value"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("#c:water", field.getValue());
            assertTrue(widgets.stream().anyMatch(w -> key(w).endsWith(".resource_fluid")));
            assertTrue(widgets.stream().anyMatch(w -> key(w).endsWith(".selector_1")));
            assertTrue(actions.isEmpty());
            ((TerminalButton) widgets.stream()
                            .filter(w -> key(w).endsWith(".save"))
                            .findFirst()
                            .orElseThrow())
                    .onPress();
            var intent = (io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match)
                    ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent();
            assertEquals(ResourceTypes.FLUID, intent.typeId());
            assertEquals(ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:water")), intent.selector());
        } finally {
            TerminalTagClipboard.clear();
        }
    }

    @Test
    void proportionalSmallEditorScrollsToSamplingWithoutMovingTheFooter() {
        var view = new TerminalFilterView(() -> {});
        view.apply(STATE);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var layout = TerminalLayout.terminal(320, 240);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var widgets = build(view, layout, edit, actions);
        var footer = TerminalActionLayout.of(layout.content());
        for (int index = 0; index < 2; index++) {
            assertTrue(view.scroll(layout.content().x() + 12, layout.content().y() + 12, -1));
            widgets = build(view, layout, edit, actions);
        }
        var field = (TerminalEditBox) widgets.stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        field.setValue("minecraft:diamond");
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".components_id_only"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        widgets = build(view, layout, edit, actions);
        assertTrue(view.scroll(layout.content().x() + 12, layout.content().y() + 12, -1));
        widgets = build(view, layout, edit, actions);
        ((TerminalSampleSlot) widgets.stream()
                        .filter(TerminalSampleSlot.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        assertTrue(view.closeLocalLayer());
        assertTrue(view.resourceDirty());
        assertTrue(actions.isEmpty());
        widgets = build(view, layout, edit, actions);
        for (var widget : widgets) {
            assertTrue(widget.getY() >= layout.content().y()
                    && widget.getBottom() <= layout.content().bottom());
            if (key(widget).endsWith(".save") || key(widget).endsWith(".cancel"))
                assertEquals(footer.primary().y(), widget.getY());
            else assertTrue(widget.getBottom() <= footer.content().bottom());
        }
    }

    @Test
    void referencePickerUsesTheCompleteLocalCatalogAtBothScrollBoundaries() {
        for (boolean searched : new boolean[] {false, true}) {
            var view = new TerminalFilterView(() -> {});
            var entries = new ArrayList<FilterPresetSummary>();
            for (int i = 0; i < 260; i++)
                entries.add(new FilterPresetSummary(i == 0 ? PRESET : new UUID(90, i), "Candidate " + i, 0, 0, true));
            var directory =
                    new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(entries.subList(0, 128), 0, 260, 1));
            view.apply(directory);
            var actions = new ArrayList<TerminalFilterView.Action>();
            var layout = TerminalLayout.terminal(640, 360);
            build(view, layout, directory, actions);
            for (int offset : new int[] {128, 256}) {
                view.tick(offset, false);
                view.acceptLibrary(new NetworkTerminalResponse.FilterLibrary(
                        NETWORK.id(),
                        NETWORK.ownerId(),
                        offset,
                        "",
                        new FilterPresetPage(entries.subList(offset, Math.min(260, offset + 128)), offset, 260, 1)));
            }
            view.apply(STATE);
            var edit = new NetworkTerminalState.PresetEdit(
                    NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
            view.apply(edit);
            for (int i = 0; i < 4; i++) {
                var widgets = build(view, layout, edit, actions);
                ((TerminalButton) widgets.stream()
                                .filter(w -> key(w).contains(".selector_") && w instanceof TerminalButton)
                                .findFirst()
                                .orElseThrow())
                        .onPress();
            }
            var widgets = build(view, layout, edit, actions);
            if (searched) {
                ((TerminalSearchButton) widgets.stream()
                                .filter(TerminalSearchButton.class::isInstance)
                                .findFirst()
                                .orElseThrow())
                        .onPress();
                widgets = build(view, layout, edit, actions);
                ((TerminalSearchBox) widgets.stream()
                                .filter(TerminalSearchBox.class::isInstance)
                                .findFirst()
                                .orElseThrow())
                        .setValue("Candidate");
                view.tick(300, false);
                widgets = build(view, layout, edit, actions);
            }
            int requests = actions.size();
            for (int i = 0; i < 300; i++) {
                var row = widgets.stream()
                        .filter(TerminalRowButton.class::isInstance)
                        .findFirst()
                        .orElseThrow();
                view.scroll(row.getX() + 1, row.getY() + 1, -1);
                widgets = build(view, layout, edit, actions);
            }
            assertEquals(requests, actions.size(), "Local reference scrolling must not request another server page");
            assertTrue(widgets.stream().anyMatch(w -> w.getMessage().getString().equals("Candidate 259")));
            for (int i = 0; i < 300; i++) {
                var row = widgets.stream()
                        .filter(TerminalRowButton.class::isInstance)
                        .findFirst()
                        .orElseThrow();
                view.scroll(row.getX() + 1, row.getY() + 1, 1);
                widgets = build(view, layout, edit, actions);
            }
            assertEquals(requests, actions.size());
            assertTrue(widgets.stream().anyMatch(w -> w.getMessage().getString().equals("Candidate 0")));
        }
    }

    @Test
    void sampleSelectionIsLocalAndReferenceSelectionPreservesTheDraft() {
        var view = new TerminalFilterView(() -> {});
        view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0)));
        view.apply(STATE);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.terminal(640, 360);
        var widgets = build(view, layout, edit, actions);
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".components_id_only"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        widgets = build(view, layout, edit, actions);
        assertFalse(widgets.stream().anyMatch(w -> key(w).endsWith(".inventory_slot") || key(w).endsWith(".tank")));
        var sample = (TerminalSampleSlot) widgets.stream()
                .filter(TerminalSampleSlot.class::isInstance)
                .findFirst()
                .orElseThrow();
        sample.onPress();
        assertTrue(actions.isEmpty());
        assertTrue(view.closeLocalLayer());
        assertTrue(view.resourceDirty());
        for (int n = 0; n < 4; n++) {
            widgets = build(view, layout, edit, actions);
            ((TerminalButton) widgets.stream()
                            .filter(w -> key(w).contains(".selector_") && w instanceof TerminalButton)
                            .findFirst()
                            .orElseThrow())
                    .onPress();
        }
        widgets = build(view, layout, edit, actions);
        assertTrue(widgets.stream().anyMatch(TerminalSearchButton.class::isInstance));
        ((TerminalRowButton) widgets.stream()
                        .filter(TerminalRowButton.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        widgets = build(view, layout, edit, actions);
        assertFalse(widgets.stream().anyMatch(TerminalSearchButton.class::isInstance));
        assertTrue(widgets.stream().anyMatch(w -> w.getMessage().getString().equals(SUMMARY.name())));
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        var intent = (io.github.loongin.omniresonance.filter.ResourceRuleIntent.Reference)
                ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent();
        assertEquals(PRESET, intent.presetId());
    }

    @Test
    void editorUsesOnePaneAndHidesNumericSamplingFields() {
        var view = new TerminalFilterView(() -> {});
        view.apply(STATE);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, TerminalLayout.calculate(320, 240), edit, new ArrayList<>());
        assertFalse(widgets.stream().anyMatch(w -> key(w).endsWith(".inventory_slot") || key(w).endsWith(".tank")));
        assertFalse(widgets.stream().anyMatch(TerminalSampleSlot.class::isInstance));
        assertFalse(widgets.stream().anyMatch(TerminalRowButton.class::isInstance));
        var field = widgets.stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        assertTrue(field.getWidth() >= 240);
    }

    @Test
    void ghostPreviewRejectsReadonlyAndFailedAdmissionDiscardsPrefill() {
        var view = new TerminalFilterView(() -> {});
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var readonly = new NetworkTerminalState.Preset(
                NETWORK, new FilterPresetSummary(PRESET, "Preset", 0, 1, false), STATE.rules());
        view.apply(readonly);
        build(view, layout, readonly, actions);
        assertEquals(null, view.ghostTarget());
        view.apply(STATE);
        build(view, layout, STATE, actions);
        assertTrue(view.acceptGhost(
                view.ghostTarget(),
                new RecipeGhostTarget.Ingredient(ResourceTypes.FLUID, ResourceLocation.parse("minecraft:water"))));
        view.requestFailed(null);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        var field = (TerminalEditBox) widgets.stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        assertEquals("minecraft:stone", field.getValue());
        assertFalse(view.resourceDirty());
    }

    @Test
    void ghostDropRequiresCurrentEditablePresetAndOnlyCreatesADraft() {
        var view = new TerminalFilterView(() -> {});
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        build(view, layout, STATE, actions);
        var target = view.ghostTarget();
        assertTrue(target != null);
        var ingredient =
                new RecipeGhostTarget.Ingredient(ResourceTypes.ITEM, ResourceLocation.parse("minecraft:iron_ingot"));
        assertTrue(view.acceptGhost(target, ingredient));
        assertTrue(actions.getLast() instanceof TerminalFilterView.Action.BeginFull);
        assertFalse(view.acceptGhost(target, ingredient));
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        assertTrue(view.resourceDirty());
        assertEquals(null, view.ghostTarget());
        var field = (TerminalEditBox) widgets.stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        assertEquals("minecraft:iron_ingot", field.getValue());
        assertEquals(1, actions.size());
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        var intent = (io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match)
                ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent();
        assertEquals(ResourceFilterRule.Selector.exact(ingredient.id()), intent.selector());
        assertEquals(ComponentCondition.Mode.ID_ONLY, intent.mode());
        view.apply(STATE);
        build(view, layout, STATE, actions);
        assertFalse(
                view.acceptGhost(new RecipeGhostTarget.Target(UUID.randomUUID(), PRESET, target.area()), ingredient));
    }

    @Test
    void librarySearchSharesTheResultRowWidthAtBothWindowSizes() {
        for (int width : new int[] {427, 960}) {
            var state = new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0));
            var view = new TerminalFilterView(() -> {});
            view.apply(state);
            var actions = new ArrayList<TerminalFilterView.Action>();
            var layout = TerminalLayout.calculate(width, 240);
            var widgets = build(view, layout, state, actions);
            ((TerminalSearchButton) widgets.stream()
                            .filter(TerminalSearchButton.class::isInstance)
                            .findFirst()
                            .orElseThrow())
                    .onPress();
            widgets = build(view, layout, state, actions);
            var field = widgets.stream()
                    .filter(TerminalSearchBox.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            var row = widgets.stream()
                    .filter(TerminalRowButton.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            assertEquals(row.getX(), field.getX());
            assertEquals(row.getWidth(), field.getWidth());
        }
    }

    @Test
    void prefixedTagInputSelectsTagModeWithoutAnExtraClick() {
        TerminalTagClipboard.clear();
        try {
            TerminalTagClipboard.copy("minecraft:item", List.of("c:ingots"), value -> {});
            var view = new TerminalFilterView(() -> {});
            view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0)));
            view.apply(STATE);
            var edit = new NetworkTerminalState.PresetEdit(
                    NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
            view.apply(edit);
            var actions = new ArrayList<TerminalFilterView.Action>();
            var widgets = build(view, TerminalLayout.calculate(960, 540), edit, actions);
            var field = (TerminalEditBox) widgets.stream()
                    .filter(w -> key(w).endsWith(".selector_value"))
                    .findFirst()
                    .orElseThrow();
            field.setValue("#c:ingots");
            assertTrue(widgets.stream().anyMatch(w -> key(w).endsWith(".selector_1")));
            ((TerminalButton) widgets.stream()
                            .filter(w -> key(w).endsWith(".save"))
                            .findFirst()
                            .orElseThrow())
                    .onPress();
            var intent = (io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match)
                    ((TerminalFilterView.Action.SaveFull) actions.getLast()).intent();
            assertEquals(ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:ingots")), intent.selector());
        } finally {
            TerminalTagClipboard.clear();
        }
    }

    @Test
    void tagPasteRequiresAnEditablePresetAndDoesNotGuessTypesFromUnrelatedClipboardText() {
        var view = new TerminalFilterView(() -> {});
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var candidate = new TerminalTagClipboard.Candidate("minecraft:item", List.of("c:ingots"), "#ingots");
        assertFalse(view.pasteTag(candidate, "#ingots"));
        var readonly = new NetworkTerminalState.Preset(
                NETWORK, new FilterPresetSummary(PRESET, "Preset", 0, 1, false), STATE.rules());
        view.apply(readonly);
        build(view, layout, readonly, actions);
        assertFalse(view.pasteTag(candidate, "#ingots"));
        assertTrue(actions.isEmpty());
        view.apply(STATE);
        build(view, layout, STATE, actions);
        assertTrue(view.pasteTag(candidate, "#water"));
        assertTrue(actions.isEmpty());
        assertFalse(view.resourceDirty());
    }

    @Test
    void pastedTagOpensAnIndependentDirtyDraftAndUsesTheExistingSavePayload() {
        var view = new TerminalFilterView(() -> {});
        view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0)));
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        build(view, layout, STATE, actions);
        var candidate = new TerminalTagClipboard.Candidate("minecraft:fluid", List.of("c:water"), "#water");
        assertTrue(view.pasteTag(candidate, "#water"));
        assertFalse(view.pasteTag(candidate, "#water"));
        var request = (NetworkTerminalRequest.BeginResourceRule)
                NetworkSetupScreen.resourceRuleRequest(actions.getLast(), PRESET, RULE, 1);
        assertEquals(PRESET, request.presetId());
        assertEquals(null, request.ruleId());
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        var field = (TerminalEditBox) widgets.stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        assertEquals("#c:water", field.getValue());
        assertTrue(view.resourceDirty());
        ((TerminalButton) widgets.stream()
                        .filter(w -> key(w).endsWith(".save"))
                        .findFirst()
                        .orElseThrow())
                .onPress();
        var save = (NetworkTerminalRequest.SaveResourceRule)
                NetworkSetupScreen.resourceRuleRequest(actions.getLast(), PRESET, RULE, 2);
        var intent = (io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match) save.intent();
        assertEquals(ResourceTypes.FLUID, intent.typeId());
        assertEquals(ResourceFilterRule.Selector.tag(ResourceLocation.parse("c:water")), intent.selector());
        assertEquals(ComponentCondition.Mode.ID_ONLY, intent.mode());
    }

    @Test
    void failedPasteAdmissionCannotPopulateTheNextManuallyCreatedRule() {
        var view = new TerminalFilterView(() -> {});
        view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0)));
        view.apply(STATE);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        build(view, layout, STATE, actions);
        assertTrue(view.pasteTag(
                new TerminalTagClipboard.Candidate("minecraft:fluid", List.of("c:water"), "#water"), "#water"));
        view.requestFailed(null);
        var edit = new NetworkTerminalState.PresetEdit(
                NETWORK, SUMMARY, PresetEditOperation.ADD_RULE, new FilterImpactSummary(0, 0, 0, true));
        view.apply(edit);
        var widgets = build(view, layout, edit, actions);
        var field = (TerminalEditBox) widgets.stream()
                .filter(w -> key(w).endsWith(".selector_value"))
                .findFirst()
                .orElseThrow();
        assertEquals("minecraft:stone", field.getValue());
        assertFalse(view.resourceDirty());
    }

    @Test
    void layeredControlsReadAndEditPinnedRuleWithoutDisplayingUuid() {
        for (int[] size : new int[][] {{320, 240}, {640, 360}, {960, 540}}) {
            TerminalFilterView view = new TerminalFilterView(() -> {});
            view.apply(new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0)));
            view.apply(STATE);
            var actions = new ArrayList<TerminalFilterView.Action>();
            var layout = TerminalLayout.calculate(size[0], size[1]);
            var widgets = build(view, layout, STATE, actions);
            assertEquals(
                    0,
                    widgets.stream()
                            .filter(TerminalSearchButton.class::isInstance)
                            .count());
            assertFalse(widgets.stream().anyMatch(widget -> key(widget).endsWith(".save")));
            var rows =
                    widgets.stream().filter(TerminalRowButton.class::isInstance).toList();
            assertEquals(1, rows.size());
            ((TerminalRowButton) rows.getFirst()).onPress();
            var read = (NetworkTerminalRequest.ReadResourceRule)
                    NetworkSetupScreen.resourceRuleRequest(actions.getLast(), PRESET, RULE, 1);
            assertEquals(RULE, read.ruleId());
            assertFalse(rows.getFirst().getMessage().getString().contains(RULE.toString()));
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
    void searchUsesCompleteCatalogLocallyAndKeepsCaretAcrossRebuilds() {
        var view = new TerminalFilterView(() -> {});
        var directory = new NetworkTerminalState.Filters(NETWORK, new FilterPresetPage(List.of(SUMMARY), 0, 1, 0));
        view.apply(directory);
        var actions = new ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var widgets = build(view, layout, directory, actions);
        assertFalse(view.searchExpanded());
        ((TerminalSearchButton) widgets.stream()
                        .filter(TerminalSearchButton.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        widgets = build(view, layout, directory, actions);
        var field = (TerminalSearchBox) widgets.stream()
                .filter(TerminalSearchBox.class::isInstance)
                .findFirst()
                .orElseThrow();
        field.setValue("first");
        field.setFocused(true);
        field.setCursorPosition(3);
        field.setHighlightPos(1);
        view.tick(2, false);
        widgets = build(view, layout, directory, actions);
        org.junit.jupiter.api.Assertions.assertSame(
                field,
                widgets.stream()
                        .filter(TerminalSearchBox.class::isInstance)
                        .findFirst()
                        .orElseThrow());
        assertEquals(3, field.getCursorPosition());
        assertEquals("ir", field.getHighlighted());
        assertTrue(actions.isEmpty());
        try {
            ClientTextSearch.install((name, query) -> query.equals("pinyin"));
            field.setValue("pinyin");
            view.tick(3, false);
            var matched = build(view, layout, directory, actions);
            assertEquals(
                    1,
                    matched.stream().filter(TerminalRowButton.class::isInstance).count());
            assertTrue(actions.isEmpty(), "Local matching must not send a search query");
        } finally {
            ClientTextSearch.usePlain();
        }
        assertTrue(view.closeSearch());
        assertFalse(view.searchExpanded());
        assertTrue(view.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0));
        assertTrue(view.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0));
        assertFalse(view.searchExpanded());
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
