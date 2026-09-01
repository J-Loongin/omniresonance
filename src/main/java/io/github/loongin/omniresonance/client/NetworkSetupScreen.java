// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalPage;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Current create/select terminal vertical slice backed only by server responses.
 *
 * <p>The client thread owns this screen, its single page and its draft. It never predicts authoritative records or
 * mutates world state. Every modification is an explicit payload; failures remain visible and no request is retried
 * automatically. Closing releases at most the matching server session and does not imply cancellation of an already
 * submitted create intent.
 */
final class NetworkSetupScreen extends Screen {
    private static final int CONTROL_HEIGHT = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int PANEL_HEADING_HEIGHT = 14;
    private static final int PAGE_CONTROLS_HEIGHT = 22;
    private static final int MAX_VISIBLE_DROPDOWN_ROWS = 6;
    private static final int EDIT_BOX_MAXIMUM_UTF16_UNITS = 256;

    private final NetworkTerminalClient client;
    private final boolean retryOnboardingSkipped;
    private final UUID viewId = UUID.randomUUID();

    private TerminalLayout layout = TerminalLayout.calculate(320, 240);
    private TerminalLayout.Rect listBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect detailBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect selectorBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect dropdownBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect modalBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private int crumbLeft;
    private int crumbRight;
    private boolean showEscapeHint;

    private @Nullable UUID sessionId;
    private @Nullable NetworkTerminalPage page;
    private @Nullable NetworkSummary selected;
    private @Nullable Component error;
    private PendingOperation pendingOperation = PendingOperation.NONE;
    private long pendingSequence;
    private long nextSequence = 1;

    private boolean openSent;
    private boolean closeSent;
    private boolean disconnected;
    private boolean createOverlay;
    private boolean firstPrompt;
    private boolean confirmation;
    private boolean dropdownOpen;
    private boolean compactDetails;
    private boolean errorAllowsRetry;
    private boolean settingNameField;
    private String draft = "";
    private TerminalInteractionPolicy.DraftState draftState = TerminalInteractionPolicy.DraftState.clear();
    private int listScroll;
    private int detailScroll;
    private int dropdownScroll;
    private final List<TerminalButton> dropdownButtons = new ArrayList<>();
    private @Nullable EditBox nameField;

    NetworkSetupScreen(NetworkTerminalClient client) {
        this(
                client,
                new TerminalInteractionPolicy.RetrySnapshot(
                        "", TerminalInteractionPolicy.DraftState.clear(), client.firstPromptDismissed()));
    }

    NetworkSetupScreen(NetworkTerminalClient client, TerminalInteractionPolicy.RetrySnapshot retrySnapshot) {
        super(Component.translatable("omniresonance.terminal.title"));
        this.client = Objects.requireNonNull(client, "client");
        TerminalInteractionPolicy.RetrySnapshot snapshot = Objects.requireNonNull(retrySnapshot, "retrySnapshot");
        retryOnboardingSkipped = snapshot.onboardingSkipped();
        draft = snapshot.draft();
        draftState = snapshot.draftState();
    }

    boolean matchesView(UUID candidate) {
        return viewId.equals(candidate) && !disconnected && !closeSent;
    }

    void disconnected() {
        disconnected = true;
        closeSent = true;
        sessionId = null;
        page = null;
        selected = null;
        pendingOperation = PendingOperation.NONE;
    }

    void closeForReplacement() {
        sendCloseOnce();
    }

    TerminalInteractionPolicy.RetrySnapshot retrySnapshot() {
        return TerminalInteractionPolicy.RetrySnapshot.capture(
                draft, draftState, retryOnboardingSkipped || client.firstPromptDismissed());
    }

    void applyResponse(NetworkTerminalResponse response) {
        if (!matchesView(response.viewId())) {
            return;
        }
        if (response.sequence() == 0) {
            applyInitialResponse(response);
            return;
        }
        if (sessionId == null
                || response.sessionId() == null
                || !sessionId.equals(response.sessionId())
                || pendingOperation == PendingOperation.NONE
                || response.sequence() != pendingSequence) {
            return;
        }
        PendingOperation completed = pendingOperation;
        pendingOperation = PendingOperation.NONE;
        if (response instanceof NetworkTerminalResponse.Failure failure) {
            if (completed == PendingOperation.CREATE) {
                applyCreateResult(false);
            }
            error = Component.translatable(failure.reason().translationKey());
            errorAllowsRetry = completed != PendingOperation.CREATE || requiresNewSession(failure.reason());
            rebuildIfActive();
            return;
        }
        NetworkTerminalResponse.Success success = (NetworkTerminalResponse.Success) response;
        page = success.page();
        error = null;
        errorAllowsRetry = false;
        listScroll = 0;
        dropdownScroll = 0;
        if (completed == PendingOperation.CREATE) {
            if (success.created() == null) {
                applyCreateResult(false);
                error = Component.translatable(NetworkTerminalResponse.Reason.INVALID_REQUEST.translationKey());
                errorAllowsRetry = true;
            } else {
                applyCreateResult(true);
                draft = "";
                selected = success.created();
                firstPrompt = false;
                client.dismissFirstPrompt();
            }
        } else if (selected != null && !containsNetwork(page.entries(), selected.id())) {
            selected = null;
            compactDetails = false;
        }
        rebuildIfActive();
    }

    private void applyInitialResponse(NetworkTerminalResponse response) {
        if (!openSent || sessionId != null || pendingOperation != PendingOperation.NONE) {
            return;
        }
        if (response instanceof NetworkTerminalResponse.Failure failure) {
            error = Component.translatable(failure.reason().translationKey());
            errorAllowsRetry = true;
            rebuildIfActive();
            return;
        }
        NetworkTerminalResponse.Success success = (NetworkTerminalResponse.Success) response;
        sessionId = success.sessionId();
        page = success.page();
        selected = page.preferred();
        error = null;
        errorAllowsRetry = false;
        if (draftState.dirty()) {
            createOverlay = true;
            firstPrompt = page.totalCount() == 0 && !onboardingWasSkipped();
        } else if (page.totalCount() == 0 && !onboardingWasSkipped()) {
            firstPrompt = true;
            createOverlay = true;
            draft = Component.translatable("omniresonance.terminal.first_open.suggested_name")
                    .getString();
            draftState = TerminalInteractionPolicy.DraftState.clear();
        }
        rebuildIfActive();
    }

    private void applyCreateResult(boolean success) {
        TerminalInteractionPolicy.CreateResult result = TerminalInteractionPolicy.createResult(draftState, success);
        draftState = result.draftState();
        createOverlay = result.showCreateOverlay();
        confirmation = false;
        if (!success) {
            firstPrompt = page != null && page.totalCount() == 0 && !onboardingWasSkipped();
        }
    }

    private boolean onboardingWasSkipped() {
        return retryOnboardingSkipped || client.firstPromptDismissed();
    }

    @Override
    protected void init() {
        layout = TerminalLayout.calculate(width, height);
        dropdownButtons.clear();
        configureBodyBounds();
        buildTopBar();
        if (page == null) {
            buildLoadingState();
        } else if (!createOverlay) {
            buildDirectoryWidgets();
        }
        if (dropdownOpen && page != null && !createOverlay) {
            buildDropdown();
        }
        nameField = null;
        if (createOverlay && !confirmation) {
            buildCreateOverlay();
        } else if (confirmation) {
            buildConfirmation();
        }
        if (!openSent) {
            openSent = true;
            if (!client.send(new NetworkTerminalRequest.Open(viewId))) {
                error = Component.translatable(NetworkTerminalResponse.Reason.DATA_UNAVAILABLE.translationKey());
                errorAllowsRetry = true;
            }
        }
    }

    @Override
    protected void repositionElements() {
        rebuildWidgets();
    }

    @Override
    protected void setInitialFocus() {
        if (nameField != null && nameField.active) {
            setInitialFocus(nameField);
        } else {
            super.setInitialFocus();
        }
    }

    private void configureBodyBounds() {
        TerminalLayout.Rect content = layout.content();
        if (layout.compact()) {
            if (compactDetails && selected != null) {
                listBounds = new TerminalLayout.Rect(content.x(), content.y(), 0, 0);
                detailBounds = content;
            } else {
                listBounds = content;
                detailBounds = new TerminalLayout.Rect(content.right(), content.y(), 0, 0);
            }
            return;
        }
        int listWidth = Math.max(112, (content.width() - TerminalLayout.GAP) * 38 / 100);
        listWidth = Math.min(listWidth, Math.max(0, content.width() - TerminalLayout.GAP));
        int detailWidth = Math.max(0, content.width() - listWidth - TerminalLayout.GAP);
        listBounds = new TerminalLayout.Rect(content.x(), content.y(), listWidth, content.height());
        detailBounds = new TerminalLayout.Rect(
                content.x() + listWidth + TerminalLayout.GAP, content.y(), detailWidth, content.height());
    }

    private void buildTopBar() {
        TerminalLayout.Rect window = layout.window();
        int y = window.y() + 4;
        int backWidth = layout.compact() ? 48 : 58;
        TerminalButton back = new TerminalButton(
                window.x() + 4,
                y,
                backWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.back"),
                button -> navigateBack(),
                false);
        addRenderableWidget(back);
        crumbLeft = back.getRight() + TerminalLayout.GAP;

        Component escape = Component.translatable("omniresonance.terminal.escape_hint", client.translatedKey());
        showEscapeHint = !layout.compact() && window.width() >= 520;
        int hintWidth = showEscapeHint ? Math.min(112, font.width(escape) + 4) : 0;
        int right = window.right() - 4 - hintWidth;
        if (showEscapeHint) {
            right -= TerminalLayout.GAP;
        }

        selectorBounds = new TerminalLayout.Rect(0, 0, 0, 0);
        if (page != null) {
            int selectorWidth = layout.compact() ? 72 : 110;
            int selectorX = right - selectorWidth;
            selectorBounds = new TerminalLayout.Rect(selectorX, y, selectorWidth, CONTROL_HEIGHT);
            String selectorText = selected == null
                    ? Component.translatable("omniresonance.terminal.no_selection")
                            .getString()
                    : selected.name();
            TerminalButton selector = new TerminalButton(
                    selectorX,
                    y,
                    selectorWidth,
                    CONTROL_HEIGHT,
                    Component.literal(ellipsize(selectorText, selectorWidth - 8)),
                    button -> {
                        if (!createOverlay && pendingOperation == PendingOperation.NONE) {
                            dropdownOpen = !dropdownOpen;
                            rebuildIfActive();
                        }
                    },
                    false);
            selector.active = !createOverlay && !page.entries().isEmpty();
            selector.setSelected(dropdownOpen);
            selector.setTooltip(Tooltip.create(Component.literal(selectorText)));
            addRenderableWidget(selector);
            right = selectorX - TerminalLayout.GAP;

            int createWidth = layout.compact() ? 50 : 62;
            TerminalButton create = new TerminalButton(
                    right - createWidth,
                    y,
                    createWidth,
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.terminal.create"),
                    button -> openCreateOverlay(false),
                    true);
            create.active = !createOverlay && sessionId != null && pendingOperation == PendingOperation.NONE;
            addRenderableWidget(create);
            right = create.getX() - TerminalLayout.GAP;
        }
        crumbRight = Math.max(crumbLeft, right);
    }

    private void buildLoadingState() {
        if (error != null && errorAllowsRetry) {
            int buttonWidth = 80;
            TerminalLayout.Rect content = layout.content();
            TerminalButton retry = new TerminalButton(
                    content.x() + (content.width() - buttonWidth) / 2,
                    content.y() + content.height() / 2 + 18,
                    buttonWidth,
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.terminal.retry"),
                    button -> client.retry(this),
                    true);
            addRenderableWidget(retry);
        }
    }

    private void buildDirectoryWidgets() {
        if (listBounds.width() > 0) {
            List<NetworkSummary> entries = page.entries();
            int visibleRows = visibleListRows();
            listScroll = clampScroll(listScroll, entries.size(), visibleRows);
            int firstY = listBounds.y() + PANEL_HEADING_HEIGHT;
            int availableWidth = Math.max(0, listBounds.width() - TerminalLayout.SCROLLBAR_WIDTH - 4);
            for (int row = 0; row < visibleRows; row++) {
                int index = listScroll + row;
                if (index >= entries.size()) {
                    break;
                }
                NetworkSummary summary = entries.get(index);
                TerminalButton entry = new TerminalButton(
                        listBounds.x() + 2,
                        firstY + row * ROW_HEIGHT,
                        availableWidth,
                        CONTROL_HEIGHT,
                        Component.literal(ellipsize(summary.name(), Math.max(0, availableWidth - 8))),
                        button -> select(summary),
                        false);
                entry.setSelected(selected != null && selected.id().equals(summary.id()));
                entry.setTooltip(Tooltip.create(Component.literal(summary.name())));
                addRenderableWidget(entry);
            }
            int pageY = listBounds.bottom() - CONTROL_HEIGHT - 2;
            int half = Math.max(0, (availableWidth - TerminalLayout.GAP) / 2);
            TerminalButton previous = new TerminalButton(
                    listBounds.x() + 2,
                    pageY,
                    half,
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.terminal.previous"),
                    button -> requestPage(true),
                    false);
            previous.active = page.hasPrevious() && pendingOperation == PendingOperation.NONE;
            addRenderableWidget(previous);
            TerminalButton next = new TerminalButton(
                    listBounds.x() + 2 + half + TerminalLayout.GAP,
                    pageY,
                    half,
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.terminal.next"),
                    button -> requestPage(false),
                    false);
            next.active = page.hasNext() && pendingOperation == PendingOperation.NONE;
            addRenderableWidget(next);
        }
        if (page.entries().isEmpty()) {
            TerminalLayout.Rect content = layout.content();
            int createWidth = 104;
            TerminalButton emptyCreate = new TerminalButton(
                    content.x() + (content.width() - createWidth) / 2,
                    content.y() + content.height() / 2 + 24,
                    createWidth,
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.terminal.create"),
                    button -> openCreateOverlay(false),
                    true);
            emptyCreate.active = sessionId != null && pendingOperation == PendingOperation.NONE;
            addRenderableWidget(emptyCreate);
        }
        if (error != null && errorAllowsRetry) {
            int retryWidth = 72;
            TerminalButton retry = new TerminalButton(
                    layout.content().right() - retryWidth - 4,
                    layout.content().bottom() - CONTROL_HEIGHT - 4,
                    retryWidth,
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.terminal.retry"),
                    button -> client.retry(this),
                    true);
            addRenderableWidget(retry);
        }
    }

    private void buildDropdown() {
        List<NetworkSummary> entries = page.entries();
        int visibleRows = Math.min(MAX_VISIBLE_DROPDOWN_ROWS, entries.size());
        dropdownScroll = clampScroll(dropdownScroll, entries.size(), visibleRows);
        int height = Math.max(1, visibleRows * CONTROL_HEIGHT + 4);
        dropdownBounds = new TerminalLayout.Rect(
                selectorBounds.x(), selectorBounds.bottom() + 1, selectorBounds.width(), height);
        for (int row = 0; row < visibleRows; row++) {
            NetworkSummary summary = entries.get(dropdownScroll + row);
            TerminalButton entry = new TerminalButton(
                    dropdownBounds.x() + 2,
                    dropdownBounds.y() + 2 + row * CONTROL_HEIGHT,
                    Math.max(0, dropdownBounds.width() - 4),
                    CONTROL_HEIGHT,
                    Component.literal(ellipsize(summary.name(), Math.max(0, dropdownBounds.width() - 12))),
                    button -> select(summary),
                    false);
            entry.setSelected(selected != null && selected.id().equals(summary.id()));
            entry.setTooltip(Tooltip.create(Component.literal(summary.name())));
            addRenderableWidget(entry);
            dropdownButtons.add(entry);
        }
    }

    private void openCreateOverlay(boolean onboarding) {
        if (sessionId == null || pendingOperation != PendingOperation.NONE) {
            return;
        }
        dropdownOpen = false;
        createOverlay = true;
        firstPrompt = onboarding;
        confirmation = false;
        if (onboarding) {
            draft = Component.translatable("omniresonance.terminal.first_open.suggested_name")
                    .getString();
            draftState = TerminalInteractionPolicy.DraftState.clear();
            error = null;
            errorAllowsRetry = false;
        } else if (!draftState.dirty()) {
            draft = "";
            draftState = TerminalInteractionPolicy.DraftState.clear();
            error = null;
            errorAllowsRetry = false;
        }
        rebuildIfActive();
    }

    private void buildCreateOverlay() {
        TerminalLayout.Rect content = layout.content();
        int modalWidth = Math.min(320, Math.max(0, content.width() - 8));
        int modalHeight = Math.min(firstPrompt ? 132 : 120, Math.max(0, content.height() - 4));
        modalBounds = centered(content, modalWidth, modalHeight);
        int fieldX = modalBounds.x() + 8;
        int fieldY = modalBounds.y() + 48;
        int fieldWidth = Math.max(0, modalBounds.width() - 16);
        EditBox field = new EditBox(
                font,
                fieldX,
                fieldY,
                fieldWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.name"));
        field.setMaxLength(EDIT_BOX_MAXIMUM_UTF16_UNITS);
        field.setResponder(this::updateDraft);
        settingNameField = true;
        field.setValue(draft);
        settingNameField = false;
        field.setEditable(!draftState.createPending());
        nameField = addRenderableWidget(field);

        int actionY = modalBounds.bottom() - CONTROL_HEIGHT - 8;
        int available = Math.max(0, modalBounds.width() - 16);
        int secondaryWidth = Math.max(0, (available - TerminalLayout.GAP) / 2);
        TerminalButton secondary = new TerminalButton(
                modalBounds.x() + 8,
                actionY,
                secondaryWidth,
                CONTROL_HEIGHT,
                Component.translatable(
                        firstPrompt ? "omniresonance.terminal.first_open.skip" : "omniresonance.terminal.back"),
                button -> {
                    if (firstPrompt) {
                        client.dismissFirstPrompt();
                        firstPrompt = false;
                        createOverlay = false;
                        draftState = TerminalInteractionPolicy.DraftState.clear();
                        draft = "";
                        rebuildIfActive();
                    } else {
                        navigateBack();
                    }
                },
                false);
        secondary.active = !firstPrompt || !draftState.createPending();
        addRenderableWidget(secondary);

        TerminalButton submit = new TerminalButton(
                modalBounds.x() + 8 + secondaryWidth + TerminalLayout.GAP,
                actionY,
                secondaryWidth,
                CONTROL_HEIGHT,
                Component.translatable(
                        errorAllowsRetry
                                ? "omniresonance.terminal.retry"
                                : draftState.createPending()
                                        ? "omniresonance.terminal.create.pending"
                                        : "omniresonance.terminal.create.submit"),
                button -> {
                    if (errorAllowsRetry) {
                        client.retry(this);
                    } else {
                        submitCreate();
                    }
                },
                true);
        submit.active = !draftState.createPending();
        addRenderableWidget(submit);
    }

    private void buildConfirmation() {
        TerminalLayout.Rect content = layout.content();
        int modalWidth = Math.min(320, Math.max(0, content.width() - 8));
        int modalHeight = Math.min(102, Math.max(0, content.height() - 4));
        modalBounds = centered(content, modalWidth, modalHeight);
        int actionY = modalBounds.bottom() - CONTROL_HEIGHT - 8;
        int available = Math.max(0, modalBounds.width() - 16 - TerminalLayout.GAP * 2);
        int buttonWidth = available / 3;
        TerminalButton create = new TerminalButton(
                modalBounds.x() + 8,
                actionY,
                buttonWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.confirm.create"),
                button -> {
                    confirmation = false;
                    submitCreate();
                },
                true);
        addRenderableWidget(create);
        TerminalButton discard = new TerminalButton(
                modalBounds.x() + 8 + buttonWidth + TerminalLayout.GAP,
                actionY,
                buttonWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.confirm.discard"),
                button -> discardDraft(),
                false);
        addRenderableWidget(discard);
        TerminalButton keepEditing = new TerminalButton(
                modalBounds.x() + 8 + (buttonWidth + TerminalLayout.GAP) * 2,
                actionY,
                buttonWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.confirm.continue"),
                button -> {
                    confirmation = false;
                    rebuildIfActive();
                },
                false);
        addRenderableWidget(keepEditing);
    }

    private void updateDraft(String value) {
        if (settingNameField) {
            return;
        }
        if (!draft.equals(value)) {
            draft = value;
            draftState = draftState.edited();
            error = null;
            errorAllowsRetry = false;
        }
    }

    private void submitCreate() {
        if (pendingOperation != PendingOperation.NONE || sessionId == null) {
            return;
        }
        String canonical;
        try {
            canonical = new ManagedName(draft).value();
        } catch (IllegalArgumentException invalidName) {
            confirmation = false;
            error = Component.translatable(NetworkTerminalResponse.Reason.INVALID_NAME.translationKey());
            errorAllowsRetry = false;
            rebuildIfActive();
            return;
        }
        long sequence = nextSequence;
        NetworkTerminalRequest.Create request =
                new NetworkTerminalRequest.Create(viewId, sessionId, sequence, canonical);
        if (!client.send(request)) {
            error = Component.translatable(NetworkTerminalResponse.Reason.DATA_UNAVAILABLE.translationKey());
            errorAllowsRetry = true;
            rebuildIfActive();
            return;
        }
        nextSequence++;
        pendingSequence = sequence;
        pendingOperation = PendingOperation.CREATE;
        draftState = draftState.submitted();
        error = null;
        errorAllowsRetry = false;
        rebuildIfActive();
    }

    private void requestPage(boolean backwards) {
        if (pendingOperation != PendingOperation.NONE
                || sessionId == null
                || page == null
                || page.entries().isEmpty()) {
            return;
        }
        List<NetworkSummary> entries = page.entries();
        UUID anchor = backwards ? entries.getFirst().id() : entries.getLast().id();
        long sequence = nextSequence;
        NetworkTerminalRequest.Page request =
                new NetworkTerminalRequest.Page(viewId, sessionId, sequence, anchor, backwards);
        if (!client.send(request)) {
            error = Component.translatable(NetworkTerminalResponse.Reason.DATA_UNAVAILABLE.translationKey());
            errorAllowsRetry = true;
            rebuildIfActive();
            return;
        }
        nextSequence++;
        pendingSequence = sequence;
        pendingOperation = PendingOperation.PAGE;
        error = null;
        errorAllowsRetry = false;
        dropdownOpen = false;
        rebuildIfActive();
    }

    private void select(NetworkSummary summary) {
        selected = summary;
        dropdownOpen = false;
        if (layout.compact()) {
            compactDetails = true;
        }
        detailScroll = 0;
        rebuildIfActive();
    }

    private void navigateBack() {
        TerminalInteractionPolicy.BackAction action = TerminalInteractionPolicy.backAction(
                dropdownOpen, confirmation, createOverlay, draftState, layout.compact() && compactDetails);
        switch (action) {
            case CLOSE_DROPDOWN -> dropdownOpen = false;
            case CLOSE_CONFIRMATION -> confirmation = false;
            case HIDE_CREATE_OVERLAY -> {
                createOverlay = false;
                firstPrompt = false;
            }
            case CONFIRM_DRAFT -> confirmation = true;
            case SHOW_COMPACT_LIST -> compactDetails = false;
            case CLOSE_SCREEN -> {
                closeRoot();
                return;
            }
        }
        rebuildIfActive();
    }

    private void discardDraft() {
        confirmation = false;
        createOverlay = false;
        firstPrompt = false;
        draftState = TerminalInteractionPolicy.DraftState.clear();
        draft = "";
        error = null;
        errorAllowsRetry = false;
        rebuildIfActive();
    }

    private void requestShortcutClose() {
        if (confirmation) {
            return;
        }
        if (createOverlay && draftState.requiresConfirmation()) {
            confirmation = true;
            rebuildIfActive();
            return;
        }
        closeRoot();
    }

    private void closeRoot() {
        sendCloseOnce();
        if (minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(null);
        }
    }

    private void sendCloseOnce() {
        if (closeSent) {
            return;
        }
        closeSent = true;
        if (!disconnected && sessionId != null) {
            client.send(new NetworkTerminalRequest.Close(viewId, sessionId));
        }
    }

    @Override
    public void removed() {
        sendCloseOnce();
    }

    @Override
    public void onClose() {
        navigateBack();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            navigateBack();
            return true;
        }
        boolean bindingMatches = client.isTerminalKey(keyCode, scanCode);
        boolean canConsumeInput = nameField != null && nameField.canConsumeInput();
        String printableKeyName = bindingMatches ? GLFW.glfwGetKeyName(keyCode, scanCode) : null;
        if (TerminalInteractionPolicy.prioritizeTextInput(
                canConsumeInput, bindingMatches, printableKeyName, modifiers)) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (bindingMatches) {
            requestShortcutClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!dropdownOpen) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (contains(selectorBounds, mouseX, mouseY)) {
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (handled && !dropdownOpen) {
                setFocused(null);
                setDragging(false);
            }
            return handled;
        }
        if (contains(dropdownBounds, mouseX, mouseY)) {
            for (TerminalButton dropdownButton : dropdownButtons) {
                if (dropdownButton.mouseClicked(mouseX, mouseY, button)) {
                    if (dropdownOpen) {
                        setFocused(dropdownButton);
                        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                            setDragging(true);
                        }
                    }
                    return true;
                }
            }
            return true;
        }
        dropdownOpen = false;
        rebuildIfActive();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dropdownOpen && contains(dropdownBounds, mouseX, mouseY) && page != null) {
            int visibleRows = Math.min(MAX_VISIBLE_DROPDOWN_ROWS, page.entries().size());
            dropdownScroll = scrollBy(dropdownScroll, scrollY, page.entries().size(), visibleRows);
            rebuildIfActive();
            return true;
        }
        if (!createOverlay && page != null && contains(listBounds, mouseX, mouseY)) {
            listScroll = scrollBy(listScroll, scrollY, page.entries().size(), visibleListRows());
            rebuildIfActive();
            return true;
        }
        if (!createOverlay && selected != null && contains(detailBounds, mouseX, mouseY)) {
            int visibleHeight = Math.max(0, detailBounds.height() - PANEL_HEADING_HEIGHT - 8);
            int maximumScroll = Math.max(0, (90 - visibleHeight + 9) / 10);
            detailScroll = Math.max(0, Math.min(maximumScroll, detailScroll + (scrollY < 0 ? 1 : -1)));
            rebuildIfActive();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, TerminalTheme.WORLD_DIM);
        TerminalTheme.renderWindow(graphics, layout);
        renderTopBar(graphics);
        renderBody(graphics);
        if (dropdownOpen && page != null && !createOverlay) {
            TerminalTheme.renderPanel(graphics, dropdownBounds);
            TerminalTheme.renderScrollbar(
                    graphics,
                    dropdownBounds.right() - TerminalLayout.SCROLLBAR_WIDTH,
                    dropdownBounds.y() + 2,
                    Math.max(0, dropdownBounds.height() - 4),
                    page.entries().size(),
                    Math.min(MAX_VISIBLE_DROPDOWN_ROWS, page.entries().size()),
                    dropdownScroll);
        }
        if (createOverlay && !confirmation) {
            renderCreateOverlay(graphics);
        }
        if (confirmation) {
            renderConfirmation(graphics);
        }
    }

    private void renderTopBar(GuiGraphics graphics) {
        String crumb = page == null
                ? Component.translatable("omniresonance.terminal.loading").getString()
                : createOverlay
                        ? Component.translatable("omniresonance.terminal.create")
                                .getString()
                        : selected == null ? "" : selected.name();
        String titleText = getTitle().getString() + (crumb.isEmpty() ? "" : " / " + crumb);
        graphics.drawString(
                font,
                ellipsize(titleText, Math.max(0, crumbRight - crumbLeft)),
                crumbLeft,
                layout.titleBar().y() + 10,
                TerminalTheme.TEXT,
                false);
        if (showEscapeHint) {
            Component hint = Component.translatable("omniresonance.terminal.escape_hint", client.translatedKey());
            graphics.drawString(
                    font,
                    hint,
                    layout.window().right() - 4 - font.width(hint),
                    layout.titleBar().y() + 10,
                    TerminalTheme.MUTED,
                    false);
        }
    }

    private void renderBody(GuiGraphics graphics) {
        TerminalLayout.Rect content = layout.content();
        if (page == null) {
            TerminalTheme.renderPanel(graphics, content);
            Component message = error == null ? Component.translatable("omniresonance.terminal.loading") : error;
            int color = error == null ? TerminalTheme.MUTED : TerminalTheme.ERROR;
            drawCenteredWrapped(graphics, message, content, color, -10);
            return;
        }
        if (page.entries().isEmpty()) {
            TerminalTheme.renderPanel(graphics, content);
            Component title = Component.translatable("omniresonance.terminal.empty.title");
            Component message = Component.translatable("omniresonance.terminal.empty.message");
            int centerY = content.y() + content.height() / 2 - 16;
            graphics.drawCenteredString(font, title, content.x() + content.width() / 2, centerY, TerminalTheme.TEXT);
            drawCenteredWrapped(graphics, message, content, TerminalTheme.MUTED, 0);
        } else {
            if (listBounds.width() > 0) {
                renderNetworkList(graphics);
            }
            if (detailBounds.width() > 0) {
                renderDetails(graphics);
            }
        }
        if (error != null && !createOverlay) {
            graphics.drawString(
                    font,
                    ellipsize(error.getString(), Math.max(0, content.width() - 84)),
                    content.x() + 4,
                    content.bottom() - 14,
                    TerminalTheme.ERROR,
                    false);
        }
    }

    private void renderNetworkList(GuiGraphics graphics) {
        TerminalTheme.renderPanel(graphics, listBounds);
        graphics.drawString(
                font,
                Component.translatable("omniresonance.terminal.networks"),
                listBounds.x() + 4,
                listBounds.y() + 3,
                TerminalTheme.TEXT,
                false);
        int visibleRows = visibleListRows();
        TerminalTheme.renderScrollbar(
                graphics,
                listBounds.right() - TerminalLayout.SCROLLBAR_WIDTH - 1,
                listBounds.y() + PANEL_HEADING_HEIGHT,
                Math.max(0, listBounds.height() - PANEL_HEADING_HEIGHT - PAGE_CONTROLS_HEIGHT),
                page.entries().size(),
                visibleRows,
                listScroll);
    }

    private void renderDetails(GuiGraphics graphics) {
        TerminalTheme.renderPanel(graphics, detailBounds);
        graphics.drawString(
                font,
                Component.translatable("omniresonance.terminal.details"),
                detailBounds.x() + 4,
                detailBounds.y() + 3,
                TerminalTheme.TEXT,
                false);
        if (selected == null) {
            drawCenteredWrapped(
                    graphics,
                    Component.translatable("omniresonance.terminal.no_selection"),
                    detailBounds,
                    TerminalTheme.MUTED,
                    0);
            return;
        }
        int x = detailBounds.x() + 8;
        int y = detailBounds.y() + 20 - detailScroll * 10;
        int maximumWidth = Math.max(0, detailBounds.width() - 16);
        graphics.enableScissor(
                detailBounds.x() + 1,
                detailBounds.y() + PANEL_HEADING_HEIGHT,
                detailBounds.right() - 1,
                detailBounds.bottom() - 1);
        graphics.drawString(font, ellipsize(selected.name(), maximumWidth), x, y, TerminalTheme.TEXT, false);
        boolean owner = minecraft != null
                && minecraft.player != null
                && minecraft.player.getUUID().equals(selected.ownerId());
        Component role = Component.translatable(
                owner ? "omniresonance.terminal.role.owner" : "omniresonance.terminal.role.managed");
        graphics.drawString(font, role, x, y + 15, TerminalTheme.MUTED, false);
        graphics.drawString(
                font, Component.translatable("omniresonance.terminal.owner"), x, y + 34, TerminalTheme.MUTED, false);
        graphics.drawString(font, selected.ownerId().toString(), x, y + 46, TerminalTheme.TEXT, false);
        graphics.drawString(
                font,
                Component.translatable("omniresonance.terminal.network_id"),
                x,
                y + 65,
                TerminalTheme.MUTED,
                false);
        graphics.drawString(font, selected.id().toString(), x, y + 77, TerminalTheme.TEXT, false);
        graphics.disableScissor();
    }

    private void renderCreateOverlay(GuiGraphics graphics) {
        TerminalLayout.Rect content = layout.content();
        graphics.fill(content.x(), content.y(), content.right(), content.bottom(), 0xB000070C);
        TerminalTheme.renderPanel(graphics, modalBounds);
        Component title = Component.translatable(
                firstPrompt ? "omniresonance.terminal.first_open.title" : "omniresonance.terminal.create");
        Component message = Component.translatable(
                firstPrompt ? "omniresonance.terminal.first_open.message" : "omniresonance.terminal.name");
        graphics.drawString(font, title, modalBounds.x() + 8, modalBounds.y() + 8, TerminalTheme.TEXT, false);
        graphics.drawString(
                font,
                ellipsize(message.getString(), Math.max(0, modalBounds.width() - 16)),
                modalBounds.x() + 8,
                modalBounds.y() + 24,
                TerminalTheme.MUTED,
                false);
        if (error != null) {
            graphics.drawString(
                    font,
                    ellipsize(error.getString(), Math.max(0, modalBounds.width() - 16)),
                    modalBounds.x() + 8,
                    modalBounds.y() + 72,
                    TerminalTheme.ERROR,
                    false);
        }
    }

    private void renderConfirmation(GuiGraphics graphics) {
        TerminalLayout.Rect content = layout.content();
        graphics.fill(content.x(), content.y(), content.right(), content.bottom(), 0xC000070C);
        TerminalTheme.renderPanel(graphics, modalBounds);
        graphics.drawString(
                font,
                Component.translatable("omniresonance.terminal.confirm.title"),
                modalBounds.x() + 8,
                modalBounds.y() + 8,
                TerminalTheme.TEXT,
                false);
        Component message = Component.translatable("omniresonance.terminal.confirm.message");
        drawWrapped(
                graphics,
                message,
                modalBounds.x() + 8,
                modalBounds.y() + 24,
                modalBounds.width() - 16,
                TerminalTheme.MUTED,
                2);
    }

    private void drawCenteredWrapped(
            GuiGraphics graphics, Component component, TerminalLayout.Rect bounds, int color, int yOffset) {
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(component, Math.max(1, bounds.width() - 16));
        int y = bounds.y() + bounds.height() / 2 + yOffset;
        for (int index = 0; index < Math.min(3, lines.size()); index++) {
            graphics.drawCenteredString(font, lines.get(index), bounds.x() + bounds.width() / 2, y + index * 11, color);
        }
    }

    private void drawWrapped(
            GuiGraphics graphics, Component component, int x, int y, int width, int color, int maximumLines) {
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(component, Math.max(1, width));
        for (int index = 0; index < Math.min(maximumLines, lines.size()); index++) {
            graphics.drawString(font, lines.get(index), x, y + index * 11, color, false);
        }
    }

    private int visibleListRows() {
        if (listBounds.height() <= PANEL_HEADING_HEIGHT + PAGE_CONTROLS_HEIGHT) {
            return 1;
        }
        return Math.max(1, (listBounds.height() - PANEL_HEADING_HEIGHT - PAGE_CONTROLS_HEIGHT) / ROW_HEIGHT);
    }

    private String ellipsize(String text, int maximumWidth) {
        if (maximumWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maximumWidth) {
            return text;
        }
        String ellipsis = "…";
        int textWidth = Math.max(0, maximumWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, textWidth) + ellipsis;
    }

    private void rebuildIfActive() {
        if (minecraft != null && minecraft.screen == this) {
            rebuildWidgets();
        }
    }

    private static TerminalLayout.Rect centered(TerminalLayout.Rect parent, int width, int height) {
        return new TerminalLayout.Rect(
                parent.x() + Math.floorDiv(parent.width() - width, 2),
                parent.y() + Math.floorDiv(parent.height() - height, 2),
                width,
                height);
    }

    private static int clampScroll(int value, int total, int visible) {
        return Math.max(0, Math.min(value, Math.max(0, total - visible)));
    }

    private static int scrollBy(int value, double scrollY, int total, int visible) {
        if (scrollY == 0) {
            return value;
        }
        return clampScroll(value + (scrollY < 0 ? 1 : -1), total, visible);
    }

    private static boolean contains(TerminalLayout.Rect bounds, double x, double y) {
        return bounds.width() > 0
                && bounds.height() > 0
                && x >= bounds.x()
                && y >= bounds.y()
                && x < bounds.right()
                && y < bounds.bottom();
    }

    private static boolean containsNetwork(List<NetworkSummary> entries, UUID networkId) {
        for (NetworkSummary entry : entries) {
            if (entry.id().equals(networkId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresNewSession(NetworkTerminalResponse.Reason reason) {
        return reason == NetworkTerminalResponse.Reason.SESSION_EXPIRED
                || reason == NetworkTerminalResponse.Reason.STALE_REQUEST
                || reason == NetworkTerminalResponse.Reason.INTERNAL_ERROR;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum PendingOperation {
        NONE,
        PAGE,
        CREATE
    }
}
