// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalFilterViewTest {
    @Test
    void ruleEditorUsesPinnedUntruncatedSourceAndPreservesCorrectableDraft() {
        var view = new TerminalFilterView(() -> {});
        var original = preset(true, 4);
        String source = "a:" + "b".repeat(65533);
        var edit = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(),
                original.preset(),
                io.github.loongin.omniresonance.filter.PresetEditOperation.EDIT_RULE,
                new io.github.loongin.omniresonance.networking.FilterImpactSummary(1, 2, 3, true),
                source);
        view.apply(original);
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        ((TerminalRowButton) build(view, layout, original, actions).getFirst()).onPress();
        assertEquals(source, view.initialValue(edit), "Local selected row must not overwrite the pinned server source");
        var font =
                new net.minecraft.client.gui.Font(
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
        var widgets = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        var field =
                view.build(font, layout, edit, view.initialValue(edit), false, widgets::add, value -> {}, actions::add);
        assertEquals(source, field.getValue());
        field.setValue(source + "b");
        assertEquals(source, field.getValue());
        assertEquals(
                "omniresonance.terminal.filters.item_id",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                                field.getMessage().getContents())
                        .getKey());
        ((TerminalButton) widgets.getLast()).onPress();
        assertEquals(new TerminalFilterView.Action.Save(), actions.getLast());
        var request = (io.github.loongin.omniresonance.networking.NetworkTerminalRequest.SavePresetEdit)
                NetworkSetupScreen.presetEditRequest(
                        actions.getLast(), network().id(), network().ownerId(), 2, field.getValue());
        assertEquals(source, request.value());
        var refreshed = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(),
                original.preset(),
                edit.operation(),
                new io.github.loongin.omniresonance.networking.FilterImpactSummary(2, 3, 4, true),
                source);
        var draft = NetworkSetupScreen.resolveFailedTopologyDraft(view, edit, refreshed, "minecraft:iron_ingot", true);
        assertEquals("minecraft:iron_ingot", draft.value());
        assertTrue(draft.dirty());
        assertTrue(TerminalInteractionPolicy.sameEditor(edit, refreshed));
        var other = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(), original.preset(), edit.operation(), edit.impact(), "minecraft:stone");
        org.junit.jupiter.api.Assertions.assertFalse(TerminalInteractionPolicy.sameEditor(edit, other));
    }

    @Test
    void selectedRuleOffersExplicitItemIdEditAlongsideExistingActions() {
        var view = new TerminalFilterView(() -> {});
        var state = preset(true, 4);
        view.apply(state);
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var widgets = build(view, layout, state, actions);
        var edit = widgets.stream()
                .filter(widget -> widget.getMessage().getContents()
                                instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("omniresonance.terminal.filters.edit_rule"))
                .findFirst();
        assertTrue(edit.isPresent(), "Rule editing must be wired into the production rule page");
        org.junit.jupiter.api.Assertions.assertFalse(edit.orElseThrow().active);
        ((TerminalRowButton) widgets.stream()
                        .filter(TerminalRowButton.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        widgets = build(view, layout, state, actions);
        var selectedEdit = (TerminalButton) widgets.stream()
                .filter(widget -> widget.getMessage().getContents()
                                instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("omniresonance.terminal.filters.edit_rule"))
                .findFirst()
                .orElseThrow();
        assertTrue(selectedEdit.active);
        selectedEdit.onPress();
        assertEquals(
                "EDIT_RULE",
                ((TerminalFilterView.Action.Begin) actions.getLast())
                        .operation()
                        .name());
        assertEquals("minecraft:stone", ((TerminalFilterView.Action.Begin) actions.getLast()).originalRule());
        var request = (io.github.loongin.omniresonance.networking.NetworkTerminalRequest.BeginPresetEdit)
                NetworkSetupScreen.presetEditRequest(
                        actions.getLast(), network().id(), network().ownerId(), 1, "ignored");
        assertEquals("minecraft:stone", request.originalRule());
        assertEquals(state.preset().id(), request.presetId());
        assertEquals(io.github.loongin.omniresonance.filter.PresetEditOperation.EDIT_RULE, request.operation());
        view.apply(preset(false, 4));
        widgets = build(view, layout, preset(false, 4), actions);
        ((TerminalRowButton) widgets.stream()
                        .filter(TerminalRowButton.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        widgets = build(view, layout, preset(false, 4), actions);
        for (var widget : widgets) {
            if (widget instanceof TerminalButton
                    && !(widget instanceof TerminalRowButton)
                    && widget.getMessage().getContents()
                            instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && text.getKey().equals("omniresonance.terminal.filters.edit_rule"))
                org.junit.jupiter.api.Assertions.assertFalse(widget.active);
        }
    }

    @Test
    void correctablePresetFailurePreservesReadableDirtyDraftAndLeavingEditorClearsIt() {
        var original = preset(true, 4);
        var edit = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(),
                original.preset(),
                io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
                new io.github.loongin.omniresonance.networking.FilterImpactSummary(1, 2, 3, true));
        var refreshed = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(),
                original.preset(),
                edit.operation(),
                new io.github.loongin.omniresonance.networking.FilterImpactSummary(4, 5, 6, true));
        var view = new TerminalFilterView(() -> {});
        var draft = NetworkSetupScreen.resolveFailedTopologyDraft(view, edit, refreshed, "Changed preset", true);
        assertEquals("Changed preset", draft.value());
        assertTrue(draft.dirty());
        assertEquals(
                TerminalInteractionPolicy.BackAction.CONFIRM_DRAFT,
                TerminalInteractionPolicy.shortcutAction(false, draft.dirty()));
        var departed =
                NetworkSetupScreen.resolveFailedTopologyDraft(view, edit, original, draft.value(), draft.dirty());
        assertEquals("", departed.value());
        org.junit.jupiter.api.Assertions.assertFalse(departed.dirty());
    }

    @Test
    void authoritativePresetSaveCancelCopyAndDeleteDestinationsClearDraftBeforeInventoryClose() {
        var original = preset(true, 4);
        var edit = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(),
                original.preset(),
                io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
                new io.github.loongin.omniresonance.networking.FilterImpactSummary(1, 2, 3, true));
        for (var destination : java.util.List.of(
                preset(true, 5),
                original,
                new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(network(), presets(0)))) {
            var draft = NetworkSetupScreen.resolveTopologyDraft(
                    new TerminalFilterView(() -> {}), edit, destination, "Changed preset", true);
            assertEquals("", draft.value());
            org.junit.jupiter.api.Assertions.assertFalse(draft.dirty());
            assertEquals(
                    TerminalInteractionPolicy.BackAction.CLOSE_SCREEN,
                    TerminalInteractionPolicy.shortcutAction(false, draft.dirty()));
        }
    }

    @Test
    void presetDetailUsesSharedHeaderSettingsAction() {
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Preset(
                network(),
                new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                        new java.util.UUID(3, 3), "Preset", 4, 0, true),
                new io.github.loongin.omniresonance.networking.FilterRulePage(java.util.List.of(), 0, 0, 0));
        assertEquals(TerminalHeaderLayout.Action.SETTINGS, TerminalInteractionPolicy.topBarAction(true, state));
    }

    @Test
    void filtersUseSharedHeaderCreateAction() {
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(network(), presets(0));
        assertEquals(TerminalHeaderLayout.Action.CREATE, TerminalInteractionPolicy.topBarAction(true, state));
    }

    @Test
    void screenSharedCreateButtonDispatchesPresetCreateAndRetainsNetworkAndTunnelRoutes() {
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(network(), presets(0));
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var calls = new java.util.ArrayList<String>();
        for (int[] size : new int[][] {{320, 240}, {427, 240}}) {
            var layout = TerminalLayout.calculate(size[0], size[1]);
            var header = TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(layout.window()), true);
            var button = NetworkSetupScreen.buildCreateButton(
                    header.action(),
                    TerminalInteractionPolicy.createTarget(true, state),
                    () -> calls.add("network"),
                    () -> calls.add("tunnel"),
                    () -> calls.add("administrator"),
                    actions::add);
            assertEquals(TerminalIconButton.class, button.getClass());
            assertEquals(20, button.getWidth());
            assertEquals(header.action().right(), button.getRight());
            assertTrue(header.remaining().right() + TerminalLayout.GAP <= button.getX());
            assertEquals(
                    "omniresonance.terminal.filters.create",
                    ((net.minecraft.network.chat.contents.TranslatableContents)
                                    button.getMessage().getContents())
                            .getKey());
            button.onPress();
            assertEquals(
                    new TerminalFilterView.Action.Begin(
                            io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE, null),
                    actions.getLast());
            assertTrue(calls.isEmpty());
        }
        for (var target : java.util.List.of(
                TerminalInteractionPolicy.CreateTarget.NETWORK, TerminalInteractionPolicy.CreateTarget.TUNNEL)) {
            NetworkSetupScreen.buildCreateButton(
                            new TerminalLayout.Rect(0, 0, 20, 20),
                            target,
                            () -> calls.add("network"),
                            () -> calls.add("tunnel"),
                            () -> calls.add("administrator"),
                            actions::add)
                    .onPress();
        }
        assertEquals(java.util.List.of("network", "tunnel"), calls);
    }

    @Test
    void catalogSendFailureOffersExplicitRetryWithoutARequestLoop() {
        var view = new TerminalFilterView(() -> {});
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(network(), presets(0));
        var layout = TerminalLayout.calculate(960, 540);
        view.apply(state);
        build(view, layout, state, actions);
        view.tick(1, false);
        assertEquals(1, actions.size());
        org.junit.jupiter.api.Assertions.assertFalse(NetworkSetupScreen.completeTopologySend(view, false));
        view.tick(2, false);
        assertEquals(1, actions.size());
        var retry = (TerminalButton) build(view, layout, state, actions).stream()
                .filter(w -> w.getMessage().getContents()
                                instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        && t.getKey().equals("omniresonance.terminal.retry"))
                .findFirst()
                .orElseThrow();
        retry.onPress();
        view.tick(3, false);
        assertEquals(2, actions.size());
        assertEquals(0, ((TerminalFilterView.Action.Query) actions.getLast()).offset());
    }

    @Test
    void screenFailureHookReleasesWheelRequestWithoutDiscardingCurrentRules() {
        var view = new TerminalFilterView(() -> {});
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var summary = new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                new java.util.UUID(3, 3), "Preset", 4, 10, true);
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Preset(
                network(),
                summary,
                new io.github.loongin.omniresonance.networking.FilterRulePage(
                        java.util.List.of("minecraft:stone"), 6, 10, 3));
        view.apply(state);
        build(view, layout, state, actions);
        view.scroll(layout.content().x() + 10, layout.content().y() + 30, 1);
        assertEquals(1, actions.size());
        NetworkSetupScreen.applyFilterResponse(
                view,
                new io.github.loongin.omniresonance.networking.NetworkTerminalResponse.Failure(
                        new java.util.UUID(7, 1),
                        new java.util.UUID(7, 2),
                        1,
                        io.github.loongin.omniresonance.networking.NetworkTerminalResponse.Reason.STALE_REVISION,
                        null));
        build(view, layout, state, actions);
        view.scroll(layout.content().x() + 10, layout.content().y() + 30, 1);
        assertEquals(
                2,
                actions.size(),
                "Failure must release wheel request; unchanged stale metadata needs explicit reopen");
        assertEquals(new TerminalFilterView.Action.Open(summary.id(), 4, 3), actions.getLast());
    }

    @Test
    void screenFailureHookRestartsAnAuthoritativeCatalogAndDropsOldPartialRows() {
        var view = new TerminalFilterView(() -> {});
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(network(), presets(0));
        view.apply(state);
        build(view, layout, state, actions);
        view.tick(1, false);
        view.requestFailed(state);
        view.tick(2, false);
        assertEquals(2, actions.size());
        assertEquals(128, ((TerminalFilterView.Action.Query) actions.getLast()).offset());
        view.acceptLibrary(new io.github.loongin.omniresonance.networking.NetworkTerminalResponse.FilterLibrary(
                network().id(), network().ownerId(), 2, "", presets(128)));
        view.tick(3, false);
        assertEquals(256, ((TerminalFilterView.Action.Query) actions.getLast()).offset());
    }

    @Test
    void libraryLoadsAllBatchesBeforeLocalWheelNavigation() {
        var view = new TerminalFilterView(() -> {});
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(network(), presets(0));
        view.apply(state);
        build(view, layout, state, actions);
        for (int offset : new int[] {128, 256}) {
            view.tick(offset, false);
            assertEquals(offset, ((TerminalFilterView.Action.Query) actions.getLast()).offset());
            assertEquals("", ((TerminalFilterView.Action.Query) actions.getLast()).query());
            view.acceptLibrary(new io.github.loongin.omniresonance.networking.NetworkTerminalResponse.FilterLibrary(
                    network().id(), network().ownerId(), offset, "", presets(offset)));
        }
        build(view, layout, state, actions);
        int requests = actions.size();
        for (int i = 0; i < 200; i++)
            view.scroll(layout.content().x() + 10, layout.content().y() + 30, -1);
        assertEquals(requests, actions.size());
        for (int i = 0; i < 400; i++)
            view.scroll(layout.content().x() + 10, layout.content().y() + 30, 1);
        var widgets = build(view, layout, state, actions);
        ((TerminalRowButton) widgets.stream()
                        .filter(TerminalRowButton.class::isInstance)
                        .findFirst()
                        .orElseThrow())
                .onPress();
        assertEquals(new TerminalFilterView.Action.Open(new java.util.UUID(3, 0), 0, 0), actions.getLast());
    }

    @Test
    void ruleWheelUsesByteBoundedPreviousOffsetAndNoPageButtons() {
        var view = new TerminalFilterView(() -> {});
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(960, 540);
        var summary = new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                new java.util.UUID(3, 3), "Preset", 4, 10, true);
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Preset(
                network(),
                summary,
                new io.github.loongin.omniresonance.networking.FilterRulePage(
                        java.util.List.of("minecraft:stone"), 6, 10, 3));
        view.apply(state);
        var widgets = build(view, layout, state, actions);
        assertEquals(5, widgets.size(), "One rule and four rule actions; metadata belongs behind the gear");
        view.scroll(layout.content().x() + 10, layout.content().y() + 30, 1);
        view.scroll(layout.content().x() + 10, layout.content().y() + 30, 1);
        assertEquals(java.util.List.of(new TerminalFilterView.Action.Open(summary.id(), 4, 3)), actions);
        view.apply(state);
        build(view, layout, state, actions);
        view.scroll(layout.content().x() + 10, layout.content().y() + 30, -1);
        assertEquals(new TerminalFilterView.Action.Open(summary.id(), 4, 7), actions.getLast());
    }

    @Test
    void sharedGearOpensReadOnlyManagementAndRenameTargetsPresetInsteadOfSelectedRule() {
        var view = new TerminalFilterView(() -> {});
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var state = preset(true, 4);
        view.apply(state);
        var layout = TerminalLayout.calculate(960, 540);
        var rules = build(view, layout, state, actions);
        ((TerminalRowButton) rules.getFirst()).onPress();
        var header = TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(layout.window()), true);
        var gear = NetworkSetupScreen.buildSettingsButton(header.action(), state, view::openManagement, () -> {
            throw new AssertionError("Preset gear routed to tunnel");
        });
        assertEquals(TerminalSettingsButton.class, gear.getClass());
        gear.onPress();
        assertTrue(view.managementOpen());
        assertTrue(actions.isEmpty(), "Opening management must not send a locking edit action");
        var management = build(view, layout, state, actions);
        assertEquals(3, management.size());
        assertEquals(
                java.util.List.of("rename", "copy", "delete"),
                management.stream()
                        .map(widget -> ((net.minecraft.network.chat.contents.TranslatableContents)
                                        widget.getMessage().getContents())
                                .getKey()
                                .replace("omniresonance.terminal.filters.", ""))
                        .toList());
        ((TerminalButton) management.getFirst()).onPress();
        assertEquals(
                new TerminalFilterView.Action.Begin(
                        io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
                        state.preset().id()),
                actions.getLast());
        assertTrue(view.closeManagement());
        rules = build(view, layout, state, actions);
        assertTrue(((TerminalRowButton) rules.getFirst()).highlighted());
        ((TerminalButton) rules.getLast()).onPress();
        assertEquals(new TerminalFilterView.Action.CopyRule("minecraft:stone"), actions.getLast());
    }

    @Test
    void managementRetainsScrollAndSelectionOnLocalBackAndSameRevisionFailure() {
        var view = new TerminalFilterView(() -> {});
        var actions = new java.util.ArrayList<TerminalFilterView.Action>();
        var layout = TerminalLayout.calculate(320, 240);
        var entries = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "minecraft:item_" + i)
                .toList();
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Preset(
                network(),
                new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                        new java.util.UUID(3, 3), "Preset", 4, 20, true),
                new io.github.loongin.omniresonance.networking.FilterRulePage(entries, 0, 20, 0));
        view.apply(state);
        build(view, layout, state, actions);
        view.scroll(layout.content().x() + 10, layout.content().y() + 30, -1);
        var rules = build(view, layout, state, actions);
        String first = rules.getFirst().getMessage().getString();
        ((TerminalRowButton) rules.getFirst()).onPress();
        view.openManagement();
        build(view, layout, state, actions);
        view.requestFailed(state);
        assertTrue(view.managementOpen());
        assertTrue(view.closeManagement());
        rules = build(view, layout, state, actions);
        assertEquals(first, rules.getFirst().getMessage().getString());
        assertTrue(((TerminalRowButton) rules.getFirst()).highlighted());
    }

    @Test
    void managementKeepsCopyAllowanceAndDropsLocalLayerOnRevisionPermissionIdentityOrPageChanges() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var state = preset(false, 4);
            var view = new TerminalFilterView(() -> {});
            var layout = TerminalLayout.calculate(size[0], size[1]);
            var actions = new java.util.ArrayList<TerminalFilterView.Action>();
            view.apply(state);
            view.openManagement();
            var widgets = build(view, layout, state, actions);
            assertEquals(3, widgets.size());
            org.junit.jupiter.api.Assertions.assertFalse(widgets.getFirst().active);
            assertTrue(widgets.get(1).active);
            org.junit.jupiter.api.Assertions.assertFalse(widgets.getLast().active);
            for (var widget : widgets) {
                assertTrue(widget.getX() >= layout.content().x()
                        && widget.getRight() <= layout.content().right());
                assertTrue(widget.getY() >= layout.content().y()
                        && widget.getBottom() <= layout.content().bottom());
            }
            ((TerminalButton) widgets.get(1)).onPress();
            assertEquals(
                    new TerminalFilterView.Action.Begin(
                            io.github.loongin.omniresonance.filter.PresetEditOperation.COPY,
                            state.preset().id()),
                    actions.getLast());
        }
        var initial = preset(true, 4);
        var edit = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(),
                initial.preset(),
                io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
                new io.github.loongin.omniresonance.networking.FilterImpactSummary(0, 0, 0, true));
        var another = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Preset(
                network(),
                new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                        new java.util.UUID(3, 4), "Copy", 0, 1, true),
                initial.rules());
        for (var next : java.util.List.of(
                preset(false, 4),
                preset(true, 5),
                another,
                edit,
                new io.github.loongin.omniresonance.networking.NetworkTerminalState.NetworkRoot(network()),
                new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(network(), presets(0)))) {
            var view = new TerminalFilterView(() -> {});
            view.apply(initial);
            view.openManagement();
            NetworkSetupScreen.applyFilterResponse(
                    view,
                    new io.github.loongin.omniresonance.networking.NetworkTerminalResponse.ViewState(
                            new java.util.UUID(9, 1), new java.util.UUID(9, 2), 1, next));
            org.junit.jupiter.api.Assertions.assertFalse(view.managementOpen());
        }
    }

    @Test
    void pendingManagementBlocksBackUntilFailureAndRenameFieldNamesThePreset() {
        var view = new TerminalFilterView(() -> {});
        var state = preset(true, 4);
        var layout = TerminalLayout.calculate(320, 240);
        var font = new net.minecraft.client.gui.Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false);
        view.apply(state);
        view.openManagement();
        view.build(font, layout, state, "", true, widget -> {}, value -> {}, action -> {});
        org.junit.jupiter.api.Assertions.assertFalse(
                NetworkSetupScreen.closeLocalTopologyLayer(new TerminalNetworkContext(), view, () -> {}));
        NetworkSetupScreen.completeTopologySend(view, false);
        assertTrue(NetworkSetupScreen.closeLocalTopologyLayer(new TerminalNetworkContext(), view, () -> {}));
        var edit = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                network(),
                state.preset(),
                io.github.loongin.omniresonance.filter.PresetEditOperation.RENAME,
                new io.github.loongin.omniresonance.networking.FilterImpactSummary(1, 2, 3, true));
        assertEquals("Preset", view.initialValue(edit));
        var field = view.build(font, layout, edit, "", false, widget -> {}, value -> {}, action -> {});
        assertEquals(
                "omniresonance.terminal.filters.preset_name",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                                field.getMessage().getContents())
                        .getKey());
    }

    private static io.github.loongin.omniresonance.networking.NetworkTerminalState.Preset preset(
            boolean editable, long revision) {
        return new io.github.loongin.omniresonance.networking.NetworkTerminalState.Preset(
                network(),
                new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                        new java.util.UUID(3, 3), "Preset", revision, 1, editable),
                new io.github.loongin.omniresonance.networking.FilterRulePage(
                        java.util.List.of("minecraft:stone"), 0, 1, 0));
    }

    private static java.util.List<net.minecraft.client.gui.components.AbstractWidget> build(
            TerminalFilterView view,
            TerminalLayout layout,
            io.github.loongin.omniresonance.networking.NetworkTerminalState state,
            java.util.List<TerminalFilterView.Action> actions) {
        var widgets = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        var font = new net.minecraft.client.gui.Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false);
        view.build(font, layout, state, "", false, widgets::add, value -> {}, actions::add);
        return widgets;
    }

    private static io.github.loongin.omniresonance.networking.NetworkSummary network() {
        return new io.github.loongin.omniresonance.networking.NetworkSummary(
                new java.util.UUID(1, 1), new java.util.UUID(2, 2), "Network");
    }

    private static io.github.loongin.omniresonance.networking.FilterPresetPage presets(int offset) {
        var entries = new java.util.ArrayList<io.github.loongin.omniresonance.networking.FilterPresetSummary>();
        for (int i = offset; i < Math.min(260, offset + 128); i++)
            entries.add(new io.github.loongin.omniresonance.networking.FilterPresetSummary(
                    new java.util.UUID(3, i), "Preset " + i, 0, 0, true));
        return new io.github.loongin.omniresonance.networking.FilterPresetPage(entries, offset, 260, 1);
    }

    @Test
    void libraryHasOnlyRowsWithoutContentCreateOrPagingButtons() {
        var layout = TerminalLayout.calculate(960, 540);
        var widgets = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        var font = new net.minecraft.client.gui.Font(
                id -> {
                    throw new AssertionError("No rendering");
                },
                false);
        var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.Filters(
                new io.github.loongin.omniresonance.networking.NetworkSummary(
                        new java.util.UUID(1, 1), new java.util.UUID(2, 2), "Network"),
                new io.github.loongin.omniresonance.networking.FilterPresetPage(java.util.List.of(), 0, 0, 0));
        new TerminalFilterView(() -> {}).build(font, layout, state, "", false, widgets::add, value -> {}, action -> {});
        assertEquals(1, widgets.size(), "Only the independent collapsed library search belongs in content");
        assertTrue(widgets.getFirst() instanceof TerminalSearchButton, "Creation remains in the global header");
        assertEquals(TerminalHeaderLayout.Action.CREATE, TerminalInteractionPolicy.topBarAction(true, state));
    }

    @Test
    void presetEditorUsesFixedCompactBottomActions() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {960, 540}}) {
            var layout = TerminalLayout.calculate(size[0], size[1]);
            var widgets = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
            var actions = new java.util.ArrayList<TerminalFilterView.Action>();
            var font = new net.minecraft.client.gui.Font(
                    id -> {
                        throw new AssertionError("No glyph rendering");
                    },
                    false);
            var state = new io.github.loongin.omniresonance.networking.NetworkTerminalState.PresetEdit(
                    new io.github.loongin.omniresonance.networking.NetworkSummary(
                            new java.util.UUID(1, 1), new java.util.UUID(2, 2), "Network"),
                    null,
                    io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE,
                    new io.github.loongin.omniresonance.networking.FilterImpactSummary(0, 0, 0, true));
            new TerminalFilterView(() -> {})
                    .build(font, layout, state, "", false, widgets::add, value -> {}, actions::add);
            assertEquals(3, widgets.size());
            for (var widget : widgets) {
                assertTrue(widget.getX() >= layout.content().x()
                        && widget.getRight() <= layout.content().right());
                if (widget instanceof net.minecraft.client.gui.components.Button button) {
                    assertTrue(button.getWidth() <= 80);
                    assertEquals(
                            TerminalFilterView.editorBounds(layout.content(), state)
                                            .bottom()
                                    - 8,
                            button.getBottom());
                    button.onPress();
                }
            }
            assertEquals(
                    java.util.List.of(new TerminalFilterView.Action.Cancel(), new TerminalFilterView.Action.Save()),
                    actions);
        }
    }

    @Test
    void ruleActionsScrollInsideViewportAtCompactAndLargeSizes() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {1920, 1080}}) {
            var layout = TerminalLayout.calculate(size[0], size[1]);
            var state = preset(true, 4);
            var view = new TerminalFilterView(() -> {});
            view.apply(state);
            var actions = new java.util.ArrayList<TerminalFilterView.Action>();
            var seen = new java.util.HashSet<String>();
            for (int scroll = 0; scroll < 4; scroll++) {
                var widgets = build(view, layout, state, actions);
                for (var widget : widgets) {
                    if (widget instanceof TerminalRowButton) continue;
                    assertTrue(widget.getBottom() <= layout.content().bottom() - 20, "Actions overlap page status");
                    assertTrue(widget.getX() >= layout.content().x()
                            && widget.getRight() <= layout.content().right());
                    seen.add(((net.minecraft.network.chat.contents.TranslatableContents)
                                    widget.getMessage().getContents())
                            .getKey());
                }
                var last = widgets.getLast();
                view.scroll(last.getX() + 1, last.getY() + 1, -1);
            }
            assertEquals(
                    java.util.Set.of(
                            "omniresonance.terminal.filters.add_rule",
                            "omniresonance.terminal.filters.edit_rule",
                            "omniresonance.terminal.filters.remove_rule",
                            "omniresonance.terminal.filters.copy_id"),
                    seen);
            assertTrue(actions.isEmpty(), "Action-column scrolling dispatched a mutation");
        }
    }

    @Test
    void displayAbbreviationDoesNotRequireMeasuringAWholeMaximumLengthId() {
        String id = "a:" + "x".repeat(65533);
        assertTrue(TerminalFilterView.displayRule(id).length() <= 256);
        assertEquals(65535, id.length());
    }
}
