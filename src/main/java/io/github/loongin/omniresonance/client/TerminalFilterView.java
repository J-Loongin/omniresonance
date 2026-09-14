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

    private final ClientSearchState librarySearch = new ClientSearchState();
    private @Nullable TerminalSearchBox searchField;
    private boolean queryPending;
    private long searchTick;

    @Nullable
    TerminalSearchBox searchField() {
        return librarySearch.expanded() ? searchField : null;
    }

    boolean keyPressed(int keyCode, int modifiers) {
        if (!librarySearch.openFromKey(keyCode, modifiers, supports(state) && !management)) return false;
        rebuild.run();
        return true;
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
        if (!supports(state) || busy || queryPending || !librarySearch.due(tick)) return;
        String query = ClientSearchState.normalizedQuery(librarySearch.draft());
        if (query == null) return;
        librarySearch.handled();
        queryPending = true;
        actions.accept(new Action.Query(query, 0, -1));
    }

    void acceptLibrary(io.github.loongin.omniresonance.networking.NetworkTerminalResponse.FilterLibrary result) {
        queryPending = false;
        if (!result.query().equals(ClientSearchState.normalizedQuery(librarySearch.draft()))) return;
        library = result.page();
        libraryScroll = 0;
        if (state instanceof NetworkTerminalState.Filters filters) {
            state = new NetworkTerminalState.Filters(filters.network(), library);
            scroll = pendingDirection == PagedListScroll.PageRequest.PREVIOUS ? Integer.MAX_VALUE : 0;
        }
        pagePending = false;
        pendingDirection = PagedListScroll.PageRequest.NONE;
    }

    private void buildLibrarySearch(Font font, TerminalLayout.Rect header, Consumer<AbstractWidget> add) {
        searchField = null;
        TerminalLayout.Rect toggle = new TerminalLayout.Rect(header.right() - 20, header.y(), 20, 20);
        TerminalSearchButton search =
                new TerminalSearchButton(toggle, librarySearch.expanded(), label("search"), ignored -> {
                    if (librarySearch.expanded()) librarySearch.close(searchTick);
                    else librarySearch.open();
                    rebuild.run();
                });
        search.active = !pending;
        add.accept(search);
        if (librarySearch.expanded()) {
            searchField = new TerminalSearchBox(font, header.x(), header.y() + 24, header.width(), 20, label("search"));
            searchField.setMaxLength(256);
            searchField.setValue(librarySearch.draft());
            searchField.setResponder(value -> librarySearch.edit(value, searchTick));
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
    private String sampleSlot = "0", sampleTank = "0", sampleFailure = "";
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
            library = filters.page();
            fullMode = false;
            fullPreset = null;
            detail = null;
            readonlyDraft = null;
            resourceDraft = null;
            selectedRuleId = null;
        }
        if (next instanceof NetworkTerminalState.Preset preset && preset.rules().fullDomain()) {
            fullMode = true;
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
        }
        if (fullMode
                && next instanceof NetworkTerminalState.PresetEdit edit
                && (edit.operation() == PresetEditOperation.ADD_RULE
                        || edit.operation() == PresetEditOperation.EDIT_RULE
                        || edit.operation() == PresetEditOperation.REMOVE_RULE)
                && !(state instanceof NetworkTerminalState.PresetEdit)) {
            resourceDraft = new TerminalResourceRuleDraft(
                    edit.operation() == PresetEditOperation.ADD_RULE || detail == null
                            ? null
                            : detail.rules().getFirst());
            detailScroll = 0;
        }
        if (!supports(next)) {
            librarySearch.reset();
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
        state = next;
    }

    void requestFailed(@Nullable NetworkTerminalState freshState) {
        pending = false;
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
            int buttonWidth = 64;
            int buttonY = body.y() + 4;
            int actionX = body.right() - 12 - buttonWidth * 2 - TerminalLayout.GAP;
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
                TerminalText.body(Component.literal(TerminalText.ellipsize(
                        font,
                        heading.getString(),
                        body.width() - (state instanceof NetworkTerminalState.PresetEdit ? 164 : 16)))),
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
                        amount < 0 ? library.offset() + library.entries().size() : Math.max(0, library.offset() - 128),
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
                expectedOffset = backwards
                        ? Math.max(0, filters.page().offset() - 128)
                        : filters.page().offset() + filters.page().entries().size();
                if (librarySearch.draft().isEmpty()) actions.accept(new Action.Page(expectedOffset));
                else {
                    queryPending = true;
                    actions.accept(new Action.Query(librarySearch.draft(), expectedOffset, library.libraryRevision()));
                }
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

    private @Nullable EditBox buildFull(
            Font font,
            TerminalLayout.Rect body,
            Consumer<AbstractWidget> add,
            Consumer<String> changed,
            Consumer<Action> actions) {
        int first = Math.max(70, body.width() / 4), second = Math.max(80, body.width() / 3);
        int searchInset = librarySearch.expanded() ? 24 : 0;
        buildLibrarySearch(font, new TerminalLayout.Rect(body.x() + 4, body.y() + 2, first - 8, 20), add);
        libraryBounds = new TerminalLayout.Rect(
                body.x() + 4, body.y() + 26 + searchInset, first - 8, Math.max(0, body.height() - 32 - searchInset));
        list = new TerminalLayout.Rect(
                body.x() + first + 2, body.y() + 26, second - 8, Math.max(0, body.height() - 32));
        detailBounds = new TerminalLayout.Rect(
                body.x() + first + second,
                body.y() + 26,
                Math.max(1, body.width() - first - second - 6),
                Math.max(0, body.height() - 32));
        var libraryRows =
                RoutingListLayout.calculateRows(libraryBounds, library.entries().size(), libraryScroll);
        libraryScroll = libraryRows.scroll();
        maxLibraryScroll = Math.max(0, library.entries().size() - libraryRows.visibleRows());
        for (int row = 0;
                row < libraryRows.visibleRows()
                        && row + libraryScroll < library.entries().size();
                row++) {
            var preset = library.entries().get(row + libraryScroll);
            TerminalRowButton widget =
                    new TerminalRowButton(libraryRows.row(row), Component.literal(preset.name()), button -> {
                        if (resourceDraft != null && resourceDraft.selector == 4) {
                            resourceDraft.reference = preset.id();
                            resourceDraft.dirty = true;
                            changed.accept(resourceDraft.text);
                            rebuild.run();
                        } else actions.accept(new Action.Open(preset.id(), preset.revision(), 0));
                    });
            widget.active = !pending && !queryPending && (resourceDraft == null || resourceDraft.selector == 4);
            widget.setSelected(
                    resourceDraft != null && resourceDraft.selector == 4
                            ? preset.id().equals(resourceDraft.reference)
                            : fullPreset != null
                                    && preset.id().equals(fullPreset.preset().id()));
            add.accept(widget);
        }
        if (fullPreset == null) return null;
        var page = fullPreset.rules();
        var rows = RoutingListLayout.calculateRows(list, page.entries().size(), scroll);
        scroll = rows.scroll();
        visibleRows = rows.visibleRows();
        maximumScroll = Math.max(0, page.entries().size() - visibleRows);
        for (int row = 0;
                row < rows.visibleRows() && row + scroll < page.entries().size();
                row++) {
            int index = row + scroll;
            UUID ruleId = page.ruleIds().get(index);
            TerminalRowButton widget = new TerminalRowButton(
                    rows.row(row), Component.literal(displayRule(page.entries().get(index))), button -> {
                        selectedRuleId = ruleId;
                        detailScroll = 0;
                        actions.accept(new Action.Read(
                                fullPreset.preset().id(), fullPreset.preset().revision(), ruleId));
                    });
            widget.active = !pending && resourceDraft == null;
            widget.setSelected(ruleId.equals(selectedRuleId));
            add.accept(widget);
        }
        int half = Math.max(20, (detailBounds.width() - 4) / 2);
        if (resourceDraft == null) {
            button(
                    add,
                    detailBounds.x(),
                    body.y() + 2,
                    half,
                    "add_rule_full",
                    () -> actions.accept(
                            new Action.BeginFull(fullPreset.preset().id(), null, false)),
                    !pending && fullPreset.preset().editable(),
                    false);
            button(
                    add,
                    detailBounds.x() + half + 4,
                    body.y() + 2,
                    half,
                    "edit_rule_full",
                    () -> actions.accept(
                            new Action.BeginFull(fullPreset.preset().id(), selectedRuleId, false)),
                    !pending
                            && selectedRuleId != null
                            && detail != null
                            && fullPreset.preset().editable(),
                    false);
            if (detail != null) {
                TerminalResourceRuleDraft readonly =
                        new TerminalResourceRuleDraft(detail.rules().getFirst());
                java.util.List<String> lines = detailLines(readonly);
                var content = RoutingListLayout.calculateRows(detailBounds, lines.size() + 2, detailScroll);
                detailScroll = content.scroll();
                maxDetailScroll = Math.max(0, lines.size() + 2 - content.visibleRows());
                for (int row = 0; row < content.visibleRows() && row + detailScroll < lines.size() + 2; row++) {
                    int index = row + detailScroll;
                    var bounds = content.row(row);
                    if (index < lines.size()) {
                        TerminalRowButton label = new TerminalRowButton(
                                bounds, Component.literal(displayRule(lines.get(index))), ignored -> {});
                        label.setReadOnly();
                        add.accept(label);
                    } else if (index == lines.size())
                        button(
                                add,
                                bounds.x(),
                                bounds.y(),
                                bounds.width(),
                                "copy_id",
                                () -> actions.accept(new Action.CopyRule(readonly.text)),
                                !pending && !readonly.text.isEmpty(),
                                false);
                    else
                        button(
                                add,
                                bounds.x(),
                                bounds.y(),
                                bounds.width(),
                                "remove_rule_full",
                                () -> actions.accept(
                                        new Action.BeginFull(fullPreset.preset().id(), selectedRuleId, true)),
                                !pending && fullPreset.preset().editable(),
                                false);
                }
            }
            return null;
        }
        TerminalResourceRuleDraft draft = resourceDraft;
        button(
                add,
                detailBounds.x(),
                body.y() + 2,
                half,
                "cancel",
                () -> actions.accept(new Action.Cancel()),
                !pending,
                false);
        button(
                add,
                detailBounds.x() + half + 4,
                body.y() + 2,
                half,
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
        int count = 7 + keys.size();
        var details = RoutingListLayout.calculateRows(detailBounds, count, detailScroll);
        detailScroll = details.scroll();
        maxDetailScroll = Math.max(0, count - details.visibleRows());
        EditBox firstField = null;
        boolean deleting = state instanceof NetworkTerminalState.PresetEdit edit
                && edit.operation() == PresetEditOperation.REMOVE_RULE;
        for (int row = 0; row < details.visibleRows() && row + detailScroll < count; row++) {
            int index = row + detailScroll;
            var bounds = details.row(row);
            if (index == 2 || index == 4 || index == 5) {
                TerminalEditBox field = new TerminalEditBox(
                        font,
                        bounds.x(),
                        bounds.y(),
                        bounds.width(),
                        20,
                        label(index == 2 ? "selector_value" : index == 4 ? "inventory_slot" : "tank"));
                field.setMaxLength(index == 2 ? 65535 : 10);
                field.setValue(
                        index == 2
                                ? draft.selector == 4
                                        ? library.entries().stream()
                                                .filter(preset -> preset.id().equals(draft.reference))
                                                .map(
                                                        io.github.loongin.omniresonance.networking.FilterPresetSummary
                                                                ::name)
                                                .findFirst()
                                                .orElse(label("selector_4").getString())
                                        : draft.text
                                : index == 4 ? sampleSlot : sampleTank);
                field.setResponder(value -> {
                    if (index == 2) {
                        draft.text = value;
                        draft.dirty = true;
                        changed.accept(value);
                    } else if (index == 4) sampleSlot = value;
                    else sampleTank = value;
                });
                field.active = !pending && !deleting && (index != 2 || draft.selector < 3);
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
                    if (index == 0) {
                        draft.type = draft.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)
                                ? io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID
                                : draft.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.FLUID)
                                        ? io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY
                                        : io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM;
                        draft.source = io.github.loongin.omniresonance.filter.ComponentCondition.idOnly();
                        draft.sampleToken = null;
                        draft.mode = io.github.loongin.omniresonance.filter.ComponentCondition.Mode.ID_ONLY;
                        draft.selected.clear();
                        if (draft.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY))
                            draft.selector = 3;
                    } else if (index == 1) {
                        draft.selector = (draft.selector + 1) % 5;
                    } else if (index == 3)
                        draft.mode = io.github.loongin.omniresonance.filter.ComponentCondition.Mode.values()[
                                (draft.mode.ordinal() + 1) % 3];
                    else if (index == 6) {
                        try {
                            actions.accept(new Action.Sample(
                                    draft.type, Integer.parseInt(sampleSlot), Integer.parseInt(sampleTank)));
                        } catch (IllegalArgumentException invalid) {
                            sampleFailure = "invalid_sample";
                        }
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
                        : label(key);
                TerminalClickButton widget = index == 6
                        ? new TerminalSampleSlot(bounds, message, press, () -> {
                            var player = net.minecraft.client.Minecraft.getInstance().player;
                            try {
                                int slot = Integer.parseInt(sampleSlot);
                                return player == null
                                                || slot < 0
                                                || slot >= player.getInventory().getContainerSize()
                                        ? net.minecraft.world.item.ItemStack.EMPTY
                                        : player.getInventory().getItem(slot);
                            } catch (NumberFormatException invalid) {
                                return net.minecraft.world.item.ItemStack.EMPTY;
                            }
                        })
                        : new TerminalButton(bounds.x(), bounds.y(), bounds.width(), 20, message, press, false);
                widget.active = !pending
                        && !deleting
                        && (index < 7
                                || draft.mode
                                        == io.github.loongin.omniresonance.filter.ComponentCondition.Mode.SELECTED)
                        && (index != 1 && index != 3 && index != 6
                                || !draft.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ENERGY));
                if (index >= 7)
                    widget.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal(componentPreview(draft.available().get(keys.get(index - 7))))));
                add.accept(widget);
            }
        }
        return firstField;
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

    private void renderFull(GuiGraphics graphics, Font font) {
        if (!librarySearch.expanded())
            graphics.drawString(
                    font,
                    TerminalText.body(label("title")),
                    libraryBounds.x(),
                    libraryBounds.y() - 20,
                    TerminalTheme.TEXT,
                    false);
        graphics.drawString(
                font, TerminalText.body(label("rules")), list.x(), list.y() - 20, TerminalTheme.TEXT, false);
        for (TerminalLayout.Rect bounds : java.util.List.of(libraryBounds, list, detailBounds))
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
        add.accept(button);
    }
}
