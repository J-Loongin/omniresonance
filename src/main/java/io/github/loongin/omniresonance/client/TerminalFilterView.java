// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.filter.PresetEditOperation;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Focused bounded preset/rule presentation inside the existing fixed terminal window. */
final class TerminalFilterView {
    sealed interface Action {
        record Page(int offset) implements Action {}

        record Open(UUID id, long revision, int offset) implements Action {}

        record Begin(
                PresetEditOperation operation, @Nullable UUID id, String originalRule) implements Action {
            Begin(PresetEditOperation operation, @Nullable UUID id) {
                this(operation, id, "");
            }
        }

        record Query(String query, int offset, long revision) implements Action {}

        record Read(UUID preset, long revision, UUID rule) implements Action {}

        record BeginFull(UUID preset, @Nullable UUID rule, boolean remove) implements Action {}

        record SaveFull(@Nullable io.github.loongin.omniresonance.filter.ResourceRuleIntent intent) implements Action {}

        record Sample(net.minecraft.resources.ResourceLocation type, int slot, int tank) implements Action {}

        record Save() implements Action {}

        record Cancel() implements Action {}

        record CopyRule(String value) implements Action {}
    }

    private record PendingRule(
            UUID network, UUID preset, io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match intent) {}

    private final java.util.List<net.minecraft.resources.ResourceLocation> resourceTypes =
            io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create().types();
    private @Nullable PendingRule pendingRule;
    private int pasteNoticeTicks;
    private @Nullable TerminalClickButton selectorButton;
    private @Nullable TerminalEditBox ruleField;

    @Nullable
    RecipeGhostTarget.Target ghostTarget() {
        if (!(state instanceof NetworkTerminalState.Preset preset)
                || !fullMode
                || management
                || pending
                || pagePending
                || queryPending
                || pendingRule != null
                || resourceDraft != null
                || !preset.preset().editable()
                || list.width() <= 0
                || list.height() <= 0) return null;
        return new RecipeGhostTarget.Target(
                preset.network().id(),
                preset.preset().id(),
                new RecipeGhostTarget.Area(list.x(), list.y(), list.width(), list.height()));
    }

    boolean acceptGhost(RecipeGhostTarget.Target target, RecipeGhostTarget.Ingredient ingredient) {
        if (!target.equals(ghostTarget())) return false;
        var intent = new io.github.loongin.omniresonance.filter.ResourceRuleIntent.Match(
                ingredient.type(),
                ingredient.type().equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY)
                        ? io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.wholeType()
                        : io.github.loongin.omniresonance.filter.ResourceFilterRule.Selector.exact(ingredient.id()),
                io.github.loongin.omniresonance.filter.ComponentCondition.Mode.ID_ONLY,
                java.util.Set.of(),
                null);
        pendingRule = new PendingRule(target.network(), target.preset(), intent);
        actions.accept(new Action.BeginFull(target.preset(), null, false));
        return true;
    }

    boolean pasteTag(@Nullable TerminalTagClipboard.Candidate candidate, String clipboard) {
        if (!(state instanceof NetworkTerminalState.Preset preset)
                || !fullMode
                || management
                || pending
                || pendingRule != null
                || pagePending
                || resourceDraft != null
                || !preset.preset().editable()) return false;
        var intent = TerminalTagPaste.read(candidate, clipboard);
        if (intent == null) {
            pasteNoticeTicks = 60;
            return true;
        }
        pendingRule = new PendingRule(preset.network().id(), preset.preset().id(), intent);
        actions.accept(new Action.BeginFull(preset.preset().id(), null, false));
        return true;
    }

    private final ClientSearchState librarySearch = new ClientSearchState();
    private @Nullable TerminalSearchBox searchField;
    private boolean queryPending;
    private final PresetSearchCatalog catalog = new PresetSearchCatalog();
    private long searchMatcherRevision = -1;
    private long searchTick;

    @Nullable
    TerminalSearchBox searchField() {
        return librarySearch.expanded() ? searchField : null;
    }

    boolean keyPressed(int keyCode, int modifiers) {
        if (net.minecraft.client.gui.screens.Screen.isPaste(keyCode)
                && pasteTag(
                        TerminalTagClipboard.recent(),
                        net.minecraft.client.Minecraft.getInstance()
                                .keyboardHandler
                                .getClipboard())) return true;
        return ClientSearchState.handleToggleKey(keyCode, modifiers, searchEligible(), this::toggleSearch);
    }

    private boolean searchEligible() {
        return !management
                && !samplePicker.isOpen()
                && (!pending || queryPending)
                && (state instanceof NetworkTerminalState.Filters || choosingReference);
    }

    private void toggleSearch() {
        if (!searchEligible()) return;
        librarySearch.toggle(searchTick);
        rebuild.run();
    }

    boolean searchExpanded() {
        return librarySearch.expanded();
    }

    boolean closeSearch() {
        if (!librarySearch.close(searchTick)) return false;
        rebuild.run();
        return true;
    }

    void finishSearchClick(boolean previous, net.minecraft.client.gui.components.events.ContainerEventHandler owner) {
        librarySearch.finishToggleClick(previous, owner, searchField);
    }

    void tick(long tick, boolean busy) {
        searchTick = tick;
        if (pasteNoticeTicks > 0) pasteNoticeTicks--;
        if (!supports(state) || busy || queryPending || catalog.failed()) return;
        if (!catalog.ready()) {
            queryPending = true;
            actions.accept(new Action.Query("", catalog.received(), catalog.revision()));
        } else if (librarySearch.due(tick) || searchMatcherRevision != ClientTextSearch.matcherRevision()) {
            librarySearch.handled();
            updateLibrary(0);
            rebuild.run();
        }
    }

    private void updateLibrary(int offset) {
        library = catalog.page(librarySearch.draft(), offset);
        searchMatcherRevision = ClientTextSearch.matcherRevision();
        libraryScroll = 0;
        scroll = 0;
        if (state instanceof NetworkTerminalState.Filters filters)
            state = new NetworkTerminalState.Filters(filters.network(), library);
    }

    private java.util.List<io.github.loongin.omniresonance.networking.FilterPresetSummary> libraryEntries() {
        return catalog.matches(librarySearch.draft());
    }

    void acceptLibrary(io.github.loongin.omniresonance.networking.NetworkTerminalResponse.FilterLibrary result) {
        if (!queryPending) return;
        queryPending = false;
        if (!result.query().isEmpty()) {
            catalog.fail();
            return;
        }
        catalog.accept(result.page());
        updateLibrary(0);
        pagePending = false;
        pendingDirection = PagedListScroll.PageRequest.NONE;
    }

    private void buildLibrarySearch(Font font, TerminalLayout.Rect header, Consumer<AbstractWidget> add) {
        searchField = null;
        TerminalLayout.Rect toggle = new TerminalLayout.Rect(header.right() - 20, header.y(), 20, 20);
        TerminalSearchButton search =
                new TerminalSearchButton(toggle, librarySearch.expanded(), label("search"), ignored -> toggleSearch());
        search.active = searchEligible();
        add.accept(search);
        if (catalog.failed()) {
            var retry = new TerminalButton(
                    header.x() + 4,
                    header.y() + (librarySearch.expanded() ? 50 : 26),
                    Math.min(80, header.width() - 8),
                    20,
                    Component.translatable("omniresonance.terminal.retry"),
                    ignored -> {
                        catalog.clear();
                        queryPending = false;
                        rebuild.run();
                    },
                    false);
            retry.active = !pending;
            add.accept(retry);
        }
        if (librarySearch.expanded()) {
            var bounds = TerminalSearchBox.bounds(header, header.y() + 24);
            searchField = librarySearch.field(
                    font, bounds, label("search"), 256, value -> librarySearch.edit(value, searchTick));
            add.accept(searchField);
        }
    }

    private boolean fullMode;
    private @Nullable UUID selectedRuleId;
    private @Nullable io.github.loongin.omniresonance.filter.ResourceFilterPreset detail;
    private @Nullable TerminalResourceRuleDraft resourceDraft;
    private @Nullable TerminalResourceRuleDraft readonlyDraft;
    private @Nullable NetworkTerminalState.Preset fullPreset;
    private io.github.loongin.omniresonance.networking.FilterPresetPage library =
            new io.github.loongin.omniresonance.networking.FilterPresetPage(java.util.List.of(), 0, 0, 0);
    private int libraryScroll, detailScroll, maxLibraryScroll, maxDetailScroll;
    private String sampleFailure = "";
    private int sampleSlot = -1;
    private boolean choosingReference;
    private final TerminalSamplePicker samplePicker = new TerminalSamplePicker();

    boolean closeLocalLayer() {
        if (samplePicker.back()) {
            rebuild.run();
            return true;
        }
        if (choosingReference) {
            if (closeSearch()) return true;
            choosingReference = false;
            closeSearch();
            rebuild.run();
            return true;
        }
        if (!management && state instanceof NetworkTerminalState.Preset && resourceDraft == null && detail != null) {
            detail = null;
            readonlyDraft = null;
            selectedRuleId = null;
            detailScroll = 0;
            rebuild.run();
            return true;
        }
        return false;
    }

    private TerminalLayout.Rect libraryBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect detailBounds = new TerminalLayout.Rect(0, 0, 0, 0);

    void acceptFull(io.github.loongin.omniresonance.networking.NetworkTerminalResponse.FullRule response) {
        if (!response.failure().isEmpty()) {
            sampleFailure = response.failure();
            return;
        }
        var snapshot = io.github.loongin.omniresonance.networking.FullFilterCodec.readSnapshot(response.snapshot());
        if (fullPreset == null
                || !snapshot.id().equals(fullPreset.preset().id())
                || snapshot.revision() != fullPreset.preset().revision()) return;
        var rule = snapshot.rules().getFirst();
        if (response.sampleToken() != null) {
            if (resourceDraft == null
                    || !(rule instanceof io.github.loongin.omniresonance.filter.ResourceFilterRule.Match match)) return;
            resourceDraft.sample(match, response.sampleToken());
            sampleFailure = "";
        } else {
            if (!rule.id().equals(selectedRuleId)) return;
            detail = snapshot;
            readonlyDraft = new TerminalResourceRuleDraft(rule);
        }
    }

    boolean resourceDirty() {
        return resourceDraft != null && resourceDraft.dirty;
    }

    private @Nullable NetworkTerminalState state;
    private @Nullable String selectedRule;
    private int scroll;
    private int maximumScroll;
    private int visibleRows = 1;
    private boolean pending;
    private boolean pagePending;
    private int expectedOffset;
    private PagedListScroll.PageRequest pendingDirection = PagedListScroll.PageRequest.NONE;
    private Consumer<Action> actions = action -> {};
    private boolean management;
    private int actionScroll;
    private int maximumActionScroll;
    private TerminalLayout.Rect actionBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect list = new TerminalLayout.Rect(0, 0, 0, 0);
    private final Runnable rebuild;

    TerminalFilterView(Runnable rebuild) {
        this.rebuild = rebuild;
    }

    static boolean supports(@Nullable NetworkTerminalState state) {
        return state instanceof NetworkTerminalState.Filters
                || state instanceof NetworkTerminalState.Preset
                || state instanceof NetworkTerminalState.PresetEdit;
    }

    boolean managementOpen() {
        return management;
    }

    void openManagement() {
        if (!(state instanceof NetworkTerminalState.Preset) || pending || pagePending) return;
        management = true;
        actionScroll = 0;
        rebuild.run();
    }

    boolean closeManagement() {
        if (!management || pending || pagePending) return false;
        management = false;
        actionScroll = 0;
        rebuild.run();
        return true;
    }

    void apply(@Nullable NetworkTerminalState next) {
        if (next instanceof NetworkTerminalState.Filters filters) {
            catalog.clear();
            catalog.accept(filters.page());
            library = catalog.page(librarySearch.draft(), 0);
            next = new NetworkTerminalState.Filters(filters.network(), library);
            queryPending = false;
            fullMode = false;
            fullPreset = null;
            detail = null;
            readonlyDraft = null;
            resourceDraft = null;
            selectedRuleId = null;
        }
        if (next instanceof NetworkTerminalState.Preset preset && preset.rules().fullDomain()) {
            if (catalog.ready()
                    && catalog.matches("").stream()
                            .noneMatch(entry -> entry.id()
                                            .equals(preset.preset().id())
                                    && entry.revision() == preset.preset().revision())) catalog.clear();
            fullMode = true;
            if (!(state instanceof NetworkTerminalState.Preset)) librarySearch.reset();
            if (fullPreset == null
                    || !fullPreset.preset().id().equals(preset.preset().id())
                    || fullPreset.preset().revision() != preset.preset().revision()) {
                detail = null;
                readonlyDraft = null;
                selectedRuleId = null;
                detailScroll = 0;
            }
            fullPreset = preset;
            resourceDraft = null;
            samplePicker.close();
            choosingReference = false;
        }
        if (fullMode
                && next instanceof NetworkTerminalState.PresetEdit edit
                && (edit.operation() == PresetEditOperation.ADD_RULE
                        || edit.operation() == PresetEditOperation.EDIT_RULE
                        || edit.operation() == PresetEditOperation.REMOVE_RULE)
                && !(state instanceof NetworkTerminalState.PresetEdit)) {
            sampleSlot = -1;
            resourceDraft = new TerminalResourceRuleDraft(
                    edit.operation() == PresetEditOperation.ADD_RULE || detail == null
                            ? null
                            : detail.rules().getFirst());
            if (pendingRule != null
                    && edit.operation() == PresetEditOperation.ADD_RULE
                    && edit.preset() != null
                    && pendingRule.preset().equals(edit.preset().id())
                    && pendingRule.network().equals(edit.network().id())) {
                var match = pendingRule.intent();
                resourceDraft.type = match.typeId();
                if (match.selector()
                        instanceof io.github.loongin.omniresonance.filter.ResourceFilterRule.TagSelector tag) {
                    resourceDraft.selector = 1;
                    resourceDraft.text = "#" + tag.tagId();
                } else if (match.selector()
                        instanceof io.github.loongin.omniresonance.filter.ResourceFilterRule.Exact exact) {
                    resourceDraft.selector = 0;
                    resourceDraft.text = exact.resourceId().toString();
                } else if (match.selector()
                        instanceof io.github.loongin.omniresonance.filter.ResourceFilterRule.Glob glob) {
                    resourceDraft.selector = 2;
                    resourceDraft.text = glob.glob().pattern();
                } else if (match.selector()
                        instanceof io.github.loongin.omniresonance.filter.ResourceFilterRule.WholeType) {
                    resourceDraft.selector = 3;
                    resourceDraft.text = "";
                }
                resourceDraft.dirty = true;
            }
            pendingRule = null;
            samplePicker.close();
            choosingReference = false;
            librarySearch.reset();
            detailScroll = 0;
        }
        if (!supports(next)) {
            samplePicker.close();
            choosingReference = false;
            pendingRule = null;
            pasteNoticeTicks = 0;
            librarySearch.reset();
            catalog.clear();
            queryPending = false;
            fullMode = false;
            fullPreset = null;
            detail = null;
            readonlyDraft = null;
            resourceDraft = null;
            selectedRuleId = null;
            library = new io.github.loongin.omniresonance.networking.FilterPresetPage(java.util.List.of(), 0, 0, 0);
        }
        if (!java.util.Objects.equals(state, next)) management = false;
        if (!supports(next)) {
            state = null;
            selectedRule = null;
            scroll = 0;
            pagePending = false;
            pendingDirection = PagedListScroll.PageRequest.NONE;
            return;
        }
        if (!(next instanceof NetworkTerminalState.PresetEdit)
                && (pagePending || !java.util.Objects.equals(state, next))) {
            int offset = next instanceof NetworkTerminalState.Filters filters
                    ? filters.page().offset()
                    : ((NetworkTerminalState.Preset) next).rules().offset();
            scroll = pagePending && offset == expectedOffset && pendingDirection == PagedListScroll.PageRequest.PREVIOUS
                    ? Integer.MAX_VALUE
                    : 0;
            pagePending = false;
            pendingDirection = PagedListScroll.PageRequest.NONE;
            actionScroll = 0;
            selectedRule = null;
        }
        if (!(next instanceof NetworkTerminalState.PresetEdit) && !java.util.Objects.equals(state, next))
            pendingRule = null;
        state = next;
    }

    void requestFailed(@Nullable NetworkTerminalState freshState) {
        pendingRule = null;
        pending = false;
        if (queryPending) catalog.fail();
        queryPending = false;
        pagePending = false;
        pendingDirection = PagedListScroll.PageRequest.NONE;
        expectedOffset = 0;
        // Without a fresh state, keep the current rows and revision. A stale rule revision requires leaving and
        // reopening the preset; releasing the local request must not silently invent fresh authority metadata.
        if (freshState != null) apply(freshState);
    }

    String initialValue(NetworkTerminalState.PresetEdit edit) {
        return switch (edit.operation()) {
            case RENAME -> edit.preset().name();
            case EDIT_RULE -> edit.originalRule();
            case REMOVE_RULE -> selectedRule == null ? "" : selectedRule;
            case CREATE, ADD_RULE, COPY, DELETE -> "";
        };
    }

    @Nullable
    EditBox build(
            Font font,
            TerminalLayout layout,
            NetworkTerminalState state,
            String draft,
            boolean pending,
            Consumer<AbstractWidget> add,
            Consumer<String> changed,
            Consumer<Action> actions) {
        this.state = state;
        this.pending = pending;
        this.actions = actions;
        TerminalLayout.Rect body = layout.content();
        if (fullMode
                && !management
                && (state instanceof NetworkTerminalState.Preset
                        || resourceDraft != null && state instanceof NetworkTerminalState.PresetEdit))
            return buildFull(font, body, add, changed, actions);
        if (state instanceof NetworkTerminalState.PresetEdit edit) {
            body = editorBounds(body, edit);
            int width = Math.min(420, body.width() - 24);
            int x = body.x() + (body.width() - width) / 2;
            int y = body.y() + 54;
            EditBox field = null;
            if (edit.operation() != PresetEditOperation.DELETE && edit.operation() != PresetEditOperation.REMOVE_RULE) {
                field = new TerminalEditBox(
                        font,
                        x,
                        y,
                        width,
                        20,
                        label(
                                edit.operation() == PresetEditOperation.RENAME
                                        ? "preset_name"
                                        : isRuleInput(edit.operation()) ? "item_id" : "value"));
                field.setMaxLength(isRuleInput(edit.operation()) ? 65535 : 256);
                field.setValue(draft);
                field.setResponder(changed);
                field.active = !pending;
                add.accept(field);
            }
            var footer = TerminalActionLayout.of(body);
            int buttonWidth = footer.primary().width();
            int buttonY = footer.primary().y();
            int actionX = footer.secondary().x();
            button(
                    add,
                    actionX,
                    buttonY,
                    buttonWidth,
                    "cancel",
                    () -> actions.accept(new Action.Cancel()),
                    !pending,
                    false);
            button(
                    add,
                    actionX + buttonWidth + TerminalLayout.GAP,
                    buttonY,
                    buttonWidth,
                    edit.operation() == PresetEditOperation.DELETE ? "delete_confirm" : "save",
                    () -> actions.accept(new Action.Save()),
                    !pending,
                    true);
            return field;
        }
        if (management && state instanceof NetworkTerminalState.Preset preset) {
            RoutingListLayout rows = actionLayout(body, actionScroll);
            actionScroll = rows.scroll();
            maximumActionScroll = Math.max(0, 3 - rows.visibleRows());
            actionBounds = rows.rows();
            PresetEditOperation[] operations = {
                PresetEditOperation.RENAME, PresetEditOperation.COPY, PresetEditOperation.DELETE
            };
            for (int row = 0; row < rows.visibleRows() && row + actionScroll < operations.length; row++) {
                PresetEditOperation operation = operations[row + actionScroll];
                TerminalLayout.Rect bounds = rows.row(row);
                button(
                        add,
                        bounds.x() + 8,
                        bounds.y(),
                        Math.min(260, bounds.width() - 16),
                        operation.name().toLowerCase(java.util.Locale.ROOT),
                        () -> actions.accept(
                                new Action.Begin(operation, preset.preset().id())),
                        !pending
                                && (operation == PresetEditOperation.COPY
                                        || preset.preset().editable()),
                        false);
            }
            return null;
        }
        int listWidth =
                state instanceof NetworkTerminalState.Preset ? Math.max(100, body.width() * 3 / 5) : body.width();
        if (state instanceof NetworkTerminalState.Filters) {
            buildLibrarySearch(font, new TerminalLayout.Rect(body.x() + 4, body.y() + 2, body.width() - 8, 20), add);
            state = new NetworkTerminalState.Filters(((NetworkTerminalState.Filters) state).network(), library);
            this.state = state;
        }
        int searchInset = state instanceof NetworkTerminalState.Filters && librarySearch.expanded() ? 24 : 0;
        TerminalLayout.Rect rowsBody = new TerminalLayout.Rect(
                body.x() + 4,
                body.y() + 24 + searchInset,
                listWidth - 8,
                Math.max(0, body.height() - 44 - searchInset));
        int count = state instanceof NetworkTerminalState.Filters filters
                ? filters.page().entries().size()
                : ((NetworkTerminalState.Preset) state).rules().entries().size();
        RoutingListLayout rows = RoutingListLayout.calculateRows(rowsBody, count, scroll);
        scroll = rows.scroll();
        visibleRows = rows.visibleRows();
        maximumScroll = Math.max(0, count - rows.visibleRows());
        list = rowsBody;
        for (int row = 0; row < rows.visibleRows() && row + scroll < count; row++) {
            int index = row + scroll;
            if (state instanceof NetworkTerminalState.Filters filters) {
                FilterPresetSummary preset = filters.page().entries().get(index);
                TerminalRowButton widget = new TerminalRowButton(
                        rows.row(row),
                        Component.translatable(
                                "omniresonance.terminal.filters.summary", preset.name(), preset.ruleCount()),
                        button -> actions.accept(new Action.Open(preset.id(), preset.revision(), 0)));
                widget.active = !pending;
                add.accept(widget);
            } else if (state instanceof NetworkTerminalState.Preset preset) {
                String rule = preset.rules().entries().get(index);
                TerminalRowButton widget =
                        new TerminalRowButton(rows.row(row), Component.literal(displayRule(rule)), button -> {
                            selectedRule = rule;
                            rebuild.run();
                        });
                widget.active = !pending;
                widget.setSelected(rule.equals(selectedRule));
                add.accept(widget);
            }
        }
        if (state instanceof NetworkTerminalState.Preset preset) {
            TerminalLayout.Rect column = new TerminalLayout.Rect(
                    body.x() + listWidth, body.y(), Math.max(0, body.width() - listWidth), body.height());
            RoutingListLayout actionRows = actionLayout(column, actionScroll, 4);
            actionScroll = actionRows.scroll();
            maximumActionScroll = Math.max(0, 4 - actionRows.visibleRows());
            actionBounds = actionRows.rows();
            PresetEditOperation[] operations = {
                PresetEditOperation.ADD_RULE, PresetEditOperation.EDIT_RULE, PresetEditOperation.REMOVE_RULE
            };
            for (int row = 0; row < actionRows.visibleRows() && row + actionScroll < 4; row++) {
                int index = row + actionScroll;
                TerminalLayout.Rect bounds = actionRows.row(row);
                if (index == 3) {
                    button(
                            add,
                            bounds.x(),
                            bounds.y(),
                            bounds.width(),
                            "copy_id",
                            () -> actions.accept(new Action.CopyRule(selectedRule)),
                            !pending && selectedRule != null,
                            false);
                } else {
                    PresetEditOperation operation = operations[index];
                    boolean enabled = operation == PresetEditOperation.COPY
                            || preset.preset().editable();
                    if (operation == PresetEditOperation.REMOVE_RULE || operation == PresetEditOperation.EDIT_RULE)
                        enabled &= selectedRule != null;
                    button(
                            add,
                            bounds.x(),
                            bounds.y(),
                            bounds.width(),
                            operation.name().toLowerCase(java.util.Locale.ROOT),
                            () -> actions.accept(new Action.Begin(
                                    operation,
                                    preset.preset().id(),
                                    operation == PresetEditOperation.EDIT_RULE ? selectedRule : "")),
                            !pending && enabled,
                            false);
                }
            }
        }
        return null;
    }

    void render(GuiGraphics graphics, Font font, TerminalLayout layout, NetworkTerminalState state, String draft) {
        if (state instanceof NetworkTerminalState.Filters filters)
            state = new NetworkTerminalState.Filters(filters.network(), library);
        TerminalLayout.Rect body = layout.content();
        TerminalTheme.renderPanel(graphics, body);
        if (fullMode
                && !management
                && (state instanceof NetworkTerminalState.Preset
                        || resourceDraft != null && state instanceof NetworkTerminalState.PresetEdit)) {
            renderFull(graphics, font);
            return;
        }
        if (state instanceof NetworkTerminalState.PresetEdit edit) {
            var dialog = editorBounds(body, edit);
            TerminalDialogLayout.render(graphics, body, dialog);
            body = dialog;
        }
        Component heading = state instanceof NetworkTerminalState.Filters
                ? label("title")
                : state instanceof NetworkTerminalState.Preset preset
                        ? (management
                                ? Component.translatable(
                                        "omniresonance.terminal.filters.management_title",
                                        preset.preset().name())
                                : Component.literal(preset.preset().name()))
                        : label(((NetworkTerminalState.PresetEdit) state)
                                .operation()
                                .name()
                                .toLowerCase(java.util.Locale.ROOT));
        graphics.drawString(
                font,
                TerminalText.body(
                        Component.literal(TerminalText.ellipsize(font, heading.getString(), body.width() - 24))),
                body.x() + 8,
                body.y() + 7,
                TerminalTheme.TEXT,
                false);
        if (state instanceof NetworkTerminalState.PresetEdit edit) {
            Component help = edit.operation() == PresetEditOperation.DELETE
                    ? label("delete_help")
                    : isRuleInput(edit.operation())
                            ? label("exact_id_help")
                            : edit.operation() == PresetEditOperation.REMOVE_RULE
                                    ? Component.literal(
                                            TerminalText.ellipsize(font, displayRule(draft), body.width() - 24))
                                    : label("name_help");
            graphics.drawWordWrap(
                    font,
                    TerminalText.body(help),
                    body.x() + 12,
                    body.y() + 28,
                    body.width() - 24,
                    TerminalTheme.MUTED);
            Component impact = Component.translatable(
                    "omniresonance.terminal.filters.impact",
                    edit.impact().networkCount(),
                    edit.impact().nodeCount(),
                    edit.impact().bindingCount());
            graphics.drawWordWrap(
                    font,
                    TerminalText.body(impact),
                    body.x() + 12,
                    body.y() + 82,
                    body.width() - 24,
                    TerminalTheme.MUTED);
            if (!edit.impact().complete())
                graphics.drawWordWrap(
                        font,
                        TerminalText.body(label("incomplete")),
                        body.x() + 12,
                        body.y() + 104,
                        body.width() - 24,
                        TerminalTheme.ERROR);
        } else if (!management) {
            int total = state instanceof NetworkTerminalState.Filters filters
                    ? filters.page().totalCount()
                    : ((NetworkTerminalState.Preset) state).rules().totalCount();
            int offset = state instanceof NetworkTerminalState.Filters filters
                    ? filters.page().offset()
                    : ((NetworkTerminalState.Preset) state).rules().offset();
            graphics.drawString(
                    font,
                    TerminalText.body(Component.translatable("omniresonance.terminal.filters.page", offset, total)),
                    body.x() + 8,
                    body.bottom() - 14,
                    TerminalTheme.MUTED,
                    false);
            if (state instanceof NetworkTerminalState.Preset)
                TerminalTheme.renderScrollbar(
                        graphics,
                        actionBounds.right() + 2,
                        actionBounds.y(),
                        actionBounds.height(),
                        4,
                        4 - maximumActionScroll,
                        actionScroll);
            TerminalTheme.renderScrollbar(
                    graphics,
                    list.right() - TerminalLayout.SCROLLBAR_WIDTH,
                    list.y(),
                    list.height(),
                    maximumScroll + 1,
                    1,
                    scroll);
        }
    }

    boolean scroll(double x, double y, double amount) {
        if (pending || pagePending) return supports(state);
        if (samplePicker.scroll(x, y, amount)) {
            rebuild.run();
            return true;
        }
        if (state instanceof NetworkTerminalState.Preset
                && x >= actionBounds.x()
                && x < actionBounds.right()
                && y >= actionBounds.y()
                && y < actionBounds.bottom()) {
            actionScroll = Math.max(0, Math.min(maximumActionScroll, actionScroll + (amount < 0 ? 1 : -1)));
            rebuild.run();
            return true;
        }
        if (fullMode && !management && inside(libraryBounds, x, y)) {
            if (!queryPending
                    && (amount < 0 && libraryScroll == maxLibraryScroll && library.hasNext()
                            || amount > 0 && libraryScroll == 0 && library.offset() > 0)) {
                queryPending = true;
                actions.accept(new Action.Query(
                        librarySearch.draft(),
                        amount < 0 ? library.offset() + libraryEntries().size() : Math.max(0, library.offset() - 128),
                        library.libraryRevision()));
            } else libraryScroll = Math.max(0, Math.min(maxLibraryScroll, libraryScroll + (amount < 0 ? 1 : -1)));
            rebuild.run();
            return true;
        }
        if (fullMode && !management && inside(detailBounds, x, y)) {
            detailScroll = Math.max(0, Math.min(maxDetailScroll, detailScroll + (amount < 0 ? 1 : -1)));
            rebuild.run();
            return true;
        }
        if (management) return false;
        if (!supports(state)
                || state instanceof NetworkTerminalState.PresetEdit
                || x < list.x()
                || x >= list.right()
                || y < list.y()
                || y >= list.bottom()) return false;
        boolean previous = state instanceof NetworkTerminalState.Filters filters
                ? filters.page().offset() > 0
                : ((NetworkTerminalState.Preset) state).rules().offset() > 0;
        boolean next = state instanceof NetworkTerminalState.Filters filters
                ? filters.page().hasNext()
                : ((NetworkTerminalState.Preset) state).rules().hasNext();
        var result = PagedListScroll.navigate(scroll, maximumScroll + visibleRows, visibleRows, previous, next, amount);
        scroll = result.scroll();
        if (result.pageRequest() == PagedListScroll.PageRequest.NONE) {
            rebuild.run();
        } else {
            boolean backwards = result.pageRequest() == PagedListScroll.PageRequest.PREVIOUS;
            pendingDirection = result.pageRequest();
            pagePending = true;
            if (state instanceof NetworkTerminalState.Filters filters) {
                int offset = backwards
                        ? Math.max(0, filters.page().offset() - 128)
                        : filters.page().offset() + filters.page().entries().size();
                updateLibrary(offset);
                if (backwards) scroll = Integer.MAX_VALUE;
                pagePending = false;
                pendingDirection = PagedListScroll.PageRequest.NONE;
                rebuild.run();
            } else if (state instanceof NetworkTerminalState.Preset preset) {
                expectedOffset = backwards
                        ? preset.rules().previousOffset()
                        : preset.rules().offset() + preset.rules().entries().size();
                actions.accept(
                        new Action.Open(preset.preset().id(), preset.preset().revision(), expectedOffset));
            }
        }
        return true;
    }

    static TerminalLayout.Rect editorBounds(TerminalLayout.Rect body, NetworkTerminalState.PresetEdit edit) {
        return TerminalDialogLayout.centered(body, 300, edit.impact().complete() ? 148 : 168);
    }

    private @Nullable EditBox buildFull(
            Font font,
            TerminalLayout.Rect body,
            Consumer<AbstractWidget> add,
            Consumer<String> changed,
            Consumer<Action> actions) {
        searchField = null;
        libraryBounds = new TerminalLayout.Rect(0, 0, 0, 0);
        list = new TerminalLayout.Rect(0, 0, 0, 0);
        detailBounds = new TerminalLayout.Rect(0, 0, 0, 0);
        if (fullPreset == null) return null;
        if (resourceDraft != null && samplePicker.isOpen()) {
            samplePicker.build(
                    font,
                    body,
                    resourceDraft.type,
                    pending,
                    add,
                    action -> {
                        if (action instanceof Action.Sample sample) sampleSlot = sample.slot();
                        actions.accept(action);
                    },
                    rebuild);
            return null;
        }
        if (resourceDraft != null && choosingReference) {
            buildReferencePicker(font, body, add, changed);
            return searchField;
        }
        var footer = TerminalActionLayout.of(body);
        if (resourceDraft == null) {
            buildRuleBrowser(body, add, actions);
            return null;
        }
        body = footer.content();
        detailBounds = new TerminalLayout.Rect(
                body.x() + 4, body.y() + 4, Math.max(0, body.width() - 8), Math.max(0, body.height() - 8));
        TerminalResourceRuleDraft draft = resourceDraft;
        button(
                add,
                footer.secondary().x(),
                footer.secondary().y(),
                footer.secondary().width(),
                "cancel",
                () -> actions.accept(new Action.Cancel()),
                !pending,
                false);
        button(
                add,
                footer.primary().x(),
                footer.primary().y(),
                footer.primary().width(),
                "save",
                () -> {
                    try {
                        actions.accept(new Action.SaveFull(
                                state instanceof NetworkTerminalState.PresetEdit edit
                                                && edit.operation() == PresetEditOperation.REMOVE_RULE
                                        ? null
                                        : draft.intent()));
                    } catch (RuntimeException invalid) {
                        sampleFailure = "invalid_rule";
                        rebuild.run();
                    }
                },
                !pending,
                true);
        java.util.List<String> keys = draft.keys();
        java.util.List<Integer> fields = new java.util.ArrayList<>(java.util.List.of(0, 1));
        if (draft.selector != 3) fields.add(2);
        if (draft.selector != 4
                && (draft.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)
                        || draft.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID))) {
            fields.add(3);
            if (draft.mode != io.github.loongin.omniresonance.filter.ComponentCondition.Mode.ID_ONLY) {
                fields.add(6);
                if (draft.mode == io.github.loongin.omniresonance.filter.ComponentCondition.Mode.SELECTED)
                    for (int index = 0; index < keys.size(); index++) fields.add(index + 7);
            }
        }
        int count = fields.size();
        var details = RoutingListLayout.calculateRows(detailBounds, count, detailScroll);
        detailScroll = details.scroll();
        maxDetailScroll = Math.max(0, count - details.visibleRows());
        selectorButton = null;
        ruleField = null;
        EditBox firstField = null;
        boolean deleting = state instanceof NetworkTerminalState.PresetEdit edit
                && edit.operation() == PresetEditOperation.REMOVE_RULE;
        for (int row = 0; row < details.visibleRows() && row + detailScroll < count; row++) {
            int index = fields.get(row + detailScroll);
            var bounds = details.row(row);
            if (index == 2 && draft.selector == 4) {
                Component selected = libraryEntries().stream()
                        .filter(preset -> preset.id().equals(draft.reference))
                        .map(preset -> (Component) Component.literal(preset.name()))
                        .findFirst()
                        .orElse(label("choose_reference"));
                var referenceButton = new TerminalButton(
                        bounds.x(),
                        bounds.y(),
                        bounds.width(),
                        20,
                        selected,
                        ignored -> {
                            choosingReference = true;
                            rebuild.run();
                        },
                        false);
                referenceButton.active = !pending && !deleting;
                referenceButton.setTooltip(
                        net.minecraft.client.gui.components.Tooltip.create(label("choose_reference")));
                add.accept(referenceButton);
            } else if (index == 2) {
                TerminalEditBox field =
                        new TerminalEditBox(font, bounds.x(), bounds.y(), bounds.width(), 20, label("selector_value"));
                ruleField = field;
                field.setMaxLength(65535);
                field.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label("typed_rule_help")));
                field.setValue(draft.text);
                field.setResponder(value -> {
                    sampleFailure = "";
                    if (draft.pasteTag(TerminalTagClipboard.recent(), value)) {
                        sampleSlot = -1;
                        changed.accept(draft.text);
                        rebuildRuleInput(field, draft.text.length());
                        return;
                    }
                    var previousType = draft.type;
                    int cursor = field.getCursorPosition();
                    draft.editText(value);
                    if (selectorButton != null) selectorButton.setMessage(label("selector_" + draft.selector));
                    changed.accept(value);
                    if (!previousType.equals(draft.type)) {
                        sampleSlot = -1;
                        rebuildRuleInput(field, cursor);
                    }
                });
                field.active = !pending && !deleting;
                add.accept(field);
                if (firstField == null) firstField = field;
            } else {
                String key = index == 0
                        ? "resource_"
                                + (draft.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)
                                        ? "item"
                                        : draft.type.equals(
                                                        io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID)
                                                ? "fluid"
                                                : "energy")
                        : index == 1
                                ? "selector_" + draft.selector
                                : index == 3
                                        ? "components_" + draft.mode.name().toLowerCase(java.util.Locale.ROOT)
                                        : index == 6 ? "sample" : "component";
                net.minecraft.client.gui.components.Button.OnPress press = ignored -> {
                    sampleFailure = "";
                    if (index == 0) {
                        var types = resourceTypes;
                        draft.changeType(types.get((types.indexOf(draft.type) + 1) % types.size()));
                        sampleSlot = -1;
                        draft.source = io.github.loongin.omniresonance.filter.ComponentCondition.idOnly();
                        draft.sampleToken = null;
                        draft.mode = io.github.loongin.omniresonance.filter.ComponentCondition.Mode.ID_ONLY;
                        draft.selected.clear();
                        if (io.github.loongin.omniresonance.bootstrap.ResourceAdapters.scalarType(draft.type))
                            draft.selector = 3;
                    } else if (index == 1) {
                        draft.select((draft.selector + 1) % 5);
                        if (draft.selector == 4) choosingReference = true;
                    } else if (index == 3)
                        draft.mode = io.github.loongin.omniresonance.filter.ComponentCondition.Mode.values()[
                                (draft.mode.ordinal() + 1) % 3];
                    else if (index == 6) {
                        samplePicker.open();
                        rebuild.run();
                        return;
                    } else if (index >= 7) {
                        var component = net.minecraft.resources.ResourceLocation.parse(keys.get(index - 7));
                        if (!draft.selected.add(component)) draft.selected.remove(component);
                    }
                    draft.dirty = true;
                    changed.accept(draft.text);
                    rebuild.run();
                };
                Component message = index >= 7
                        ? Component.literal((draft.selected.contains(
                                                net.minecraft.resources.ResourceLocation.parse(keys.get(index - 7)))
                                        ? "✓ "
                                        : "")
                                + keys.get(index - 7))
                        : index == 0
                                        && !draft.type.equals(
                                                io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)
                                        && !draft.type.equals(
                                                io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID)
                                        && !draft.type.equals(
                                                io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY)
                                ? NodeResourcePolicyView.typeName(draft.type)
                                : label(key);
                TerminalClickButton widget = index == 6
                        ? new TerminalSampleSlot(bounds, message, press, () -> {
                            var player = net.minecraft.client.Minecraft.getInstance().player;
                            return player == null
                                            || sampleSlot < 0
                                            || sampleSlot
                                                    >= player.getInventory().getContainerSize()
                                    ? net.minecraft.world.item.ItemStack.EMPTY
                                    : player.getInventory().getItem(sampleSlot);
                        })
                        : new TerminalButton(bounds.x(), bounds.y(), bounds.width(), 20, message, press, false);
                if (index == 1) selectorButton = widget;
                widget.active = !pending
                        && !deleting
                        && (index < 7
                                || draft.mode
                                        == io.github.loongin.omniresonance.filter.ComponentCondition.Mode.SELECTED)
                        && (index != 1 && index != 3 && index != 6
                                || !io.github.loongin.omniresonance.bootstrap.ResourceAdapters.scalarType(draft.type));
                if (index >= 7)
                    widget.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal(componentPreview(draft.available().get(keys.get(index - 7))))));
                add.accept(widget);
            }
        }
        return firstField;
    }

    private void rebuildRuleInput(TerminalEditBox previous, int cursor) {
        boolean focused = previous.isFocused();
        rebuild.run();
        if (focused && ruleField != null) {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft != null && minecraft.screen != null) minecraft.screen.setFocused(ruleField);
            ruleField.setFocused(true);
            ruleField.setCursorPosition(Math.min(cursor, ruleField.getValue().length()));
        }
    }

    private void buildReferencePicker(
            Font font, TerminalLayout.Rect body, Consumer<AbstractWidget> add, Consumer<String> changed) {
        var footer = TerminalActionLayout.of(body);
        buildLibrarySearch(font, new TerminalLayout.Rect(body.x() + 4, body.y() + 2, body.width() - 8, 20), add);
        int inset = librarySearch.expanded() ? 50 : 26;
        libraryBounds = new TerminalLayout.Rect(
                body.x() + 4,
                body.y() + inset,
                body.width() - 8,
                Math.max(0, footer.content().height() - inset));
        var entries = libraryEntries();
        var rows = RoutingListLayout.calculateRows(libraryBounds, entries.size(), libraryScroll);
        libraryScroll = rows.scroll();
        maxLibraryScroll = Math.max(0, entries.size() - rows.visibleRows());
        for (int row = 0; row < rows.visibleRows() && row + libraryScroll < entries.size(); row++) {
            var preset = entries.get(row + libraryScroll);
            var widget = new TerminalRowButton(rows.row(row), Component.literal(preset.name()), ignored -> {
                if (resourceDraft == null) return;
                resourceDraft.reference = preset.id();
                resourceDraft.dirty = true;
                choosingReference = false;
                librarySearch.reset();
                changed.accept(resourceDraft.text);
                rebuild.run();
            });
            widget.active = !pending && !queryPending;
            add.accept(widget);
        }
        var back = footer.primary();
        button(add, back.x(), back.y(), back.width(), "sample_back", this::closeLocalLayer, !pending, false);
    }

    private void buildRuleBrowser(TerminalLayout.Rect body, Consumer<AbstractWidget> add, Consumer<Action> actions) {
        var toolbar = new TerminalLayout.Rect(body.x(), body.y(), body.width(), 36);
        if (detail == null) {
            var addBounds = TerminalActionLayout.toolbarButton(toolbar, 1, 0);
            button(
                    add,
                    addBounds.x(),
                    addBounds.y(),
                    addBounds.width(),
                    "add_rule_full",
                    () -> actions.accept(
                            new Action.BeginFull(fullPreset.preset().id(), null, false)),
                    !pending && fullPreset.preset().editable(),
                    false);
            list = new TerminalLayout.Rect(
                    body.x() + 4, body.y() + 36, body.width() - 8, Math.max(0, body.height() - 40));
            var page = fullPreset.rules();
            var rows = RoutingListLayout.calculateRows(list, page.entries().size(), scroll);
            scroll = rows.scroll();
            visibleRows = rows.visibleRows();
            maximumScroll = Math.max(0, page.entries().size() - visibleRows);
            for (int row = 0;
                    row < rows.visibleRows() && row + scroll < page.entries().size();
                    row++) {
                int index = row + scroll;
                UUID id = page.ruleIds().get(index);
                var widget = new TerminalRowButton(
                        rows.row(row),
                        Component.literal(displayRule(page.entries().get(index))),
                        ignored -> {
                            selectedRuleId = id;
                            detailScroll = 0;
                            actions.accept(new Action.Read(
                                    fullPreset.preset().id(),
                                    fullPreset.preset().revision(),
                                    id));
                        });
                widget.active = !pending;
                add.accept(widget);
            }
            return;
        }
        var readonly = new TerminalResourceRuleDraft(detail.rules().getFirst());
        for (int index = 0; index < 3; index++) {
            int action = index;
            var bounds = TerminalActionLayout.toolbarButton(toolbar, 3, index);
            String key = index == 0 ? "edit_rule_full" : index == 1 ? "remove_rule_full" : "copy_id";
            button(
                    add,
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    key,
                    () -> {
                        if (action == 2) actions.accept(new Action.CopyRule(readonly.text));
                        else
                            actions.accept(
                                    new Action.BeginFull(fullPreset.preset().id(), selectedRuleId, action == 1));
                    },
                    !pending
                            && (index == 2
                                    ? !readonly.text.isEmpty()
                                    : fullPreset.preset().editable()),
                    false);
        }
        detailBounds =
                new TerminalLayout.Rect(body.x() + 4, body.y() + 36, body.width() - 8, Math.max(0, body.height() - 40));
        var lines = detailLines(readonly);
        var rows = RoutingListLayout.calculateRows(detailBounds, lines.size(), detailScroll);
        detailScroll = rows.scroll();
        maxDetailScroll = Math.max(0, lines.size() - rows.visibleRows());
        for (int row = 0; row < rows.visibleRows() && row + detailScroll < lines.size(); row++) {
            var widget = new TerminalRowButton(
                    rows.row(row), Component.literal(displayRule(lines.get(row + detailScroll))), ignored -> {});
            widget.setReadOnly();
            add.accept(widget);
        }
    }

    private java.util.List<String> detailLines(TerminalResourceRuleDraft draft) {
        java.util.List<String> keys = draft.keys();
        return new java.util.AbstractList<>() {
            @Override
            public int size() {
                return 3 + keys.size();
            }

            @Override
            public String get(int index) {
                if (index == 0) return draft.selector == 4 ? label("selector_4").getString() : draft.type.toString();
                if (index == 1) return draft.text;
                if (index == 2)
                    return label("components_" + draft.mode.name().toLowerCase(java.util.Locale.ROOT))
                            .getString();
                String key = keys.get(index - 3);
                return key + ": " + componentPreview(draft.available().get(key));
            }
        };
    }

    private static String componentPreview(@Nullable net.minecraft.nbt.Tag tag) {
        if (tag == null) return "";
        if (tag instanceof net.minecraft.nbt.ByteArrayTag array) return "byte[" + array.size() + "]";
        if (tag instanceof net.minecraft.nbt.IntArrayTag array) return "int[" + array.size() + "]";
        if (tag instanceof net.minecraft.nbt.LongArrayTag array) return "long[" + array.size() + "]";
        if (tag instanceof net.minecraft.nbt.ListTag list) return "list[" + list.size() + "]";
        if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
            StringBuilder result = new StringBuilder("{");
            int count = 0;
            for (String key : compound.getAllKeys()) {
                if (count++ == 3) {
                    result.append("...");
                    break;
                }
                if (count > 1) result.append(", ");
                result.append(displayRule(key));
            }
            return result.append('}').toString();
        }
        return displayRule(tag.getAsString());
    }

    void renderPasteNotice(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (pasteNoticeTicks > 0 && supports(state))
            graphics.renderTooltip(font, TerminalText.body(label("paste_tag_unavailable")), mouseX, mouseY);
    }

    private void renderFull(GuiGraphics graphics, Font font) {
        if (samplePicker.isOpen()) {
            samplePicker.render(graphics, font);
            return;
        }
        if (choosingReference && !librarySearch.expanded())
            graphics.drawString(
                    font,
                    TerminalText.body(label("choose_reference")),
                    libraryBounds.x(),
                    libraryBounds.y() - 20,
                    TerminalTheme.TEXT,
                    false);
        for (TerminalLayout.Rect bounds : java.util.List.of(libraryBounds, list, detailBounds)) {
            if (bounds.width() == 0 || bounds.height() == 0) continue;
            TerminalTheme.renderScrollbar(
                    graphics,
                    bounds.right() - TerminalLayout.SCROLLBAR_WIDTH,
                    bounds.y(),
                    bounds.height(),
                    bounds == libraryBounds
                            ? maxLibraryScroll + 1
                            : bounds == detailBounds ? maxDetailScroll + 1 : maximumScroll + 1,
                    1,
                    bounds == libraryBounds ? libraryScroll : bounds == detailBounds ? detailScroll : scroll);
        }
        if (!sampleFailure.isEmpty())
            graphics.drawString(
                    font,
                    TerminalText.body(label("sample_" + sampleFailure)),
                    detailBounds.x(),
                    detailBounds.bottom() - 10,
                    TerminalTheme.ERROR,
                    false);
    }

    private static boolean inside(TerminalLayout.Rect bounds, double x, double y) {
        return x >= bounds.x() && x < bounds.right() && y >= bounds.y() && y < bounds.bottom();
    }

    static RoutingListLayout actionLayout(TerminalLayout.Rect body, int requestedScroll) {
        return actionLayout(body, requestedScroll, 3);
    }

    private static RoutingListLayout actionLayout(TerminalLayout.Rect body, int requestedScroll, int count) {
        TerminalLayout.Rect actions = new TerminalLayout.Rect(
                body.x(), body.y() + 26, body.width(), Math.min(count * 24, Math.max(0, body.height() - 46)));
        return RoutingListLayout.calculateRows(actions, count, requestedScroll);
    }

    private static boolean isRuleInput(PresetEditOperation operation) {
        return operation == PresetEditOperation.ADD_RULE || operation == PresetEditOperation.EDIT_RULE;
    }

    static String displayRule(String rule) {
        return rule.length() > 256 ? rule.substring(0, 253) + "..." : rule;
    }

    private static Component label(String key) {
        return Component.translatable("omniresonance.terminal.filters." + key);
    }

    private static void button(
            Consumer<AbstractWidget> add,
            int x,
            int y,
            int width,
            String key,
            Runnable action,
            boolean active,
            boolean primary) {
        TerminalButton button = new TerminalButton(x, y, width, 20, label(key), ignored -> action.run(), primary);
        button.active = active;
        if (key.equals("add_rule_full"))
            button.setTooltip(
                    net.minecraft.client.gui.components.Tooltip.create(TerminalText.body(label("paste_tag_hint"))));
        add.accept(button);
    }
}
