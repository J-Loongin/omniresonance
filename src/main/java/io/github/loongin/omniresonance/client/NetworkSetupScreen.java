// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.networking.ChannelPage;
import io.github.loongin.omniresonance.networking.ChannelSummary;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalPage;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.TopologyDeletionSummary;
import io.github.loongin.omniresonance.networking.TunnelPage;
import io.github.loongin.omniresonance.networking.TunnelSummary;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
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
    private static final int MAX_VISIBLE_DROPDOWN_ROWS = 6;
    private static final int EDIT_BOX_MAXIMUM_UTF16_UNITS = 256;

    private final NetworkTerminalClient client;
    private final ModalBackdrop modalBackdrop = new ModalBackdrop();
    private final boolean retryOnboardingSkipped;
    private final UUID viewId = UUID.randomUUID();

    private TerminalLayout layout = TerminalLayout.calculate(320, 240);
    private TerminalLayout.Rect listBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect detailBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect selectorBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private final TerminalNetworkContext networkContext = new TerminalNetworkContext();
    private @Nullable DomainInventoryView inventoryView;
    private long inventoryGeneration;

    void invalidateInventoryMetadata() {
        if (inventoryView != null) inventoryView.invalidateMetadata();
    }

    void receiveStorage(io.github.loongin.omniresonance.networking.TerminalStorageResponse response) {
        if (inventoryView != null) inventoryView.accept(response);
    }

    void receiveInventory(io.github.loongin.omniresonance.networking.DomainInventoryFrame frame) {
        if (inventoryView != null) inventoryView.accept(frame);
    }

    private void openInventory() {
        if (!(topologyState instanceof NetworkTerminalState.NetworkRoot)
                || sessionId == null
                || pendingOperation != PendingOperation.NONE) return;
        networkContext.readOnly(true);
        inventoryView = new DomainInventoryView(this::retryInventory);
        inventoryView.bindStorage(viewId, client::sendStorage);
        retryInventory();
        rebuildIfActive();
    }

    private void retryInventory() {
        if (inventoryView == null || sessionId == null) return;
        inventoryGeneration = Math.incrementExact(inventoryGeneration);
        inventoryView.request(sessionId, inventoryGeneration);
        if (!client.sendInventory(new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                viewId, sessionId, inventoryGeneration, true))) {
            inventoryView.accept(new io.github.loongin.omniresonance.networking.DomainInventoryFrame.Failed(
                    sessionId,
                    inventoryGeneration,
                    0,
                    io.github.loongin.omniresonance.networking.DomainInventoryFrame.Reason.UNAVAILABLE));
        }
    }

    private void closeInventory() {
        if (inventoryView == null) return;
        if (sessionId != null)
            client.sendInventory(new io.github.loongin.omniresonance.networking.DomainInventoryRequest(
                    viewId, sessionId, inventoryGeneration, false));
        inventoryView.close();
        inventoryView = null;
        networkContext.readOnly(false);
    }

    private TerminalLayout.Rect createBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect modalBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private int crumbLeft;
    private int crumbRight;
    private int escapeHintRight;
    private int escapeHintWidth;
    private boolean showEscapeHint;

    private @Nullable UUID sessionId;
    private @Nullable NetworkTerminalPage page;
    private @Nullable NetworkSummary selected;
    private @Nullable NetworkTerminalState topologyState;
    private @Nullable Component error;
    private PendingOperation pendingOperation = PendingOperation.NONE;
    private boolean pendingDraftSubmitted;
    private PagedListScroll.PageRequest pendingPageRequest = PagedListScroll.PageRequest.NONE;
    private long pendingSequence;
    private long nextSequence = 1;
    private long heartbeatSequence;
    private long clientTicks;
    private long lastTopologyHeartbeatTick;

    private boolean openSent;
    private boolean closeSent;
    private boolean disconnected;
    private boolean createOverlay;
    private boolean firstPrompt;
    private boolean confirmation;
    private boolean compactDetails;
    private boolean errorAllowsRetry;
    private boolean settingNameField;
    private boolean topologyDraftDirty;
    private boolean topologyDiscardConfirmation;
    private boolean closeAfterDiscard;
    private AutomaticNameCommit automaticNameCommit = AutomaticNameCommit.idle();
    private String draft = "";
    private String topologyDraft = "";
    private TerminalInteractionPolicy.DraftState draftState = TerminalInteractionPolicy.DraftState.clear();
    private int listScroll;
    private int detailScroll;
    private int dropdownScroll;
    private @Nullable EditBox nameField;
    private final TerminalMemberView memberView = new TerminalMemberView(this, this::rebuildIfActive);
    private boolean memberBackPending;
    private final io.github.loongin.omniresonance.networking.ManagementDownloadAssembler filterDownload =
            new io.github.loongin.omniresonance.networking.ManagementDownloadAssembler();
    private @Nullable NetworkTerminalResponse.RuleTransferReady filterTransfer;
    private @Nullable io.github.loongin.omniresonance.filter.ResourceRuleIntent uploadIntent;
    private byte @Nullable [] uploadBytes;
    private int filterTransferOffset;
    private long filterTransferStartedTick;
    private @Nullable TerminalFilterView.Action.SaveFull pendingFilterSave;
    private final TerminalFilterView filterView = new TerminalFilterView(this::rebuildIfActive);

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
        closeInventory();
        clearFilterTransfer();
        filterDownload.close();
        disconnected = true;
        closeSent = true;
        sessionId = null;
        page = null;
        selected = null;
        topologyState = null;
        networkContext.apply(topologyState);
        filterView.apply(null);
        memberView.reset();
        memberBackPending = false;
        pendingOperation = PendingOperation.NONE;
        pendingPageRequest = PagedListScroll.PageRequest.NONE;
        automaticNameCommit = AutomaticNameCommit.idle();
    }

    void closeForReplacement() {
        sendCloseOnce();
    }

    TerminalInteractionPolicy.RetrySnapshot retrySnapshot() {
        return TerminalInteractionPolicy.RetrySnapshot.capture(
                draft, draftState, retryOnboardingSkipped || client.firstPromptDismissed());
    }

    static void applyFilterResponse(TerminalFilterView filters, NetworkTerminalResponse response) {
        if (response instanceof NetworkTerminalResponse.ViewState view) filters.apply(view.state());
        else if (response instanceof NetworkTerminalResponse.Failure failure) filters.requestFailed(failure.state());
    }

    void applyResponse(NetworkTerminalResponse response) {
        if (!matchesView(response.viewId())) {
            return;
        }
        if (response instanceof NetworkTerminalResponse.AccessRevoked revoked) {
            if (sessionId != null && sessionId.equals(revoked.sessionId())) {
                disconnected();
                if (minecraft != null && minecraft.screen == this) {
                    minecraft.setScreen(null);
                    if (minecraft.player != null)
                        minecraft.player.displayClientMessage(
                                Component.translatable("omniresonance.terminal.members.access_revoked"), false);
                }
            }
            return;
        }
        if (response instanceof NetworkTerminalResponse.NetworkDeleted deleted) {
            if (sessionId != null && sessionId.equals(deleted.sessionId())) {
                disconnected();
                if (minecraft != null && minecraft.screen == this) {
                    minecraft.setScreen(null);
                    if (minecraft.player != null)
                        minecraft.player.displayClientMessage(
                                Component.translatable("omniresonance.terminal.settings.network_deleted"), false);
                }
            }
            return;
        }
        if (response.sequence() == 0) {
            applyInitialResponse(response);
            return;
        }
        if (sessionId == null
                || response.sessionId() == null
                || !sessionId.equals(response.sessionId())
                || (response.sequence() != pendingSequence && response.sequence() != heartbeatSequence)
                || (pendingOperation == PendingOperation.NONE && response.sequence() != heartbeatSequence)) {
            return;
        }
        if (response instanceof NetworkTerminalResponse.RuleTransferReady ready) {
            if (filterTransfer != null) return;
            filterTransfer = ready;
            filterTransferOffset = 0;
            filterTransferStartedTick = clientTicks;
            try {
                if (ready.upload()) {
                    uploadBytes = io.github.loongin.omniresonance.networking.FullFilterCodec.intent(
                            Objects.requireNonNull(uploadIntent));
                    if (uploadBytes.length != ready.length())
                        throw new IllegalArgumentException("Upload length changed");
                } else {
                    var pin = new io.github.loongin.omniresonance.networking.ManagementDownloadAssembler.Expected(
                            ready.sessionId(),
                            ready.transferId(),
                            io.github.loongin.omniresonance.networking.ManagementTransferMessage.Context.TERMINAL,
                            viewId,
                            io.github.loongin.omniresonance.networking.ManagementTransferMessage.Purpose.RULE_SNAPSHOT,
                            ready.length());
                    filterDownload.begin(
                            new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Begin(
                                    ready.sessionId(),
                                    ready.transferId(),
                                    io.github.loongin.omniresonance.networking.ManagementTransferMessage.Direction
                                            .DOWNLOAD,
                                    pin.context(),
                                    pin.contextId(),
                                    pin.purpose(),
                                    pin.totalLength()),
                            pin,
                            clientTicks);
                }
            } catch (RuntimeException failure) {
                failFilterTransfer();
            }
            return;
        }
        boolean heartbeatResponse = response.sequence() == heartbeatSequence && response.sequence() != pendingSequence;
        PendingOperation completed = heartbeatResponse ? PendingOperation.NONE : pendingOperation;
        PagedListScroll.PageRequest completedPage =
                heartbeatResponse ? PagedListScroll.PageRequest.NONE : pendingPageRequest;
        if (!heartbeatResponse) {
            if (filterTransfer != null) clearFilterTransfer();
            pendingOperation = PendingOperation.NONE;
            pendingDraftSubmitted = false;
            pendingPageRequest = PagedListScroll.PageRequest.NONE;
        }
        if (response.sequence() == heartbeatSequence) {
            heartbeatSequence = 0;
        }
        if (!heartbeatResponse) applyFilterResponse(filterView, response);
        if (response instanceof NetworkTerminalResponse.FilterLibrary library) {
            filterView.acceptLibrary(library);
            rebuildIfActive();
            return;
        }
        if (response instanceof NetworkTerminalResponse.FullRule full) {
            clearFilterTransfer();
            try {
                filterView.acceptFull(full);
            } catch (RuntimeException invalid) {
                error = Component.translatable(NetworkTerminalResponse.Reason.INVALID_REQUEST.translationKey());
            }
            rebuildIfActive();
            return;
        }
        if (response instanceof NetworkTerminalResponse.Failure failure) {
            clearFilterTransfer();
            automaticNameCommit =
                    automaticNameCommit.resolveTerminal(false, failure.state()).next();
            if (completed == PendingOperation.CREATE) {
                applyCreateResult(false);
            }
            error = Component.translatable(failure.reason().translationKey());
            if (failure.state() != null) {
                if (closeAfterDiscard
                        && topologyDiscardConfirmation
                        && !TerminalInteractionPolicy.sameEditor(topologyState, failure.state())) {
                    topologyDiscardConfirmation = false;
                    closeAfterDiscard = false;
                }
                TopologyDraft failedDraft = resolveFailedTopologyDraft(
                        filterView, topologyState, failure.state(), topologyDraft, topologyDraftDirty);
                topologyDraft = failedDraft.value();
                topologyDraftDirty = failedDraft.dirty();
                topologyState = failure.state();
                networkContext.apply(topologyState);
                memberView.apply(topologyState);
                if (topologyState instanceof NetworkTerminalState.AdministratorCandidates) memberView.notice(error);
                if (continueMemberCandidates()) return;
            } else if (topologyState instanceof NetworkTerminalState.AdministratorCandidates) {
                memberView.requestFailed();
                if (memberBackPending && continueMemberCandidates()) return;
            }
            errorAllowsRetry = completed != PendingOperation.CREATE || requiresNewSession(failure.reason());
            rebuildIfActive();
            return;
        }
        if (response instanceof NetworkTerminalResponse.ViewState view) {
            NetworkTerminalState previous = topologyState;
            topologyState = view.state();
            networkContext.apply(topologyState);
            memberView.apply(topologyState);
            if (completedPage != PagedListScroll.PageRequest.NONE) {
                listScroll = pageLanding(completedPage);
            } else if (previous == null || previous.getClass() != topologyState.getClass()) {
                listScroll = 0;
            }
            boolean preserveClosingDraft = closeAfterDiscard
                    && topologyDiscardConfirmation
                    && TerminalInteractionPolicy.sameEditor(previous, topologyState);
            if (!preserveClosingDraft) initializeTopologyDraft(previous, topologyState);
            AutomaticNameCommit.Resolution automatic = automaticNameCommit.resolveTerminal(true, topologyState);
            automaticNameCommit = automatic.next();
            error = null;
            errorAllowsRetry = false;
            if (!preserveClosingDraft) {
                topologyDiscardConfirmation = false;
                closeAfterDiscard = false;
            }
            if (automatic.commit() == AutomaticNameCommit.Target.TUNNEL) {
                submitTopologyName();
                return;
            }
            if (continueMemberCandidates()) return;
            rebuildIfActive();
            return;
        }
        NetworkTerminalResponse.Success success = (NetworkTerminalResponse.Success) response;
        topologyState = null;
        networkContext.apply(topologyState);
        filterView.apply(null);
        memberView.reset();
        memberBackPending = false;
        page = success.page();
        error = null;
        errorAllowsRetry = false;
        listScroll = completedPage == PagedListScroll.PageRequest.NONE ? 0 : pageLanding(completedPage);
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
        if (TerminalInteractionPolicy.enterNetworkHome(
                selected != null, createOverlay, pendingOperation != PendingOperation.NONE)) {
            openSelectedHome();
        } else {
            rebuildIfActive();
        }
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
        if (TerminalInteractionPolicy.enterNetworkHome(
                selected != null, createOverlay, pendingOperation != PendingOperation.NONE)) {
            openSelectedHome();
        } else {
            rebuildIfActive();
        }
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
        font = TerminalText.font(Objects.requireNonNull(minecraft, "minecraft"));
        layout = inventoryView == null
                ? TerminalLayout.calculate(width, height)
                : DomainInventoryLayout.window(width, height);
        networkContext.apply(topologyState);
        networkContext.buttons.clear();
        modalBackdrop.clear();
        configureBodyBounds();
        buildTopBar();
        nameField = null;
        if (topologyState != null) {
            buildTopologyWidgets();
            if (topologyDiscardConfirmation) {
                modalBackdrop.retain(children(), this::removeWidget);
                setFocused(null);
                buildTopologyDiscardConfirmation();
            }
        } else {
            if (page == null) {
                buildLoadingState();
            } else if (!createOverlay) {
                buildDirectoryWidgets();
            }
            if (createOverlay) {
                buildCreateOverlay();
                createBounds = modalBounds;
            }
            if (confirmation) {
                modalBackdrop.retain(children(), this::removeWidget);
                setFocused(null);
                buildConfirmation();
            }
        }
        if (networkContext.intercepts()
                && page != null
                && !createOverlay
                && !confirmation
                && !topologyDiscardConfirmation) {
            buildDropdown();
        }
        if (confirmation || topologyDiscardConfirmation) modalBackdrop.captureForeground(renderables);
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
        if (confirmation || topologyDiscardConfirmation) {
            super.setInitialFocus();
        } else if (memberView.searchField() != null && memberView.searchField().active) {
            setInitialFocus(memberView.searchField());
        } else if (filterView.searchField() != null && filterView.searchField().active) {
            setInitialFocus(filterView.searchField());
        } else if (nameField != null && nameField.active) {
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
        TerminalLayout.Rect topBar = TerminalHeaderLayout.topBarContent(window);
        int y = topBar.y();
        crumbLeft = topBar.x();

        int right = topBar.right();
        TerminalHeaderLayout.Action action = inventoryView != null || filterView.managementOpen()
                ? TerminalHeaderLayout.Action.NONE
                : TerminalInteractionPolicy.topBarAction(
                        page != null,
                        topologyState,
                        minecraft != null && minecraft.player != null ? minecraft.player.getUUID() : null);
        TerminalHeaderLayout.ActionLayout header =
                TerminalHeaderLayout.atRightEdge(topBar, action != TerminalHeaderLayout.Action.NONE);
        if (action != TerminalHeaderLayout.Action.NONE) buildTopBarAction(action, header.action());
        right = header.remaining().right();

        Component escape = Component.translatable("omniresonance.terminal.escape_hint", client.translatedKey());
        showEscapeHint = !layout.compact() && window.width() >= 520;
        escapeHintRight = right;
        escapeHintWidth = showEscapeHint ? Math.min(112, font.width(escape) + 4) : 0;
        right -= escapeHintWidth;
        if (showEscapeHint) {
            right -= TerminalLayout.GAP;
        }

        selectorBounds = new TerminalLayout.Rect(0, 0, 0, 0);
        if (page != null && networkContext.network() != null) {
            selectorBounds = TerminalNetworkContext.layout(
                    new TerminalLayout.Rect(crumbLeft, y, Math.max(0, right - crumbLeft), CONTROL_HEIGHT),
                    layout.compact(),
                    networkContext.selectable());
            TerminalButton selector = networkContext.buildSelector(
                    selectorBounds,
                    TerminalText.networkLabel(
                            networkContext.network().name(),
                            selectorBounds,
                            candidate -> font.width(TerminalText.body(Component.literal(candidate)))),
                    !createOverlay
                            && !confirmation
                            && !topologyDiscardConfirmation
                            && pendingOperation == PendingOperation.NONE
                            && !page.entries().isEmpty(),
                    this::rebuildIfActive);
            if (selector != null && inventoryView == null) addRenderableWidget(selector);
            right = selectorBounds.x() - TerminalLayout.GAP;
        }
        crumbRight = Math.max(crumbLeft, right);
    }

    private void buildTopBarAction(TerminalHeaderLayout.Action action, TerminalLayout.Rect bounds) {
        TerminalClickButton button;
        if (action == TerminalHeaderLayout.Action.CREATE) {
            TerminalInteractionPolicy.CreateTarget target = TerminalInteractionPolicy.createTarget(true, topologyState);
            button = buildCreateButton(
                    bounds,
                    target,
                    () -> openCreateOverlay(false),
                    this::beginAutomaticTunnelCreate,
                    () -> sendMemberAction(new TerminalMemberView.Action.OpenCandidates()),
                    this::runFilterAction);
        } else if (action == TerminalHeaderLayout.Action.SEARCH) {
            button = new TerminalSearchButton(
                    bounds,
                    memberView.expanded(),
                    Component.translatable(
                            memberView.expanded()
                                    ? "omniresonance.node_menu.tunnel.search.close"
                                    : "omniresonance.terminal.members.search"),
                    ignored -> memberView.toggleSearch());
        } else {
            button = buildSettingsButton(
                    bounds,
                    topologyState,
                    filterView::openManagement,
                    () -> sendTopology(sequence ->
                            new NetworkTerminalRequest.OpenTunnelSettings(viewId, requireSessionId(), sequence)));
        }
        button.active = !createOverlay
                && !confirmation
                && !topologyDiscardConfirmation
                && sessionId != null
                && pendingOperation == PendingOperation.NONE;
        if (action == TerminalHeaderLayout.Action.SEARCH) button.active &= memberView.candidatesReady();
        if (topologyState instanceof NetworkTerminalState.Members members) {
            boolean atQuota = members.administratorLimit() != -1
                    && members.page().totalCount() - 1 >= members.administratorLimit();
            button.active &= !atQuota && members.page().totalCount() <= 262144;
            if (atQuota)
                button.setTooltip(Tooltip.create(
                        TerminalText.body(Component.translatable("omniresonance.terminal.error.quota_reached"))));
        }
        addRenderableWidget(button);
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
                TerminalRowButton entry = new TerminalRowButton(
                        listBounds.x() + 2,
                        firstY + row * ROW_HEIGHT,
                        availableWidth,
                        CONTROL_HEIGHT,
                        Component.literal(summary.name()),
                        button -> select(summary));
                entry.setSelected(selected != null && selected.id().equals(summary.id()));
                entry.setTooltip(Tooltip.create(TerminalText.body(Component.literal(summary.name()))));
                addRenderableWidget(entry);
            }
        }
        if (selected != null && detailBounds.width() > 0) {
            TerminalButton manage = new TerminalButton(
                    detailBounds.x() + 6,
                    detailBounds.bottom() - CONTROL_HEIGHT - 6,
                    Math.max(0, detailBounds.width() - 12),
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.terminal.topology.manage"),
                    button -> sendTopology(sequence -> new NetworkTerminalRequest.OpenNetwork(
                            viewId, requireSessionId(), sequence, selected.id())),
                    true);
            manage.active = pendingOperation == PendingOperation.NONE;
            addRenderableWidget(manage);
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
        networkContext.bounds = new TerminalLayout.Rect(
                selectorBounds.x(), selectorBounds.bottom() + 1, selectorBounds.width(), height);
        for (int row = 0; row < visibleRows; row++) {
            NetworkSummary summary = entries.get(dropdownScroll + row);
            TerminalButton entry = new TerminalButton(
                    networkContext.bounds.x() + 2,
                    networkContext.bounds.y() + 2 + row * CONTROL_HEIGHT,
                    Math.max(0, networkContext.bounds.width() - 4),
                    CONTROL_HEIGHT,
                    Component.literal(ellipsize(summary.name(), Math.max(0, networkContext.bounds.width() - 12))),
                    button -> {
                        if (networkContext.intercepts()) select(summary);
                    },
                    false);
            entry.setSelected(selected != null && selected.id().equals(summary.id()));
            entry.setTooltip(Tooltip.create(TerminalText.body(Component.literal(summary.name()))));
            addRenderableWidget(entry);
            networkContext.buttons.add(entry);
        }
    }

    private void openCreateOverlay(boolean onboarding) {
        if (sessionId == null || pendingOperation != PendingOperation.NONE) {
            return;
        }
        networkContext.open = false;
        topologyState = null;
        networkContext.apply(topologyState);
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
        modalBounds = TerminalDialogLayout.editor(content);
        int fieldX = modalBounds.x() + 8;
        int fieldY = modalBounds.y() + 48;
        int fieldWidth = Math.max(0, modalBounds.width() - 16);
        EditBox field = new TerminalEditBox(
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

        var footer = TerminalActionLayout.of(modalBounds);
        int actionY = footer.primary().y();
        int secondaryWidth = footer.primary().width();
        TerminalButton secondary = new TerminalButton(
                footer.secondary().x(),
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
                footer.primary().x(),
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
        modalBounds = TerminalDialogLayout.confirmation(
                content, font, Component.translatable("omniresonance.terminal.confirm.message"));
        int actionY = modalBounds.bottom() - CONTROL_HEIGHT - 8;
        int buttonWidth = TerminalActionLayout.button(modalBounds, 3, 0).width();
        TerminalButton create = new TerminalButton(
                TerminalActionLayout.button(modalBounds, 3, 2).x(),
                actionY,
                buttonWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.confirm.create"),
                button -> {
                    confirmation = false;
                    submitCreate();
                },
                true);
        TerminalButton discard = new TerminalButton(
                TerminalActionLayout.button(modalBounds, 3, 1).x(),
                actionY,
                buttonWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.confirm.discard"),
                button -> discardDraft(),
                false);
        TerminalButton keepEditing = new TerminalButton(
                TerminalActionLayout.button(modalBounds, 3, 0).x(),
                actionY,
                buttonWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.confirm.continue"),
                button -> {
                    confirmation = false;
                    closeAfterDiscard = false;
                    rebuildIfActive();
                },
                false);
        addRenderableWidget(keepEditing);
        addRenderableWidget(discard);
        addRenderableWidget(create);
    }

    private void buildTopologyWidgets() {
        TerminalLayout.Rect content = layout.content();
        if (inventoryView != null) {
            nameField = inventoryView.build(font, content, this::addRenderableWidget, this::removeWidget);
            return;
        }
        if (automaticNameCommit.suppressEditor()) {
            return;
        }
        switch (topologyState) {
            case NetworkTerminalState.Filters filters -> buildFilterView(filters);
            case NetworkTerminalState.Preset preset -> buildFilterView(preset);
            case NetworkTerminalState.PresetEdit edit -> buildFilterView(edit);
            case NetworkTerminalState.NetworkSettings settings -> buildNetworkSettingsView(settings);
            case NetworkTerminalState.NetworkRename rename -> buildNetworkSettingsView(rename);
            case NetworkTerminalState.NetworkDelete delete -> buildNetworkSettingsView(delete);
            case NetworkTerminalState.Members ignored -> buildMemberView();
            case NetworkTerminalState.AdministratorCandidates ignored -> buildMemberView();
            case NetworkTerminalState.RemoveAdministrator ignored -> buildMemberView();
            case NetworkTerminalState.NetworkRoot root -> buildNetworkHome();
            case NetworkTerminalState.TunnelList list -> buildTunnelList(list);
            case NetworkTerminalState.ChannelList list -> buildChannelList(list);
            case NetworkTerminalState.TunnelEdit edit ->
                buildTopologyNameEdit(
                        edit.existing() == null,
                        edit.existing() == null
                                ? edit.suggestedName()
                                : edit.existing().name());
            case NetworkTerminalState.DeleteConfirmation ignored -> buildTopologyDeleteConfirmation();
            case NetworkTerminalState.TunnelSettings settings -> buildTunnelSettings(settings);
        }
    }

    private void buildNetworkHome() {
        List<TerminalLayout.Rect> cards = TerminalHomeLayout.cards(layout.content(), layout.compact());
        String[] modules = {"nodes", "tunnels", "domain", "filters", "loading", "admins", "status", "settings"};
        for (int index = 0; index < modules.length; index++) {
            String module = modules[index];
            boolean domainUnavailable = index == 2
                    && topologyState instanceof NetworkTerminalState.NetworkRoot root
                    && root.domainUnavailable();
            boolean implemented = index == 1 || index == 2 || index == 3 || index == 5 || index == 7;
            TerminalCardButton card = new TerminalCardButton(
                    cards.get(index),
                    Component.translatable("omniresonance.terminal.home." + module + ".mark"),
                    Component.translatable("omniresonance.terminal.home." + module),
                    Component.translatable(
                            domainUnavailable
                                    ? "omniresonance.terminal.home.domain.unavailable"
                                    : index == 2
                                            ? "omniresonance.inventory.home_meta"
                                            : index == 3
                                                    ? "omniresonance.terminal.filters.home_meta"
                                                    : index == 1
                                                            ? "omniresonance.terminal.home.tunnels.meta"
                                                            : index == 5
                                                                    ? "omniresonance.terminal.home.admins.meta"
                                                                    : index == 7
                                                                            ? "omniresonance.terminal.home.settings.meta"
                                                                            : "omniresonance.terminal.home.unavailable"),
                    ignored -> {
                        if (module.equals("domain")) {
                            openInventory();
                            return;
                        }
                        if (implemented) {
                            sendTopology(sequence -> module.equals("admins")
                                    ? new NetworkTerminalRequest.OpenMembers(viewId, requireSessionId(), sequence)
                                    : module.equals("filters")
                                            ? new NetworkTerminalRequest.OpenFilters(
                                                    viewId, requireSessionId(), sequence)
                                            : module.equals("settings")
                                                    ? new NetworkTerminalRequest.OpenNetworkSettings(
                                                            viewId, requireSessionId(), sequence)
                                                    : new NetworkTerminalRequest.OpenTunnels(
                                                            viewId, requireSessionId(), sequence));
                        }
                    });
            card.active = implemented && pendingOperation == PendingOperation.NONE;
            if (!implemented || domainUnavailable) {
                card.setTooltip(Tooltip.create(TerminalText.body(Component.translatable(
                        domainUnavailable
                                ? "omniresonance.domain_status.storage_unavailable"
                                : "omniresonance.terminal.home.unavailable"))));
            }
            addRenderableWidget(card);
        }
    }

    static TerminalSettingsButton buildSettingsButton(
            TerminalLayout.Rect bounds,
            @Nullable NetworkTerminalState state,
            Runnable presetManagement,
            Runnable tunnelManagement) {
        boolean preset = state instanceof NetworkTerminalState.Preset;
        return new TerminalSettingsButton(
                bounds,
                Component.translatable(
                        preset ? "omniresonance.terminal.filters.manage" : "omniresonance.terminal.tunnel.manage"),
                ignored -> {
                    if (preset) presetManagement.run();
                    else tunnelManagement.run();
                });
    }

    private void buildFilterView(NetworkTerminalState state) {
        nameField = filterView.build(
                font,
                layout,
                state,
                topologyDraft,
                pendingOperation != PendingOperation.NONE,
                this::addRenderableWidget,
                value -> {
                    if (!topologyDraft.equals(value)) {
                        topologyDraft = value;
                        topologyDraftDirty = true;
                        error = null;
                    }
                },
                this::runFilterAction);
    }

    static NetworkTerminalRequest presetEditRequest(
            TerminalFilterView.Action action, UUID viewId, UUID sessionId, long sequence, String draft) {
        return switch (action) {
            case TerminalFilterView.Action.Begin begin ->
                new NetworkTerminalRequest.BeginPresetEdit(
                        viewId, sessionId, sequence, begin.operation(), begin.id(), begin.originalRule());
            case TerminalFilterView.Action.Save ignored ->
                new NetworkTerminalRequest.SavePresetEdit(viewId, sessionId, sequence, draft);
            default -> throw new IllegalArgumentException("Not a preset edit action");
        };
    }

    static NetworkTerminalRequest resourceRuleRequest(
            TerminalFilterView.Action action, UUID view, UUID session, long sequence) {
        return switch (action) {
            case TerminalFilterView.Action.Read read ->
                new NetworkTerminalRequest.ReadResourceRule(
                        view, session, sequence, read.preset(), read.revision(), read.rule());
            case TerminalFilterView.Action.BeginFull begin ->
                new NetworkTerminalRequest.BeginResourceRule(
                        view, session, sequence, begin.preset(), begin.rule(), begin.remove());
            case TerminalFilterView.Action.Sample sample ->
                new NetworkTerminalRequest.SampleResourceRule(
                        view, session, sequence, sample.type(), sample.slot(), sample.tank());
            case TerminalFilterView.Action.Query query ->
                new NetworkTerminalRequest.QueryFilterLibrary(
                        view, session, sequence, query.query(), query.offset(), query.revision());
            case TerminalFilterView.Action.SaveFull save ->
                new NetworkTerminalRequest.SaveResourceRule(view, session, sequence, save.intent());
            default -> throw new IllegalArgumentException("Not a resource rule action");
        };
    }

    private void runFilterAction(TerminalFilterView.Action action) {
        switch (action) {
            case TerminalFilterView.Action.Query query ->
                sendTopology(sequence -> resourceRuleRequest(query, viewId, requireSessionId(), sequence));
            case TerminalFilterView.Action.Read read ->
                sendTopology(sequence -> resourceRuleRequest(read, viewId, requireSessionId(), sequence));
            case TerminalFilterView.Action.BeginFull begin ->
                sendTopology(sequence -> resourceRuleRequest(begin, viewId, requireSessionId(), sequence));
            case TerminalFilterView.Action.SaveFull save -> {
                if (topologyState instanceof NetworkTerminalState.PresetEdit edit
                        && (edit.impact().bindingCount() > 0
                                || edit.operation()
                                        == io.github.loongin.omniresonance.filter.PresetEditOperation.REMOVE_RULE)) {
                    pendingFilterSave = save;
                    topologyDiscardConfirmation = true;
                    closeAfterDiscard = false;
                    rebuildIfActive();
                } else submitResourceRule(save);
            }
            case TerminalFilterView.Action.Sample sample ->
                sendTopology(sequence -> resourceRuleRequest(sample, viewId, requireSessionId(), sequence));
            case TerminalFilterView.Action.Page page ->
                sendTopology(sequence ->
                        new NetworkTerminalRequest.PagePresets(viewId, requireSessionId(), sequence, page.offset()));
            case TerminalFilterView.Action.Open open ->
                sendTopology(sequence -> new NetworkTerminalRequest.OpenPreset(
                        viewId, requireSessionId(), sequence, open.id(), open.revision(), open.offset()));
            case TerminalFilterView.Action.Begin begin ->
                sendTopology(sequence -> presetEditRequest(begin, viewId, requireSessionId(), sequence, topologyDraft));
            case TerminalFilterView.Action.Save ignored -> {
                try {
                    sendTopology(
                            sequence -> presetEditRequest(action, viewId, requireSessionId(), sequence, topologyDraft));
                } catch (IllegalArgumentException invalidDraft) {
                    error = Component.translatable(NetworkTerminalResponse.Reason.INVALID_REQUEST.translationKey());
                    rebuildIfActive();
                }
            }
            case TerminalFilterView.Action.Cancel ignored -> navigateTopologyBack();
            case TerminalFilterView.Action.CopyRule copy -> {
                if (minecraft != null) minecraft.keyboardHandler.setClipboard(copy.value());
            }
        }
    }

    private void submitResourceRule(TerminalFilterView.Action.SaveFull save) {

        int size = save.intent() == null
                ? 0
                : io.github.loongin.omniresonance.networking.FullFilterCodec.intentSize(save.intent());
        if (save.intent() == null || io.github.loongin.omniresonance.networking.FullFilterCodec.intentFitsPacket(size))
            sendTopology(sequence -> resourceRuleRequest(save, viewId, requireSessionId(), sequence));
        else {
            uploadIntent = save.intent();
            UUID transfer = UUID.randomUUID();
            sendTopology(sequence -> new NetworkTerminalRequest.PrepareResourceRuleUpload(
                    viewId, requireSessionId(), sequence, transfer, size));
        }
    }

    void receiveFilterTransfer(io.github.loongin.omniresonance.networking.ManagementTransferMessage message) {
        NetworkTerminalResponse.RuleTransferReady active = filterTransfer;
        if (active == null
                || active.upload()
                || !active.sessionId().equals(message.session())
                || !active.transferId().equals(message.transfer())
                || closeSent) return;
        try {
            if (!(message instanceof io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk chunk))
                throw new IllegalArgumentException("Unexpected filter fragment");
            if (!filterDownload.append(chunk, clientTicks)) return;
            filterTransferOffset += chunk.data().length;
            if (filterTransferOffset == active.length()) {
                byte[][] data = new byte[1][];
                filterDownload.finish(
                        new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                                active.sessionId(), active.transferId()),
                        clientTicks,
                        object -> {
                            byte[] bytes = new byte[object.length()];
                            for (int i = 0; i < bytes.length; i++) bytes[i] = object.byteAt(i);
                            data[0] = bytes;
                        });
                applyResponse(new NetworkTerminalResponse.FullRule(
                        viewId,
                        active.sessionId(),
                        active.sequence(),
                        active.sampleToken(),
                        active.tanks(),
                        active.tank(),
                        "",
                        data[0]));
            }
        } catch (RuntimeException failure) {
            failFilterTransfer();
        }
    }

    private void tickFilterTransfer() {
        NetworkTerminalResponse.RuleTransferReady active = filterTransfer;
        if (active == null) return;
        if (clientTicks - filterTransferStartedTick >= 200) {
            failFilterTransfer();
            return;
        }
        if (!active.upload() || uploadBytes == null) return;
        FilterUploadProgress progress = advanceFilterUpload(
                active,
                uploadBytes,
                filterTransferOffset,
                topologyDiscardConfirmation,
                closeSent,
                net.neoforged.neoforge.network.PacketDistributor::sendToServer,
                () -> pendingDraftSubmitted = true);
        filterTransferOffset = progress.offset();
        if (progress.finished()) uploadBytes = null;
    }

    record FilterUploadProgress(int offset, boolean finished) {}

    static FilterUploadProgress advanceFilterUpload(
            NetworkTerminalResponse.RuleTransferReady active,
            byte[] bytes,
            int offset,
            boolean discardDecisionOpen,
            boolean closed,
            java.util.function.Consumer<io.github.loongin.omniresonance.networking.ManagementTransferMessage> sender,
            Runnable submitted) {
        if (discardDecisionOpen || closed) return new FilterUploadProgress(offset, false);
        int end = Math.min(
                bytes.length,
                offset + io.github.loongin.omniresonance.networking.ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES);
        if (offset < end)
            sender.accept(new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Chunk(
                    active.sessionId(), active.transferId(), offset, java.util.Arrays.copyOfRange(bytes, offset, end)));
        boolean finished = end == bytes.length;
        if (finished) {
            submitted.run();
            sender.accept(new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Finish(
                    active.sessionId(), active.transferId()));
        }
        return new FilterUploadProgress(end, finished);
    }

    private void failFilterTransfer() {
        NetworkTerminalResponse.RuleTransferReady active = filterTransfer;
        if (active == null) return;
        clearFilterTransfer();
        applyResponse(new NetworkTerminalResponse.Failure(
                viewId, active.sessionId(), active.sequence(), NetworkTerminalResponse.Reason.INVALID_REQUEST));
    }

    private void clearFilterTransfer() {
        NetworkTerminalResponse.RuleTransferReady active = filterTransfer;
        filterTransfer = null;
        uploadIntent = null;
        uploadBytes = null;
        if (active != null) {
            filterDownload.abort(active.sessionId(), active.transferId());
            if (client.canSend())
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new io.github.loongin.omniresonance.networking.ManagementTransferMessage.Abort(
                                active.sessionId(), active.transferId()));
        }
    }

    private void buildNetworkSettingsView(NetworkTerminalState state) {
        nameField = TerminalNetworkSettingsView.build(
                font,
                layout,
                state,
                topologyDraft,
                pendingOperation != PendingOperation.NONE,
                this::addRenderableWidget,
                value -> {
                    if (!topologyDraft.equals(value)) {
                        topologyDraft = value;
                        topologyDraftDirty = true;
                        error = null;
                    }
                },
                this::runNetworkSettingsAction);
    }

    private void runNetworkSettingsAction(TerminalNetworkSettingsView.Action action) {
        switch (action) {
            case BEGIN_RENAME ->
                sendTopology(sequence ->
                        new NetworkTerminalRequest.BeginRenameNetwork(viewId, requireSessionId(), sequence));
            case SET_DEFAULT ->
                sendTopology(
                        sequence -> new NetworkTerminalRequest.SetDefaultNetwork(viewId, requireSessionId(), sequence));
            case REQUEST_DELETE ->
                sendTopology(sequence ->
                        new NetworkTerminalRequest.RequestDeleteNetwork(viewId, requireSessionId(), sequence));
            case SAVE_RENAME -> submitTopologyName();
            case CANCEL_RENAME -> navigateTopologyBack();
            case CONFIRM_DELETE ->
                sendTopology(sequence ->
                        new NetworkTerminalRequest.ConfirmDeleteNetwork(viewId, requireSessionId(), sequence));
            case CANCEL_DELETE -> sendTopologyBack();
        }
    }

    private void buildTunnelList(NetworkTerminalState.TunnelList state) {
        TunnelPage page = state.page();
        RoutingListLayout listLayout = topologyListLayout(page.entries().size());
        listScroll = listLayout.scroll();
        for (int row = 0; row < listLayout.visibleRows(); row++) {
            int index = listScroll + row;
            if (index >= page.entries().size()) {
                break;
            }
            TunnelSummary tunnel = page.entries().get(index);
            String label = Component.translatable(
                            "omniresonance.terminal.tunnel.summary",
                            tunnel.name(),
                            tunnel.enabled()
                                    ? Component.translatable("omniresonance.terminal.tunnel.enabled")
                                    : Component.translatable("omniresonance.terminal.tunnel.disabled"),
                            tunnel.channelCount(),
                            tunnel.bindingCount())
                    .getString();
            TerminalRowButton entry = new TerminalRowButton(
                    listLayout.row(row),
                    Component.literal(label),
                    button -> sendTopology(sequence -> new NetworkTerminalRequest.OpenChannels(
                            viewId, requireSessionId(), sequence, tunnel.tunnelId())));
            entry.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label))));
            entry.active = pendingOperation == PendingOperation.NONE;
            addRenderableWidget(entry);
        }
    }

    private void buildChannelList(NetworkTerminalState.ChannelList state) {
        ChannelPage page = state.page();
        RoutingListLayout listLayout = topologyListLayout(page.entries().size());
        listScroll = listLayout.scroll();
        for (int row = 0; row < listLayout.visibleRows(); row++) {
            int index = listScroll + row;
            if (index >= page.entries().size()) {
                break;
            }
            ChannelSummary channel = page.entries().get(index);
            String label = Component.translatable(
                            "omniresonance.terminal.channel.summary",
                            channel.name(),
                            channel.inputCount(),
                            channel.outputCount())
                    .getString();
            TerminalRowButton entry =
                    new TerminalRowButton(listLayout.row(row), Component.literal(label), button -> {});
            entry.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label))));
            entry.setReadOnly();
            addRenderableWidget(entry);
        }
    }

    private void buildTunnelSettings(NetworkTerminalState.TunnelSettings state) {
        TunnelSummary tunnel = state.tunnel();
        int width = Math.min(420, Math.max(0, layout.content().width() - 24));
        int left = layout.content().x() + (layout.content().width() - width) / 2;
        int third = Math.max(0, (width - TerminalLayout.GAP * 2) / 3);
        int y = layout.content().y() + 56;
        addTopologyButton(
                left,
                y,
                third,
                "omniresonance.terminal.rename",
                () -> sendTopology(sequence -> new NetworkTerminalRequest.BeginRenameTunnel(
                        viewId, requireSessionId(), sequence, tunnel.tunnelId())),
                false,
                true);
        addTopologyButton(
                left + third + TerminalLayout.GAP,
                y,
                third,
                tunnel.enabled() ? "omniresonance.terminal.disable" : "omniresonance.terminal.enable",
                () -> sendTopology(sequence -> new NetworkTerminalRequest.SetTunnelEnabled(
                        viewId, requireSessionId(), sequence, tunnel.tunnelId(), !tunnel.enabled())),
                false,
                true);
        addTopologyButton(
                left + (third + TerminalLayout.GAP) * 2,
                y,
                third,
                "omniresonance.terminal.delete",
                () -> sendTopology(sequence -> new NetworkTerminalRequest.RequestDeleteTunnel(
                        viewId, requireSessionId(), sequence, tunnel.tunnelId())),
                false,
                true);
    }

    private void buildTopologyNameEdit(boolean creating, String initial) {
        if (topologyDraft.isEmpty() && !topologyDraftDirty) {
            topologyDraft = initial;
        }
        var dialog = TerminalDialogLayout.editor(layout.content());
        int width = dialog.width() - 24;
        int x = dialog.x() + 12;
        int y = dialog.y() + 52;
        EditBox field = new TerminalEditBox(
                font, x, y, width, CONTROL_HEIGHT, Component.translatable("omniresonance.terminal.object_name"));
        field.setMaxLength(EDIT_BOX_MAXIMUM_UTF16_UNITS);
        settingNameField = true;
        field.setValue(topologyDraft);
        settingNameField = false;
        field.setResponder(value -> {
            if (!settingNameField && !topologyDraft.equals(value)) {
                topologyDraft = value;
                topologyDraftDirty = true;
                error = null;
            }
        });
        field.active = pendingOperation == PendingOperation.NONE;
        nameField = addRenderableWidget(field);
        var footer = TerminalActionLayout.of(dialog);
        int half = footer.primary().width();
        addTopologyButton(
                footer.secondary().x(),
                footer.secondary().y(),
                half,
                "omniresonance.terminal.cancel",
                this::navigateTopologyBack,
                false,
                true);
        addTopologyButton(
                footer.primary().x(),
                footer.primary().y(),
                half,
                creating ? "omniresonance.terminal.create" : "omniresonance.terminal.save",
                this::submitTopologyName,
                true,
                true);
    }

    private void buildTopologyDeleteConfirmation() {
        var summary = ((NetworkTerminalState.DeleteConfirmation) topologyState).summary();
        modalBounds = TerminalDialogLayout.confirmation(
                layout.content(),
                font,
                Component.translatable(
                        "omniresonance.terminal.delete.impact", summary.channelCount(), summary.bindingCount()));
        var footer = TerminalActionLayout.of(modalBounds);
        int half = footer.primary().width();
        int y = modalBounds.bottom() - CONTROL_HEIGHT - 8;
        addTopologyButton(
                footer.secondary().x(), y, half, "omniresonance.terminal.cancel", this::sendTopologyBack, false, true);
        addTopologyButton(
                footer.primary().x(),
                y,
                half,
                "omniresonance.terminal.delete.confirm",
                () -> sendTopology(
                        sequence -> new NetworkTerminalRequest.ConfirmDelete(viewId, requireSessionId(), sequence)),
                true,
                true);
    }

    private void buildTopologyDiscardConfirmation() {
        if (pendingFilterSave != null) {
            var edit = (NetworkTerminalState.PresetEdit) topologyState;
            modalBounds = TerminalDialogLayout.confirmation(
                    layout.content(),
                    font,
                    Component.translatable(
                            "omniresonance.terminal.filters.impact",
                            edit.impact().networkCount(),
                            edit.impact().nodeCount(),
                            edit.impact().bindingCount()));
            var footer = TerminalActionLayout.of(modalBounds);
            int half = footer.primary().width(), y = modalBounds.bottom() - CONTROL_HEIGHT - 8;
            addTopologyButton(
                    footer.secondary().x(),
                    y,
                    half,
                    "omniresonance.terminal.cancel",
                    () -> {
                        pendingFilterSave = null;
                        topologyDiscardConfirmation = false;
                        rebuildIfActive();
                    },
                    false,
                    true);
            addTopologyButton(
                    footer.primary().x(),
                    y,
                    half,
                    "omniresonance.terminal.filters.save",
                    () -> {
                        var save = Objects.requireNonNull(pendingFilterSave);
                        pendingFilterSave = null;
                        topologyDiscardConfirmation = false;
                        submitResourceRule(save);
                    },
                    true,
                    true);
            return;
        }
        modalBounds = TerminalDialogLayout.confirmation(
                layout.content(), font, Component.translatable("omniresonance.terminal.topology.discard.message"));
        var footer = TerminalActionLayout.of(modalBounds);
        int half = footer.primary().width();
        int y = modalBounds.bottom() - CONTROL_HEIGHT - 8;
        addTopologyButton(
                        footer.secondary().x(),
                        y,
                        half,
                        "omniresonance.terminal.confirm.continue",
                        () -> {
                            topologyDiscardConfirmation = false;
                            closeAfterDiscard = false;
                            rebuildIfActive();
                        },
                        false,
                        true)
                .active = true;
        addTopologyButton(
                        footer.primary().x(),
                        y,
                        half,
                        "omniresonance.terminal.confirm.discard",
                        () -> {
                            topologyDiscardConfirmation = false;
                            topologyDraftDirty = false;
                            if (closeAfterDiscard) closeRoot();
                            else sendTopologyBack();
                        },
                        true,
                        true)
                .active = true;
    }

    private TerminalButton addTopologyButton(
            int x, int y, int width, String key, Runnable action, boolean primary, boolean active) {
        TerminalButton button = new TerminalButton(
                x,
                y,
                Math.max(0, width),
                CONTROL_HEIGHT,
                Component.translatable(key),
                ignored -> action.run(),
                primary);
        button.active = active && pendingOperation == PendingOperation.NONE;
        addRenderableWidget(button);
        return button;
    }

    private void pageTunnels(TunnelPage page, boolean backwards) {
        if (page.entries().isEmpty()) {
            return;
        }
        UUID anchor = backwards
                ? page.entries().getFirst().tunnelId()
                : page.entries().getLast().tunnelId();
        if (sendTopology(sequence ->
                new NetworkTerminalRequest.PageTunnels(viewId, requireSessionId(), sequence, anchor, backwards))) {
            pendingPageRequest = backwards ? PagedListScroll.PageRequest.PREVIOUS : PagedListScroll.PageRequest.NEXT;
        }
    }

    private void pageChannels(ChannelPage page, boolean backwards) {
        if (page.entries().isEmpty()) {
            return;
        }
        UUID anchor = backwards
                ? page.entries().getFirst().channelId()
                : page.entries().getLast().channelId();
        if (sendTopology(sequence ->
                new NetworkTerminalRequest.PageChannels(viewId, requireSessionId(), sequence, anchor, backwards))) {
            pendingPageRequest = backwards ? PagedListScroll.PageRequest.PREVIOUS : PagedListScroll.PageRequest.NEXT;
        }
    }

    private void applyTopologyPageScroll(PagedListScroll.PageRequest request, TunnelPage page) {
        if (request == PagedListScroll.PageRequest.PREVIOUS) {
            pageTunnels(page, true);
        } else if (request == PagedListScroll.PageRequest.NEXT) {
            pageTunnels(page, false);
        } else {
            rebuildIfActive();
        }
    }

    private void applyTopologyPageScroll(PagedListScroll.PageRequest request, ChannelPage page) {
        if (request == PagedListScroll.PageRequest.PREVIOUS) {
            pageChannels(page, true);
        } else if (request == PagedListScroll.PageRequest.NEXT) {
            pageChannels(page, false);
        } else {
            rebuildIfActive();
        }
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
        pendingDraftSubmitted = true;
        draftState = draftState.submitted();
        error = null;
        errorAllowsRetry = false;
        rebuildIfActive();
    }

    private void submitTopologyName() {
        String canonical;
        try {
            canonical = new ManagedName(topologyDraft).value();
        } catch (IllegalArgumentException invalidName) {
            error = Component.translatable(NetworkTerminalResponse.Reason.INVALID_NAME.translationKey());
            rebuildIfActive();
            return;
        }
        if (topologyState instanceof NetworkTerminalState.NetworkRename) {
            sendTopology(sequence ->
                    new NetworkTerminalRequest.RenameNetwork(viewId, requireSessionId(), sequence, canonical));
        } else if (topologyState instanceof NetworkTerminalState.TunnelEdit edit) {
            sendTopology(sequence -> edit.existing() == null
                    ? new NetworkTerminalRequest.CreateTunnel(
                            viewId,
                            requireSessionId(),
                            sequence,
                            canonical,
                            Component.translatable("omniresonance.terminal.channel.suggested", 1)
                                    .getString())
                    : new NetworkTerminalRequest.RenameTunnel(viewId, requireSessionId(), sequence, canonical));
        }
    }

    private void beginAutomaticTunnelCreate() {
        boolean sent = sendTopology(sequence -> new NetworkTerminalRequest.BeginCreateTunnel(
                viewId,
                requireSessionId(),
                sequence,
                Component.translatable("omniresonance.terminal.tunnel.prefix").getString()));
        if (sent) {
            automaticNameCommit = automaticNameCommit.arm(AutomaticNameCommit.Target.TUNNEL);
        }
    }

    static boolean completeTopologySend(TerminalFilterView filters, boolean sent) {
        if (!sent) filters.requestFailed(null);
        return sent;
    }

    private boolean sendTopology(LongFunction<NetworkTerminalRequest> factory) {
        if (pendingOperation != PendingOperation.NONE || sessionId == null) {
            return false;
        }
        long sequence = nextSequence;
        NetworkTerminalRequest request = Objects.requireNonNull(factory.apply(sequence), "request");
        if (!completeTopologySend(filterView, client.send(request))) {
            error = Component.translatable(NetworkTerminalResponse.Reason.DATA_UNAVAILABLE.translationKey());
            errorAllowsRetry = true;
            rebuildIfActive();
            return false;
        }
        nextSequence++;
        pendingSequence = sequence;
        pendingOperation = PendingOperation.TOPOLOGY;
        pendingDraftSubmitted = TerminalInteractionPolicy.submitsDraft(request);
        error = null;
        errorAllowsRetry = false;
        rebuildIfActive();
        return true;
    }

    private void openSelectedHome() {
        NetworkSummary network = selected;
        if (network == null || sessionId == null || pendingOperation != PendingOperation.NONE) {
            rebuildIfActive();
            return;
        }
        sendTopology(
                sequence -> new NetworkTerminalRequest.OpenNetwork(viewId, requireSessionId(), sequence, network.id()));
    }

    private void sendTopologyBack() {
        sendTopology(sequence -> new NetworkTerminalRequest.Back(viewId, requireSessionId(), sequence));
    }

    record TopologyDraft(String value, boolean dirty) {}

    static TopologyDraft resolveTopologyDraft(
            TerminalFilterView filters,
            @Nullable NetworkTerminalState previous,
            NetworkTerminalState current,
            String value,
            boolean dirty) {
        if (current instanceof NetworkTerminalState.PresetEdit edit) {
            return new TopologyDraft(filters.initialValue(edit), false);
        }
        if (current instanceof NetworkTerminalState.NetworkRename rename) {
            return new TopologyDraft(rename.settings().network().name(), false);
        }
        if (current instanceof NetworkTerminalState.TunnelEdit edit) {
            return new TopologyDraft(
                    edit.existing() == null
                            ? edit.suggestedName()
                            : edit.existing().name(),
                    false);
        }
        if (!(current instanceof NetworkTerminalState.DeleteConfirmation)
                && !(current instanceof NetworkTerminalState.NetworkDelete)
                && (previous instanceof NetworkTerminalState.TunnelEdit
                        || previous instanceof NetworkTerminalState.NetworkRename
                        || previous instanceof NetworkTerminalState.PresetEdit)) {
            return new TopologyDraft("", false);
        }
        return new TopologyDraft(value, dirty);
    }

    static TopologyDraft resolveFailedTopologyDraft(
            TerminalFilterView filters,
            @Nullable NetworkTerminalState previous,
            NetworkTerminalState current,
            String value,
            boolean dirty) {
        return TerminalInteractionPolicy.sameEditor(previous, current)
                ? new TopologyDraft(value, dirty)
                : resolveTopologyDraft(filters, previous, current, value, dirty);
    }

    private void initializeTopologyDraft(@Nullable NetworkTerminalState previous, NetworkTerminalState current) {
        TopologyDraft draft = resolveTopologyDraft(filterView, previous, current, topologyDraft, topologyDraftDirty);
        topologyDraft = draft.value();
        topologyDraftDirty = draft.dirty();
        if (current instanceof NetworkTerminalState.PresetEdit
                || current instanceof NetworkTerminalState.NetworkRename
                || current instanceof NetworkTerminalState.TunnelEdit
                || current instanceof NetworkTerminalState.DeleteConfirmation
                || current instanceof NetworkTerminalState.NetworkDelete) {
            lastTopologyHeartbeatTick = clientTicks;
        }
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
        pendingPageRequest = backwards ? PagedListScroll.PageRequest.PREVIOUS : PagedListScroll.PageRequest.NEXT;
        error = null;
        errorAllowsRetry = false;
        networkContext.open = false;
        rebuildIfActive();
    }

    static TerminalIconButton buildCreateButton(
            TerminalLayout.Rect bounds,
            TerminalInteractionPolicy.CreateTarget target,
            Runnable network,
            Runnable tunnel,
            Runnable administrator,
            java.util.function.Consumer<TerminalFilterView.Action> filters) {
        String key =
                switch (target) {
                    case NETWORK, NONE -> "omniresonance.terminal.create";
                    case TUNNEL -> "omniresonance.terminal.tunnel.create";
                    case ADMINISTRATOR -> "omniresonance.terminal.members.add";
                    case PRESET -> "omniresonance.terminal.filters.create";
                };
        return new TerminalIconButton(
                bounds.x(), bounds.y(), bounds.width(), bounds.height(), Component.translatable(key), ignored -> {
                    switch (target) {
                        case NETWORK -> network.run();
                        case TUNNEL -> tunnel.run();
                        case ADMINISTRATOR -> administrator.run();
                        case PRESET ->
                            filters.accept(new TerminalFilterView.Action.Begin(
                                    io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE, null));
                        case NONE -> {}
                    }
                });
    }

    private void select(NetworkSummary summary) {
        if (topologyState != null && !networkContext.selectable()) return;
        selected = summary;
        networkContext.open = false;
        detailScroll = 0;
        openSelectedHome();
    }

    private void navigateBack() {
        if (topologyState != null) {
            navigateTopologyBack();
            return;
        }
        TerminalInteractionPolicy.BackAction action = TerminalInteractionPolicy.backAction(
                networkContext.open, confirmation, createOverlay, draftState, layout.compact() && compactDetails);
        switch (action) {
            case CLOSE_DROPDOWN -> networkContext.open = false;
            case CLOSE_CONFIRMATION -> {
                confirmation = false;
                closeAfterDiscard = false;
            }
            case HIDE_CREATE_OVERLAY -> {
                createOverlay = false;
                firstPrompt = false;
                if (selected != null) {
                    openSelectedHome();
                    return;
                }
            }
            case CONFIRM_DRAFT -> {
                closeAfterDiscard = false;
                confirmation = true;
            }
            case SHOW_COMPACT_LIST -> compactDetails = false;
            case CLOSE_SCREEN -> {
                closeRoot();
                return;
            }
        }
        rebuildIfActive();
    }

    static boolean closeLocalTopologyLayer(
            TerminalNetworkContext context, TerminalFilterView filters, Runnable rebuild) {
        if (context.intercepts()) {
            context.clearDropdown();
            rebuild.run();
            return true;
        }
        return filters.closeManagement();
    }

    private void navigateTopologyBack() {
        if (inventoryView != null) {
            closeInventory();
            rebuildIfActive();
            return;
        }
        if (topologyDiscardConfirmation) {
            pendingFilterSave = null;
            topologyDiscardConfirmation = false;
            closeAfterDiscard = false;
            rebuildIfActive();
            return;
        }
        if (filterView.closeSearch()) return;
        if (closeLocalTopologyLayer(networkContext, filterView, this::rebuildIfActive)) return;
        if (!networkContext.open && memberView.closeLocalLayer()) return;
        if (topologyState instanceof NetworkTerminalState.AdministratorCandidates
                && pendingOperation != PendingOperation.NONE) {
            memberBackPending = true;
            return;
        }
        if (topologyState instanceof NetworkTerminalState.NetworkRoot) {
            closeRoot();
            return;
        }
        boolean editing = topologyState instanceof NetworkTerminalState.TunnelEdit
                || topologyState instanceof NetworkTerminalState.NetworkRename
                || topologyState instanceof NetworkTerminalState.PresetEdit;
        TerminalInteractionPolicy.TopologyBackAction action = TerminalInteractionPolicy.topologyBackAction(
                pendingOperation != PendingOperation.NONE,
                topologyDiscardConfirmation,
                editing && (topologyDraftDirty || filterView.resourceDirty()));
        switch (action) {
            case BLOCK -> {
                return;
            }
            case CLOSE_CONFIRMATION -> {
                topologyDiscardConfirmation = false;
                closeAfterDiscard = false;
            }
            case CONFIRM_DRAFT -> {
                networkContext.open = false;
                topologyDiscardConfirmation = true;
                closeAfterDiscard = false;
            }
            case SEND_BACK -> sendTopologyBack();
        }
        rebuildIfActive();
    }

    private void discardDraft() {
        if (closeAfterDiscard) {
            closeRoot();
            return;
        }
        confirmation = false;
        createOverlay = false;
        firstPrompt = false;
        draftState = TerminalInteractionPolicy.DraftState.clear();
        draft = "";
        error = null;
        errorAllowsRetry = false;
        if (selected != null) {
            openSelectedHome();
        } else {
            rebuildIfActive();
        }
    }

    private void requestShortcutClose() {
        pendingFilterSave = null;
        boolean dirty = topologyState != null
                ? topologyDraftDirty || filterView.resourceDirty()
                : createOverlay && draftState.requiresConfirmation();
        if (TerminalInteractionPolicy.shortcutAction(pendingDraftSubmitted, dirty)
                == TerminalInteractionPolicy.BackAction.CONFIRM_DRAFT) {
            closeAfterDiscard = true;
            networkContext.open = false;
            if (topologyState != null) topologyDiscardConfirmation = true;
            else confirmation = true;
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
        closeInventory();
        if (closeSent) {
            return;
        }
        closeSent = true;
        clearFilterTransfer();
        filterDownload.close();
        if (!disconnected && sessionId != null) {
            client.send(new NetworkTerminalRequest.Close(viewId, sessionId));
        }
    }

    @Override
    public void removed() {
        closeInventory();
        sendCloseOnce();
    }

    @Override
    public void onClose() {
        navigateBack();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (inventoryView != null && inventoryView.dismissPopup(keyCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            navigateBack();
            return true;
        }
        if (routeKey(
                getFocused(),
                keyCode,
                scanCode,
                modifiers,
                () -> (minecraft != null
                                && TerminalInteractionPolicy.inventoryShortcut(
                                        minecraft.options.keyInventory, getFocused(), keyCode, scanCode))
                        || client.isTerminalKey(keyCode, scanCode),
                this::requestShortcutClose,
                () -> {
                    if (networkContext.open
                            || topologyDiscardConfirmation
                            || !((inventoryView != null && inventoryView.resourceShortcut(keyCode, scanCode, modifiers))
                                    || memberView.keyPressed(keyCode, modifiers)
                                    || filterView.keyPressed(keyCode, modifiers))) return false;
                    if (minecraft != null)
                        minecraft
                                .getSoundManager()
                                .play(TerminalClickButton.clickFeedback().createSound());
                    return true;
                })) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    static boolean routeKey(
            @Nullable GuiEventListener focused,
            int keyCode,
            int scanCode,
            int modifiers,
            BooleanSupplier shortcutPressed,
            Runnable close,
            BooleanSupplier memberSearch) {
        if (focused instanceof TerminalEditBox field && field.ownsKey(keyCode)) {
            return field.keyPressed(keyCode, scanCode, modifiers);
        }
        if (shortcutPressed.getAsBoolean()) {
            close.run();
            return true;
        }
        return memberSearch.getAsBoolean();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        networkContext.apply(topologyState);
        if (topologyDiscardConfirmation || confirmation) return super.mouseClicked(mouseX, mouseY, button);
        if (inventoryView != null && inventoryView.mouseClicked(mouseX, mouseY, button)) {
            setFocused(null);
            setDragging(false);
            return true;
        }
        if (!networkContext.open) {
            boolean expanded = memberView.expanded();
            boolean filterExpanded = filterView.searchExpanded();
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (TerminalMemberView.supports(topologyState)) memberView.finishClick(expanded);
            filterView.finishSearchClick(filterExpanded, this);
            return handled;
        }
        if (contains(selectorBounds, mouseX, mouseY)) {
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (handled && !networkContext.open) {
                setFocused(null);
                setDragging(false);
            }
            return handled;
        }
        if (contains(networkContext.bounds, mouseX, mouseY)) {
            for (TerminalButton dropdownButton : networkContext.buttons) {
                if (dropdownButton.mouseClicked(mouseX, mouseY, button)) {
                    if (networkContext.open) {
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
        networkContext.open = false;
        rebuildIfActive();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inventoryView != null) return inventoryView.wheel(mouseX, mouseY, scrollY);
        networkContext.apply(topologyState);
        if (topologyDiscardConfirmation || confirmation || pendingOperation != PendingOperation.NONE) return true;
        if (pendingOperation == PendingOperation.NONE
                && !topologyDiscardConfirmation
                && filterView.scroll(mouseX, mouseY, scrollY)) return true;
        if (TerminalMemberView.supports(topologyState) && !networkContext.open) {
            return memberView.mouseScrolled(mouseX, mouseY, scrollY);
        }
        if (topologyState instanceof NetworkTerminalState.TunnelList list
                && contains(layout.content(), mouseX, mouseY)) {
            PagedListScroll.Result scroll = PagedListScroll.navigate(
                    listScroll,
                    list.page().entries().size(),
                    topologyListLayout(list.page().entries().size()).visibleRows(),
                    list.page().hasPrevious(),
                    list.page().hasNext(),
                    scrollY);
            listScroll = scroll.scroll();
            applyTopologyPageScroll(scroll.pageRequest(), list.page());
            return true;
        }
        if (topologyState instanceof NetworkTerminalState.ChannelList list
                && contains(layout.content(), mouseX, mouseY)) {
            PagedListScroll.Result scroll = PagedListScroll.navigate(
                    listScroll,
                    list.page().entries().size(),
                    topologyListLayout(list.page().entries().size()).visibleRows(),
                    list.page().hasPrevious(),
                    list.page().hasNext(),
                    scrollY);
            listScroll = scroll.scroll();
            applyTopologyPageScroll(scroll.pageRequest(), list.page());
            return true;
        }
        if (networkContext.intercepts() && contains(networkContext.bounds, mouseX, mouseY) && page != null) {
            int visibleRows = Math.min(MAX_VISIBLE_DROPDOWN_ROWS, page.entries().size());
            dropdownScroll = scrollBy(dropdownScroll, scrollY, page.entries().size(), visibleRows);
            rebuildIfActive();
            return true;
        }
        if (!createOverlay && page != null && contains(listBounds, mouseX, mouseY)) {
            PagedListScroll.Result scroll = PagedListScroll.navigate(
                    listScroll, page.entries().size(), visibleListRows(), page.hasPrevious(), page.hasNext(), scrollY);
            listScroll = scroll.scroll();
            if (scroll.pageRequest() == PagedListScroll.PageRequest.PREVIOUS) {
                requestPage(true);
            } else if (scroll.pageRequest() == PagedListScroll.PageRequest.NEXT) {
                requestPage(false);
            } else {
                rebuildIfActive();
            }
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
    public void tick() {
        super.tick();
        clientTicks++;
        if (inventoryView != null) inventoryView.tick();
        tickFilterTransfer();
        memberView.tick(clientTicks);
        filterView.tick(clientTicks, pendingOperation != PendingOperation.NONE);
        boolean editing = topologyState instanceof NetworkTerminalState.TunnelEdit
                || topologyState instanceof NetworkTerminalState.DeleteConfirmation
                || topologyState instanceof NetworkTerminalState.NetworkRename
                || topologyState instanceof NetworkTerminalState.NetworkDelete
                || topologyState instanceof NetworkTerminalState.RemoveAdministrator
                || topologyState instanceof NetworkTerminalState.PresetEdit;
        if (editing
                && pendingOperation == PendingOperation.NONE
                && clientTicks - lastTopologyHeartbeatTick >= 40
                && sessionId != null) {
            long sequence = nextSequence++;
            heartbeatSequence = sequence;
            lastTopologyHeartbeatTick = clientTicks;
            client.send(new NetworkTerminalRequest.Heartbeat(viewId, sessionId, sequence));
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, TerminalTheme.WORLD_DIM);
        if (inventoryView == null) TerminalTheme.renderWindow(graphics, layout);
        else DomainInventoryLayout.renderWindow(graphics, layout);
        renderTopBar(graphics);
        renderBody(graphics);
        if (networkContext.intercepts()
                && page != null
                && !createOverlay
                && !confirmation
                && !topologyDiscardConfirmation) {
            TerminalTheme.renderPanel(graphics, networkContext.bounds);
            TerminalTheme.renderScrollbar(
                    graphics,
                    networkContext.bounds.right() - TerminalLayout.SCROLLBAR_WIDTH,
                    networkContext.bounds.y() + 2,
                    Math.max(0, networkContext.bounds.height() - 4),
                    page.entries().size(),
                    Math.min(MAX_VISIBLE_DROPDOWN_ROWS, page.entries().size()),
                    dropdownScroll);
        }
        if (createOverlay) {
            renderCreateOverlay(graphics, createBounds);
        }
        if (confirmation) {
            modalBackdrop.render(widget -> widget.render(graphics, -1, -1, partialTick));
        }
        if (topologyState != null && topologyDiscardConfirmation) {
            modalBackdrop.render(widget -> widget.render(graphics, -1, -1, partialTick));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean inspecting = inventoryView != null && inventoryView.popupOpen();
        super.render(graphics, inspecting ? -1 : mouseX, inspecting ? -1 : mouseY, partialTick);
        if (inventoryView != null) {
            inventoryView.renderTooltip(graphics, font, layout.window(), mouseX, mouseY);
            inventoryView.renderCarried(graphics, font, mouseX, mouseY);
        }
        if (!inspecting
                && !confirmation
                && !topologyDiscardConfirmation
                && (!networkContext.selectable() || inventoryView != null)
                && networkContext.network() != null
                && contains(selectorBounds, mouseX, mouseY)) {
            graphics.renderTooltip(font, TerminalText.body(networkContext.label()), mouseX, mouseY);
        }
        if (!confirmation && !topologyDiscardConfirmation && inventoryView == null)
            filterView.renderPasteNotice(graphics, font, mouseX, mouseY);
        if (confirmation || topologyDiscardConfirmation)
            modalBackdrop.renderForeground(graphics, mouseX, mouseY, partialTick, () -> {
                if (confirmation) renderConfirmation(graphics);
                else renderTopologyDiscardConfirmation(graphics);
            });
    }

    private void renderTopBar(GuiGraphics graphics) {
        networkContext.apply(topologyState);
        if ((!networkContext.selectable() || inventoryView != null)
                && networkContext.network() != null
                && selectorBounds.width() > 0) {
            TerminalText.drawNetworkLabel(
                    networkContext.label().getString(),
                    selectorBounds,
                    candidate -> font.width(TerminalText.body(Component.literal(candidate))),
                    TerminalTheme.TEXT,
                    (text, x, y, color, shadow) -> graphics.drawString(font, text, x, y, color, shadow));
        }
        graphics.drawString(
                font,
                TerminalText.title(font, topBarTitle(), Math.max(0, crumbRight - crumbLeft)),
                crumbLeft,
                layout.titleBar().y() + 10,
                TerminalTheme.TEXT,
                false);
        if (showEscapeHint) {
            Component hint = Component.translatable("omniresonance.terminal.escape_hint", client.translatedKey());
            String label = ellipsize(hint.getString(), escapeHintWidth);
            graphics.drawString(
                    font,
                    label,
                    escapeHintRight - font.width(label),
                    layout.titleBar().y() + 10,
                    TerminalTheme.MUTED,
                    false);
        }
    }

    private String topBarTitle() {
        if (inventoryView != null) return DomainInventoryView.text("title").getString();
        String crumb = inventoryView != null
                ? DomainInventoryView.text("title").getString()
                : topologyState != null
                        ? topologyCrumb(topologyState)
                        : page == null
                                ? Component.translatable("omniresonance.terminal.loading")
                                        .getString()
                                : createOverlay
                                        ? Component.translatable("omniresonance.terminal.create")
                                                .getString()
                                        : selected == null ? "" : selected.name();
        return getTitle().getString() + (crumb.isEmpty() ? "" : " / " + crumb);
    }

    private void renderBody(GuiGraphics graphics) {
        TerminalLayout.Rect content = layout.content();
        if (topologyState != null) {
            renderTopologyBody(graphics, topologyState);
            return;
        }
        if (page == null) {
            TerminalTheme.renderPanel(graphics, content);
            Component message = error == null ? Component.translatable("omniresonance.terminal.loading") : error;
            int color = error == null ? TerminalTheme.MUTED : TerminalTheme.ERROR;
            drawCenteredWrapped(graphics, message, content, color, -10);
            return;
        }
        if (page.entries().isEmpty()) {
            TerminalTheme.renderPanel(graphics, content);
            if (TerminalInteractionPolicy.renderEmptyDirectory(createOverlay)) {
                Component title = TerminalText.title(Component.translatable("omniresonance.terminal.empty.title"));
                Component message = Component.translatable("omniresonance.terminal.empty.message");
                int centerY = content.y() + content.height() / 2 - 16;
                TerminalText.drawCentered(
                        graphics, font, title, content.x() + content.width() / 2, centerY, TerminalTheme.TEXT);
                drawCenteredWrapped(graphics, message, content, TerminalTheme.MUTED, 0);
            }
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

    private void renderTopologyBody(GuiGraphics graphics, NetworkTerminalState state) {
        if (inventoryView != null) {
            inventoryView.renderBody(graphics, font);
            return;
        }
        if (TerminalFilterView.supports(state)) {
            filterView.render(graphics, font, layout, state, topologyDraft);
            if (error != null)
                graphics.drawString(
                        font,
                        ellipsize(error.getString(), layout.content().width() - 16),
                        layout.content().x() + 8,
                        layout.content().bottom() - 12,
                        TerminalTheme.ERROR,
                        false);
            return;
        }
        if (TerminalMemberView.supports(state)) {
            memberView.render(graphics, font, layout);
            if (error != null)
                graphics.drawString(
                        font,
                        ellipsize(error.getString(), layout.content().width() - 16),
                        layout.content().x() + 8,
                        layout.content().bottom() - 12,
                        TerminalTheme.ERROR,
                        false);
            return;
        }
        TerminalLayout.Rect content = layout.content();
        TerminalTheme.renderPanel(graphics, content);
        if (automaticNameCommit.suppressEditor()) {
            drawCenteredWrapped(
                    graphics,
                    Component.translatable("omniresonance.terminal.topology.pending"),
                    content,
                    TerminalTheme.MUTED,
                    -5);
            return;
        }
        if (TerminalNetworkSettingsView.supports(state)) {
            TerminalNetworkSettingsView.render(graphics, font, layout, state);
        } else if (state instanceof NetworkTerminalState.NetworkRoot root) {
            return;
        } else if (state instanceof NetworkTerminalState.TunnelList list) {
            TerminalText.drawHeaderTitle(
                    graphics,
                    font,
                    Component.translatable("omniresonance.terminal.tunnels").getString(),
                    TerminalHeaderLayout.contentTitle(content));
            renderTopologyScrollbar(graphics, list.page().entries().size());
        } else if (state instanceof NetworkTerminalState.ChannelList list) {
            TerminalText.drawHeaderTitle(
                    graphics, font, list.tunnel().name(), TerminalHeaderLayout.contentTitle(content));
            if (!list.tunnel().enabled()) {
                drawCenteredWrapped(
                        graphics,
                        Component.translatable("omniresonance.terminal.tunnel.disabled.message"),
                        content,
                        TerminalTheme.ERROR,
                        -8);
            }
            renderTopologyScrollbar(graphics, list.page().entries().size());
        } else if (state instanceof NetworkTerminalState.TunnelSettings settings) {
            graphics.drawString(
                    font,
                    TerminalText.title(Component.translatable("omniresonance.terminal.tunnel.settings")),
                    content.x() + 12,
                    content.y() + 18,
                    TerminalTheme.TEXT,
                    false);
            graphics.drawString(
                    font,
                    ellipsize(settings.tunnel().name(), Math.max(0, content.width() - 24)),
                    content.x() + 12,
                    content.y() + 34,
                    TerminalTheme.ACCENT,
                    false);
        } else if (state instanceof NetworkTerminalState.TunnelEdit edit) {
            var dialog = TerminalDialogLayout.editor(content);
            TerminalDialogLayout.render(graphics, content, dialog);
            graphics.drawString(
                    font,
                    Component.translatable(
                            edit.existing() == null
                                    ? "omniresonance.terminal.tunnel.create"
                                    : "omniresonance.terminal.tunnel.rename"),
                    dialog.x() + 12,
                    dialog.y() + 18,
                    TerminalTheme.TEXT,
                    false);
            graphics.drawString(
                    font,
                    Component.translatable("omniresonance.terminal.object_name"),
                    dialog.x() + 12,
                    dialog.y() + 40,
                    TerminalTheme.MUTED,
                    false);
        } else if (state instanceof NetworkTerminalState.DeleteConfirmation confirmationState) {
            graphics.fill(content.x(), content.y(), content.right(), content.bottom(), 0xA000070C);
            TerminalTheme.renderPanel(graphics, modalBounds);
            TopologyDeletionSummary summary = confirmationState.summary();
            TerminalText.drawCentered(
                    graphics,
                    font,
                    Component.translatable("omniresonance.terminal.delete.title", summary.name()),
                    modalBounds.x() + modalBounds.width() / 2,
                    modalBounds.y() + 12,
                    TerminalTheme.TEXT);
            Component impact = Component.translatable(
                    "omniresonance.terminal.delete.impact", summary.channelCount(), summary.bindingCount());
            drawWrapped(
                    graphics,
                    impact,
                    modalBounds.x() + 10,
                    modalBounds.y() + 34,
                    modalBounds.width() - 20,
                    TerminalTheme.MUTED,
                    3);
        }
        if (pendingOperation == PendingOperation.TOPOLOGY) {
            graphics.drawString(
                    font,
                    Component.translatable("omniresonance.terminal.topology.pending"),
                    content.x() + 5,
                    content.bottom() - 13,
                    TerminalTheme.MUTED,
                    false);
        } else if (error != null) {
            graphics.drawString(
                    font,
                    ellipsize(error.getString(), content.width() - 10),
                    content.x() + 5,
                    content.bottom() - 13,
                    TerminalTheme.ERROR,
                    false);
        }
    }

    private static String topologyCrumb(NetworkTerminalState state) {
        return switch (state) {
            case NetworkTerminalState.Filters filters -> filters.network().name();
            case NetworkTerminalState.Preset preset -> preset.preset().name();
            case NetworkTerminalState.PresetEdit edit -> edit.network().name();
            case NetworkTerminalState.NetworkSettings settings ->
                settings.settings().network().name();
            case NetworkTerminalState.NetworkRename rename ->
                rename.settings().network().name();
            case NetworkTerminalState.NetworkDelete delete ->
                delete.settings().network().name();
            case NetworkTerminalState.Members members -> members.network().name();
            case NetworkTerminalState.AdministratorCandidates candidates ->
                candidates.network().name();
            case NetworkTerminalState.RemoveAdministrator remove ->
                remove.network().name();
            case NetworkTerminalState.NetworkRoot root ->
                Component.translatable("omniresonance.terminal.home").getString();
            case NetworkTerminalState.TunnelList list -> list.network().name();
            case NetworkTerminalState.TunnelEdit edit -> edit.network().name();
            case NetworkTerminalState.ChannelList list -> list.tunnel().name();
            case NetworkTerminalState.TunnelSettings settings ->
                settings.tunnel().name();
            case NetworkTerminalState.DeleteConfirmation confirmation ->
                confirmation.summary().name();
        };
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
                Math.max(0, listBounds.height() - PANEL_HEADING_HEIGHT),
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

    private void renderCreateOverlay(GuiGraphics graphics, TerminalLayout.Rect modalBounds) {
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

    private void renderTopologyDiscardConfirmation(GuiGraphics graphics) {
        TerminalLayout.Rect content = layout.content();
        graphics.fill(content.x(), content.y(), content.right(), content.bottom(), 0xB000070C);
        TerminalTheme.renderPanel(graphics, modalBounds);
        TerminalText.drawCentered(
                graphics,
                font,
                Component.translatable(
                        pendingFilterSave == null
                                ? "omniresonance.terminal.topology.discard.title"
                                : "omniresonance.terminal.filters.save"),
                modalBounds.x() + modalBounds.width() / 2,
                modalBounds.y() + 12,
                TerminalTheme.TEXT);
        drawWrapped(
                graphics,
                pendingFilterSave != null && topologyState instanceof NetworkTerminalState.PresetEdit edit
                        ? Component.translatable(
                                "omniresonance.terminal.filters.impact",
                                edit.impact().networkCount(),
                                edit.impact().nodeCount(),
                                edit.impact().bindingCount())
                        : Component.translatable("omniresonance.terminal.topology.discard.message"),
                modalBounds.x() + 10,
                modalBounds.y() + 32,
                modalBounds.width() - 20,
                TerminalTheme.MUTED,
                2);
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
            TerminalText.drawCentered(
                    graphics, font, lines.get(index), bounds.x() + bounds.width() / 2, y + index * 11, color);
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
        if (listBounds.height() <= PANEL_HEADING_HEIGHT) {
            return 1;
        }
        return Math.max(1, (listBounds.height() - PANEL_HEADING_HEIGHT) / ROW_HEIGHT);
    }

    private RoutingListLayout topologyListLayout(int entryCount) {
        return RoutingListLayout.calculate(layout.content(), entryCount, listScroll);
    }

    private void renderTopologyScrollbar(GuiGraphics graphics, int entryCount) {
        RoutingListLayout listLayout = topologyListLayout(entryCount);
        TerminalTheme.renderScrollbar(
                graphics,
                listLayout.scrollbar().x(),
                listLayout.scrollbar().y(),
                listLayout.scrollbar().height(),
                entryCount,
                listLayout.visibleRows(),
                listLayout.scroll());
    }

    private UUID requireSessionId() {
        return Objects.requireNonNull(sessionId, "terminal session");
    }

    private void buildMemberView() {
        UUID actor = minecraft != null && minecraft.player != null
                ? minecraft.player.getUUID()
                : net.minecraft.Util.NIL_UUID;
        memberView.build(
                font,
                layout,
                actor,
                pendingOperation != PendingOperation.NONE,
                this::addRenderableWidget,
                this::removeWidget,
                this::sendMemberAction);
    }

    private void sendMemberAction(TerminalMemberView.Action action) {
        if (action instanceof TerminalMemberView.Action.Back) {
            navigateTopologyBack();
            return;
        }
        sendTopology(sequence -> switch (action) {
            case TerminalMemberView.Action.OpenCandidates ignored ->
                new NetworkTerminalRequest.OpenAdministratorCandidates(viewId, requireSessionId(), sequence);
            case TerminalMemberView.Action.Add add ->
                new NetworkTerminalRequest.AddAdministrator(viewId, requireSessionId(), sequence, add.target());
            case TerminalMemberView.Action.Remove remove ->
                new NetworkTerminalRequest.RequestRemoveAdministrator(
                        viewId, requireSessionId(), sequence, remove.target());
            case TerminalMemberView.Action.ConfirmRemove ignored ->
                new NetworkTerminalRequest.ConfirmRemoveAdministrator(viewId, requireSessionId(), sequence);
            case TerminalMemberView.Action.PageMembers page ->
                new NetworkTerminalRequest.PageMembers(
                        viewId, requireSessionId(), sequence, page.anchor(), page.backwards());
            case TerminalMemberView.Action.ContinueCandidates page ->
                new NetworkTerminalRequest.PageAdministratorCandidates(
                        viewId, requireSessionId(), sequence, page.snapshotId(), page.offset());
            case TerminalMemberView.Action.Back ignored -> throw new IllegalStateException("Back was already handled");
        });
    }

    private boolean continueMemberCandidates() {
        if (!(topologyState instanceof NetworkTerminalState.AdministratorCandidates)) {
            memberBackPending = false;
            return false;
        }
        if (memberBackPending) {
            memberBackPending = false;
            sendTopologyBack();
            return pendingOperation != PendingOperation.NONE;
        }
        TerminalMemberView.Action.ContinueCandidates continuation = memberView.continuation();
        if (continuation != null) {
            sendMemberAction(continuation);
            return pendingOperation != PendingOperation.NONE;
        }
        return false;
    }

    private String ellipsize(String text, int maximumWidth) {
        return TerminalText.ellipsize(font, text, maximumWidth);
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

    private static int pageLanding(PagedListScroll.PageRequest request) {
        return request == PagedListScroll.PageRequest.PREVIOUS ? Integer.MAX_VALUE : 0;
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
        CREATE,
        TOPOLOGY
    }
}
