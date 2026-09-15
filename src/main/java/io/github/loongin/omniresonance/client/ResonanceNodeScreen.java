// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.networking.ManagementDownloadAssembler;
import io.github.loongin.omniresonance.networking.ManagementTransferMessage;
import io.github.loongin.omniresonance.networking.ManagementTransferPool;
import io.github.loongin.omniresonance.networking.NodeChannelPage;
import io.github.loongin.omniresonance.networking.NodeChannelSummary;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuRequest;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeNetworkPage;
import io.github.loongin.omniresonance.networking.NodeNetworkSummary;
import io.github.loongin.omniresonance.networking.NodePolicyFrames;
import io.github.loongin.omniresonance.networking.NodeTransferStatus;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.networking.ResourcePolicyEditCodec;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.ResonanceNodeMenu;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Fixed-window client view for one server-authoritative physical resonance-node Menu. */
final class ResonanceNodeScreen extends AbstractContainerScreen<ResonanceNodeMenu> {
    private static final int CONTROL_HEIGHT = 20;
    private static final int HEADING_HEIGHT = 18;
    private static final int EDIT_BOX_MAXIMUM_UTF16_UNITS = 256;

    private TerminalLayout layout = TerminalLayout.calculate(320, 240);
    private TerminalLayout.Rect bodyBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect modalBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect nodeTitleBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private TerminalLayout.Rect networkTitleBounds = new TerminalLayout.Rect(0, 0, 0, 0);
    private NodeMenuInteractionPolicy.Model interaction = NodeMenuInteractionPolicy.Model.loading();
    private @Nullable Component error;
    private @Nullable EditBox nameField;
    private @Nullable EditBox searchField;
    private String draft = "";
    private final NodeTunnelSearch tunnelSearch = new NodeTunnelSearch();
    private final NodeTunnelCatalog tunnelCatalog = new NodeTunnelCatalog();
    private final TerminalResultRows tunnelResultRows =
            new TerminalResultRows(this, this::addRenderableWidget, this::removeWidget);
    private String appliedTunnelSearch = "";
    private boolean tunnelCatalogPagePending;
    private boolean returnAfterTunnelBatch;
    private boolean settingDraft;
    private int listScroll;
    private long clientTicks;
    private Modal modal = Modal.NONE;
    private boolean closeAfterDiscard;
    private @Nullable NodeWorkingFacesDraft faceDraft;
    private int faceScroll;
    private final ModalBackdrop modalBackdrop = new ModalBackdrop();
    private @Nullable NodeMode pendingMode;
    private @Nullable NodeMode modeCommitAfterBegin;
    private boolean modeCommitConfirmedReset;
    private @Nullable NodeDirectionView.Draft directionDraft;
    private @Nullable NodeResourcePolicyDraft itemDraft;
    private final ManagementDownloadAssembler downloads = new ManagementDownloadAssembler();
    private @Nullable NodeMenuResponse.Download policyDownload;
    private @Nullable ResourcePolicyEdit uploadPolicy;
    private @Nullable UUID uploadId;
    private byte @Nullable [] uploadBytes;
    private int uploadOffset;
    private long transferDeadline;
    private final NodeResourceTypeCatalog resourceCatalog = new NodeResourceTypeCatalog();
    private @Nullable NodeResourceTypeCatalog.Request catalogRequest;
    private @Nullable NodeResourceTypeSelection resourceSelection;
    private final java.util.List<TerminalRowButton> resourceRows = new java.util.ArrayList<>();
    private boolean choosingItemPreset;
    private final NodePresetPicker presetPicker = new NodePresetPicker();
    private final TerminalResultRows presetResultRows =
            new TerminalResultRows(this, this::addRenderableWidget, this::removeWidget);
    private int itemFormScroll;
    private boolean resourceSettingsPage;
    private int resourceSettingsScroll;
    private @Nullable NodeResourceSettingEditor typeEditor;
    private PagedListScroll.PageRequest pendingPageRequest = PagedListScroll.PageRequest.NONE;
    private AutomaticNameCommit automaticNameCommit = AutomaticNameCommit.idle();

    ResonanceNodeScreen(ResonanceNodeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        inventoryLabelY = Integer.MIN_VALUE;
        titleLabelY = Integer.MIN_VALUE;
    }

    boolean matches(NodeMenuResponse response) {
        return response.containerId() == menu.containerId
                && response.sessionId().equals(menu.sessionId())
                && minecraft != null
                && minecraft.screen == this;
    }

    void applyResponse(NodeMenuResponse response) {
        if (!matches(response)) {
            return;
        }
        if (response instanceof NodeMenuResponse.UploadReady ready) {
            if (uploadPolicy != null
                    && uploadId != null
                    && uploadId.equals(ready.transfer())
                    && interaction.pending() != null
                    && interaction.pending().sequence() == ready.sequence()
                    && uploadBytes == null) {
                uploadBytes = ResourcePolicyEditCodec.encode(uploadPolicy);
                uploadPolicy = null;
            }
            return;
        }
        if (response instanceof NodeMenuResponse.Download download) {
            if (interaction.pending() == null
                    || interaction.pending().sequence() != download.sequence()
                    || policyDownload != null
                    || uploadId != null) return;
            policyDownload = download;
            var pin = new ManagementDownloadAssembler.Expected(
                    menu.sessionId(),
                    download.transfer(),
                    ManagementTransferMessage.Context.NODE,
                    download.metadata().policyContextId(),
                    ManagementTransferMessage.Purpose.NODE_POLICY,
                    download.length());
            downloads.begin(
                    new ManagementTransferMessage.Begin(
                            pin.session(),
                            pin.transfer(),
                            ManagementTransferMessage.Direction.DOWNLOAD,
                            pin.context(),
                            pin.contextId(),
                            pin.purpose(),
                            pin.totalLength()),
                    pin,
                    clientTicks);
            return;
        }
        if (response instanceof NodeMenuResponse.Catalog catalog) {
            if (catalogRequest == null
                    || interaction.pending() == null
                    || interaction.pending().sequence() != response.sequence()) return;
            resourceCatalog.complete(catalogRequest, catalog.page());
            catalogRequest = null;
            interaction = interaction
                    .apply(new NodeMenuResponse.State(
                            menu.containerId, menu.sessionId(), response.sequence(), interaction.authoritative()))
                    .model();
            rebuildIfActive();
            return;
        }
        if (uploadId != null
                && interaction.pending() != null
                && response.sequence() == interaction.pending().sequence()) {
            uploadId = null;
            uploadBytes = null;
            uploadPolicy = null;
            uploadOffset = 0;
        }
        NodeMenuInteractionPolicy.EditKind previous = interaction.editKind();
        PagedListScroll.PageRequest completedPage = pendingPageRequest;
        NodeMenuInteractionPolicy.Transition transition = interaction.apply(response);
        if (!transition.accepted()) {
            return;
        }
        if (presetPicker.pending()) {
            presetPicker.complete(
                    response instanceof NodeMenuResponse.State success
                                    && success.state() instanceof NodeMenuState.ResourceEdit edit
                            ? edit.presets()
                            : null);
            if (response instanceof NodeMenuResponse.State
                    && transition.model().authoritative() instanceof NodeMenuState.ResourceEdit) {
                interaction = transition.model().armHeartbeat(clientTicks);
                if (choosingItemPreset && modal == Modal.NONE) updatePresetResultRows();
                else rebuildIfActive();
                return;
            }
        }
        if (!transition.rebuild()) {
            interaction = transition.model();
            return;
        }
        boolean continuation = tunnelCatalogPagePending;
        boolean returnAfterBatch = returnAfterTunnelBatch;
        boolean wasTunnelList = interaction.authoritative() instanceof NodeMenuState.DirectTunnelList;
        tunnelCatalogPagePending = false;
        returnAfterTunnelBatch = false;
        interaction = transition.model();
        if (returnAfterBatch) {
            tunnelCatalog.reset();
        } else if (interaction.authoritative() instanceof NodeMenuState.DirectTunnelList list) {
            if (response instanceof NodeMenuResponse.Failure) {
                tunnelCatalog.fail();
            } else if (continuation) {
                tunnelCatalog.append(list);
            } else {
                tunnelCatalog.begin(list);
            }
            if (!wasTunnelList) {
                tunnelSearch.reset();
                appliedTunnelSearch = "";
            }
        } else {
            tunnelSearch.reset();
            appliedTunnelSearch = "";
            NodeMenuNodeSummary node = NodeMenuInteractionPolicy.linkedNode(interaction.authoritative());
            if (node == null || !node.enabled() || !tunnelCatalog.belongsTo(node) || tunnelCatalog.loading()) {
                tunnelCatalog.reset();
            }
        }
        pendingPageRequest = PagedListScroll.PageRequest.NONE;
        NodeMenuInteractionPolicy.EditKind current = interaction.editKind();
        if (current != NodeMenuInteractionPolicy.EditKind.NONE) {
            interaction = interaction.armHeartbeat(clientTicks);
        }
        if (current != previous) {
            initializeDraft(current, interaction.authoritative());
        } else if (current == NodeMenuInteractionPolicy.EditKind.NONE) {
            draft = "";
            directionDraft = null;
            itemDraft = null;
            faceDraft = null;
            choosingItemPreset = false;
            presetPicker.close();
        }
        AutomaticNameCommit.Resolution automatic = automaticNameCommit.resolveNode(
                response instanceof NodeMenuResponse.State, interaction.authoritative());
        automaticNameCommit = automatic.next();
        if (automatic.commit() == AutomaticNameCommit.Target.NODE_LINK) {
            submitName(NamePurpose.LINK);
            return;
        }
        if (automatic.commit() == AutomaticNameCommit.Target.CHANNEL) {
            submitName(NamePurpose.CHANNEL);
            return;
        }
        if (current == NodeMenuInteractionPolicy.EditKind.MODE
                && previous == NodeMenuInteractionPolicy.EditKind.NONE
                && modeCommitAfterBegin != null
                && response instanceof NodeMenuResponse.State) {
            NodeMode mode = modeCommitAfterBegin;
            boolean confirmedReset = modeCommitConfirmedReset;
            modeCommitAfterBegin = null;
            modeCommitConfirmedReset = false;
            modal = Modal.NONE;
            pendingMode = null;
            error = null;
            sendMode(mode, confirmedReset);
            return;
        }
        if (response instanceof NodeMenuResponse.Failure) {
            modeCommitAfterBegin = null;
            modeCommitConfirmedReset = false;
        }
        if (response instanceof NodeMenuResponse.State) {
            listScroll = completedPage == PagedListScroll.PageRequest.NONE ? 0 : pageLanding(completedPage);
        }
        modal = interaction.discardConfirmation() ? Modal.DISCARD : Modal.NONE;
        closeAfterDiscard &= interaction.discardConfirmation();
        pendingMode = null;
        error = response instanceof NodeMenuResponse.Failure failure
                ? Component.translatable(failure.reason().translationKey())
                : null;
        if (returnAfterBatch && interaction.authoritative() instanceof NodeMenuState.DirectTunnelList) {
            send(
                    sequence -> new NodeMenuRequest.Back(menu.containerId, menu.sessionId(), sequence),
                    NodeMenuInteractionPolicy.PendingKind.NAVIGATE);
            return;
        }
        if (requestNextTunnelBatch()) {
            return;
        }
        rebuildIfActive();
    }

    @Override
    protected void init() {
        tunnelResultRows.clear();
        presetResultRows.clear();
        resourceRows.clear();
        modalBackdrop.clear();
        layout = TerminalLayout.calculate(width, height);
        imageWidth = layout.window().width();
        imageHeight = layout.window().height();
        super.init();
        font = TerminalText.font(Objects.requireNonNull(minecraft, "minecraft"));
        bodyBounds = layout.content();
        nameField = null;
        searchField = null;
        buildTopBar();
        if (NodeMenuInteractionPolicy.bodyControlsVisible(modal != Modal.NONE)
                && !automaticNameCommit.suppressEditor()) {
            NodeMenuState state = interaction.authoritative();
            if (state instanceof NodeMenuState.BlankList list) {
                buildNetworkList(list.page(), null);
            } else if (state instanceof NodeMenuState.BlankEdit edit) {
                buildNameEditor(edit.network().name(), NamePurpose.LINK);
            } else if (state instanceof NodeMenuState.LinkedRoot root) {
                buildLinkedRoot(root.node());
            } else if (state instanceof NodeMenuState.ModeRoot root) {
                buildLinkedRoot(root.node());
            } else if (state instanceof NodeMenuState.LinkedRename rename) {
                buildNameEditor(rename.node().networkName(), NamePurpose.RENAME);
            } else if (state instanceof NodeMenuState.LinkedMode mode) {
                buildModeEditor(mode.node());
            } else if (state instanceof NodeMenuState.NetworkSelection selection) {
                buildNetworkList(selection.page(), selection.node());
            } else if (state instanceof NodeMenuState.NetworkMoveEdit move) {
                buildNameEditor(move.target().name(), NamePurpose.MOVE);
            } else if (state instanceof NodeMenuState.DirectTunnelList list) {
                buildTunnelList(list);
            } else if (state instanceof NodeMenuState.DirectChannelList list) {
                buildChannelList(list);
            } else if (state instanceof NodeMenuState.DirectChannelRoot root) {
                buildChannelRoot(root);
            } else if (state instanceof NodeMenuState.DirectChannelSettings settings) {
                buildChannelSettings(settings);
            } else if (state instanceof NodeMenuState.DirectTunnelSwitch tunnelSwitch) {
                buildTunnelSwitch(tunnelSwitch);
            } else if (state instanceof NodeMenuState.ResourceEdit edit) {
                buildItemEditor(edit);
            } else if (state instanceof NodeMenuState.DirectChannelEdit edit) {
                buildNameEditor(edit.tunnel().name(), NamePurpose.CHANNEL);
            } else if (state instanceof NodeMenuState.DirectChannelDelete delete) {
                buildChannelDeleteConfirmation(delete);
            } else if (state instanceof NodeMenuState.DomainRoot root) {
                buildDomainRoot(root);
            }
        }
        if (modal != Modal.NONE) {
            modalBackdrop.retain(children(), this::removeWidget);
            setFocused(null);
        }
        buildModal();
        if (modal != Modal.NONE) modalBackdrop.captureForeground(renderables);
    }

    @Override
    protected void setInitialFocus() {
        if (modal == Modal.NONE && searchField != null) {
            setInitialFocus(searchField);
        } else {
            super.setInitialFocus();
        }
    }

    @Override
    protected void containerTick() {
        clientTicks++;
        if (downloads.expire(clientTicks) && policyDownload != null) failPolicyTransfer();
        if (uploadId != null && clientTicks >= transferDeadline) failPolicyTransfer();
        if (uploadBytes != null && uploadOffset < uploadBytes.length) {
            int end = Math.min(uploadBytes.length, uploadOffset + ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES);
            PacketDistributor.sendToServer(new ManagementTransferMessage.Chunk(
                    menu.sessionId(),
                    uploadId,
                    uploadOffset,
                    java.util.Arrays.copyOfRange(uploadBytes, uploadOffset, end)));
            uploadOffset = end;
            if (end == uploadBytes.length) {
                PacketDistributor.sendToServer(new ManagementTransferMessage.Finish(menu.sessionId(), uploadId));
                uploadBytes = null;
            }
        }
        if (interaction.heartbeatDue(clientTicks)) {
            long sequence = nextSequence();
            interaction = interaction.heartbeatSent(sequence, clientTicks);
            PacketDistributor.sendToServer(new NodeMenuRequest.Heartbeat(menu.containerId, menu.sessionId(), sequence));
        }
        if (interaction.authoritative() instanceof NodeMenuState.ResourceEdit
                && !resourceCatalog.ready()
                && !resourceCatalog.failed()
                && !interaction.mutationPending()) {
            catalogRequest = resourceCatalog.nextRequest();
            if (catalogRequest != null)
                send(
                        sequence -> new NodeMenuRequest.ResourceCatalog(
                                menu.containerId, menu.sessionId(), sequence, catalogRequest.offset()),
                        NodeMenuInteractionPolicy.PendingKind.PAGE);
        }
        if (resourceSelection != null && modal == Modal.NONE && resourceSelection.tick(clientTicks))
            updateResourceRows();
        applyLocalTunnelSearch();
        requestPresetQuery();
        if (clientTicks % 20 == 0
                && (interaction.authoritative() instanceof NodeMenuState.DomainRoot
                        || interaction.authoritative() instanceof NodeMenuState.DirectChannelRoot root
                                && root.channel().currentDirection() != null)
                && interaction.expectedBackgroundSequence() == 0
                && !interaction.mutationPending()
                && modal == Modal.NONE) {
            send(
                    sequence -> new NodeMenuRequest.PollItemStatus(menu.containerId, menu.sessionId(), sequence),
                    NodeMenuInteractionPolicy.PendingKind.STATUS);
        }
    }

    private void buildTopBar() {
        TerminalLayout.Rect topBar = TerminalHeaderLayout.topBarContent(layout.window());
        int y = topBar.y();

        NodeMenuNodeSummary node = NodeMenuInteractionPolicy.linkedNode(interaction.authoritative());
        int right = topBar.right();
        int left = topBar.x();
        TerminalHeaderLayout.Action action = topBarAction(
                interaction.authoritative(), choosingItemPreset || resourceSelection != null, resourceSettingsPage);
        TerminalHeaderLayout.ActionLayout actionLayout =
                TerminalHeaderLayout.atRightEdge(topBar, action != TerminalHeaderLayout.Action.NONE);
        if (action != TerminalHeaderLayout.Action.NONE) buildTopBarAction(action, actionLayout.action());
        right = actionLayout.remaining().right();
        TerminalHeaderLayout.NodeNames names =
                TerminalHeaderLayout.nodeNames(actionLayout.remaining(), layout.compact());
        if (node != null) {
            int chunkWidth = layout.compact() ? 64 : 92;
            TerminalButton chunk = new TerminalButton(
                    right - chunkWidth,
                    y,
                    chunkWidth,
                    CONTROL_HEIGHT,
                    Component.translatable(
                            node.chunkLoadingRequested()
                                    ? layout.compact()
                                            ? "omniresonance.node_menu.chunk_request.short.on"
                                            : "omniresonance.node_menu.chunk_request.on"
                                    : layout.compact()
                                            ? "omniresonance.node_menu.chunk_request.short.off"
                                            : "omniresonance.node_menu.chunk_request.off"),
                    button -> toggleChunkRequest(node),
                    false);
            chunk.active = node.enabled()
                    && interaction.editKind() == NodeMenuInteractionPolicy.EditKind.NONE
                    && !interaction.mutationPending()
                    && modal == Modal.NONE;
            chunk.setSelected(node.chunkLoadingRequested());
            chunk.setTooltip(Tooltip.create(
                    TerminalText.body(Component.translatable("omniresonance.node_menu.chunk_request.note"))));
            addRenderableWidget(chunk);
            right = chunk.getX() - TerminalLayout.GAP;

            int enabledWidth = layout.compact() ? 52 : 66;
            TerminalButton enabled = new TerminalButton(
                    right - enabledWidth,
                    y,
                    enabledWidth,
                    CONTROL_HEIGHT,
                    Component.translatable(
                            node.enabled() ? "omniresonance.node_menu.enabled" : "omniresonance.node_menu.disabled"),
                    button -> toggleEnabled(node),
                    false);
            enabled.active = interaction.editKind() == NodeMenuInteractionPolicy.EditKind.NONE
                    && !interaction.mutationPending()
                    && modal == Modal.NONE;
            enabled.setSelected(node.enabled());
            addRenderableWidget(enabled);
            right = enabled.getX() - TerminalLayout.GAP;
        }

        int available = Math.max(0, right - left);
        nodeTitleBounds = new TerminalLayout.Rect(left, y, available, CONTROL_HEIGHT);
        networkTitleBounds = new TerminalLayout.Rect(right, y, 0, CONTROL_HEIGHT);
        String nodeName = node == null
                ? Component.translatable("omniresonance.node_menu.title.unconfigured")
                        .getString()
                : node.nodeName();
        if (node != null && node.enabled() && interaction.editKind() == NodeMenuInteractionPolicy.EditKind.NONE) {
            int nameWidth = names.node().width();
            nodeTitleBounds = new TerminalLayout.Rect(left, y, 0, CONTROL_HEIGHT);
            networkTitleBounds = names.network();
            TerminalButton rename = new TerminalButton(
                    left,
                    y,
                    nameWidth,
                    CONTROL_HEIGHT,
                    Component.literal(ellipsize(nodeName, Math.max(0, nameWidth - 8))),
                    button -> send(
                            sequence -> new NodeMenuRequest.BeginRename(menu.containerId, menu.sessionId(), sequence),
                            NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT),
                    false);
            rename.active = !interaction.mutationPending() && modal == Modal.NONE;
            rename.setTooltip(Tooltip.create(TerminalText.body(Component.literal(nodeName))));
            addRenderableWidget(rename);
            TerminalButton network = buildNetworkHeaderButton(
                    networkTitleBounds,
                    TerminalText.networkLabel(
                            node.networkName(),
                            networkTitleBounds,
                            candidate -> font.width(TerminalText.body(Component.literal(candidate)))),
                    () -> interaction,
                    () -> modal == Modal.NONE,
                    () -> send(
                            sequence -> new NodeMenuRequest.OpenNetworkSelection(
                                    menu.containerId, menu.sessionId(), sequence),
                            NodeMenuInteractionPolicy.PendingKind.NAVIGATE));
            if (network != null) addRenderableWidget(network);
        } else if (node != null) {
            int nameWidth = names.node().width();
            nodeTitleBounds = new TerminalLayout.Rect(left, y, nameWidth, CONTROL_HEIGHT);
            networkTitleBounds = names.network();
        } else if (interaction.authoritative() instanceof NodeMenuState.BlankEdit) {
            int nameWidth = Math.max(0, available * 50 / 100);
            nodeTitleBounds = new TerminalLayout.Rect(left, y, nameWidth, CONTROL_HEIGHT);
            networkTitleBounds = new TerminalLayout.Rect(
                    left + nameWidth + TerminalLayout.GAP,
                    y,
                    Math.max(0, right - left - nameWidth - TerminalLayout.GAP),
                    CONTROL_HEIGHT);
        }
    }

    static TerminalHeaderLayout.Action topBarAction(@Nullable NodeMenuState state, boolean choosingPreset) {
        return choosingPreset && state instanceof NodeMenuState.ResourceEdit
                ? TerminalHeaderLayout.Action.SEARCH
                : NodeMenuInteractionPolicy.topBarAction(state);
    }

    static TerminalHeaderLayout.Action topBarAction(
            @Nullable NodeMenuState state, boolean selecting, boolean settingsPage) {
        return settingsPage && !selecting && state instanceof NodeMenuState.ResourceEdit
                ? TerminalHeaderLayout.Action.CREATE
                : topBarAction(state, selecting);
    }

    private ClientSearchState activeSearch() {
        return resourceSelection != null
                ? resourceSelection.search()
                : choosingItemPreset ? presetPicker.search() : tunnelSearch;
    }

    private static boolean presetReadPending(NodeMenuInteractionPolicy.Model interaction, boolean queryPending) {
        return queryPending
                && interaction.pending() != null
                && interaction.pending().kind() == NodeMenuInteractionPolicy.PendingKind.NAVIGATE;
    }

    private boolean searchEligible() {
        if (resourceSelection != null) return modal == Modal.NONE && !interaction.mutationPending();
        return modal == Modal.NONE
                && (choosingItemPreset
                        ? !interaction.mutationPending() || presetReadPending(interaction, presetPicker.pending())
                        : !interaction.mutationPending()
                                && tunnelCatalog.ready()
                                && NodeMenuInteractionPolicy.topBarAction(interaction.authoritative())
                                        == TerminalHeaderLayout.Action.SEARCH);
    }

    private void buildTopBarAction(TerminalHeaderLayout.Action action, TerminalLayout.Rect bounds) {
        TerminalClickButton button;
        if (action == TerminalHeaderLayout.Action.SEARCH) {
            button = buildSearchButton(bounds, activeSearch(), choosingItemPreset, this::toggleSearch);
        } else if (action == TerminalHeaderLayout.Action.CREATE) {
            button = new TerminalIconButton(
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    Component.translatable(
                            resourceSettingsPage
                                    ? "omniresonance.resource_policy.add_type"
                                    : "omniresonance.node_menu.channel.create"),
                    ignored -> {
                        if (resourceSettingsPage) chooseResourceSetting();
                        else beginAutomaticChannelCreate();
                    });
        } else {
            button = new TerminalSettingsButton(
                    bounds,
                    Component.translatable("omniresonance.node_menu.channel.manage"),
                    ignored -> send(
                            sequence -> new NodeMenuRequest.OpenChannelSettings(
                                    menu.containerId, menu.sessionId(), sequence),
                            NodeMenuInteractionPolicy.PendingKind.NAVIGATE));
        }
        button.active = modal == Modal.NONE
                && (!interaction.mutationPending()
                        || (action == TerminalHeaderLayout.Action.SEARCH
                                && (choosingItemPreset
                                        ? presetReadPending(interaction, presetPicker.pending())
                                        : tunnelSearch.expanded())))
                && (action != TerminalHeaderLayout.Action.SEARCH || choosingItemPreset || tunnelCatalog.ready());
        addRenderableWidget(button);
    }

    private void buildNetworkList(NodeNetworkPage page, @Nullable NodeMenuNodeSummary movingNode) {
        RoutingListLayout listLayout =
                RoutingListLayout.calculate(bodyBounds, page.entries().size(), listScroll);
        listScroll = listLayout.scroll();
        int visibleRows = listLayout.visibleRows();
        for (int row = 0; row < visibleRows; row++) {
            int index = listScroll + row;
            if (index >= page.entries().size()) {
                break;
            }
            NodeNetworkSummary network = page.entries().get(index);
            String role = Component.translatable(
                            network.role() == NodeNetworkSummary.Role.OWNER
                                    ? "omniresonance.node_menu.role.owner"
                                    : "omniresonance.node_menu.role.admin")
                    .getString();
            String label = network.name() + " · " + role;
            TerminalRowButton entry = new TerminalRowButton(
                    listLayout.row(row),
                    Component.literal(label),
                    button -> selectNetwork(network, movingNode != null));
            entry.active = !interaction.mutationPending();
            entry.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label))));
            addRenderableWidget(entry);
        }
    }

    private void buildNameEditor(String networkName, NamePurpose purpose) {
        var dialog = TerminalDialogLayout.editor(bodyBounds);
        int formWidth = dialog.width() - 24;
        int left = dialog.x() + 12;
        int top = dialog.y() + 54;
        nameField = new TerminalEditBox(
                font, left, top, formWidth, CONTROL_HEIGHT, Component.translatable("omniresonance.node_menu.name"));
        nameField.setMaxLength(EDIT_BOX_MAXIMUM_UTF16_UNITS);
        settingDraft = true;
        nameField.setValue(draft);
        settingDraft = false;
        nameField.setResponder(this::updateDraft);
        nameField.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(nameField);

        var footer = TerminalActionLayout.of(dialog);
        int actionWidth = footer.primary().width();
        TerminalButton cancel = new TerminalButton(
                footer.secondary().x(),
                footer.secondary().y(),
                actionWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> navigateBack(),
                false);
        cancel.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(cancel);
        TerminalButton save = new TerminalButton(
                footer.primary().x(),
                footer.primary().y(),
                actionWidth,
                CONTROL_HEIGHT,
                Component.translatable(
                        purpose == NamePurpose.LINK
                                ? "omniresonance.node_menu.link"
                                : purpose == NamePurpose.MOVE
                                        ? "omniresonance.node_menu.network.move"
                                        : purpose == NamePurpose.CHANNEL
                                                        && interaction.authoritative()
                                                                instanceof NodeMenuState.DirectChannelEdit edit
                                                        && edit.existing() == null
                                                ? "omniresonance.node_menu.channel.create"
                                                : "omniresonance.node_menu.save"),
                button -> submitName(purpose),
                true);
        save.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(save);
    }

    private void buildLinkedRoot(NodeMenuNodeSummary node) {
        if (!node.enabled()) {
            return;
        }
        List<TerminalLayout.Rect> cards = NodeModeLayout.cards(bodyBounds);
        addModeCard(cards.get(0), node, NodeMode.DIRECT, "直");
        addModeCard(cards.get(1), node, NodeMode.DOMAIN, "域");
    }

    private void addModeCard(TerminalLayout.Rect bounds, NodeMenuNodeSummary node, NodeMode mode, String mark) {
        TerminalCardButton card = new TerminalCardButton(
                bounds,
                Component.literal(mark),
                Component.translatable(modeKey(mode)),
                Component.translatable(
                        mode == NodeMode.DIRECT
                                ? "omniresonance.node_menu.mode.direct.description"
                                : "omniresonance.node_menu.mode.domain.description"),
                ignored -> modeCardPressed(node, mode));
        card.active = !interaction.mutationPending() && modal == Modal.NONE;
        card.setTooltip(Tooltip.create(TerminalText.body(Component.translatable(
                mode == NodeMode.DIRECT
                        ? "omniresonance.node_menu.mode.direct.description"
                        : "omniresonance.node_menu.mode.domain.description"))));
        addRenderableWidget(card);
    }

    private void buildModeEditor(NodeMenuNodeSummary node) {
        int width = Math.min(440, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - width) / 2;
        int top = bodyBounds.y() + 56;
        int half = Math.max(0, (width - TerminalLayout.GAP) / 2);
        TerminalButton direct = new TerminalButton(
                left,
                top,
                half,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.mode.direct"),
                button -> chooseMode(node, NodeMode.DIRECT),
                false);
        direct.active = !interaction.mutationPending() && modal == Modal.NONE;
        direct.setSelected(node.mode() == NodeMode.DIRECT);
        addRenderableWidget(direct);
        TerminalButton domain = new TerminalButton(
                left + half + TerminalLayout.GAP,
                top,
                half,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.mode.domain"),
                button -> chooseMode(node, NodeMode.DOMAIN),
                false);
        domain.active = !interaction.mutationPending() && modal == Modal.NONE;
        domain.setSelected(node.mode() == NodeMode.DOMAIN);
        addRenderableWidget(domain);
        var cancelBounds = TerminalActionLayout.button(bodyBounds, 1, 0);
        TerminalButton cancel = new TerminalButton(
                cancelBounds.x(),
                cancelBounds.y(),
                cancelBounds.width(),
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> navigateBack(),
                false);
        cancel.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(cancel);
    }

    private void buildTunnelList(NodeMenuState.DirectTunnelList state) {
        if (!tunnelCatalog.ready()) {
            if (tunnelCatalog.failed()) {
                TerminalButton retry = new TerminalButton(
                        bodyBounds.x() + 5,
                        bodyBounds.y() + 58,
                        Math.min(120, Math.max(0, bodyBounds.width() - 10)),
                        CONTROL_HEIGHT,
                        Component.translatable("omniresonance.terminal.retry"),
                        ignored -> retryTunnelCatalog(),
                        true);
                retry.active = !interaction.mutationPending() && modal == Modal.NONE;
                addRenderableWidget(retry);
            }
            return;
        }
        if (tunnelSearch.expanded()) {
            TerminalLayout.Rect searchBounds = NodeRoutingView.tunnelSearchBounds(bodyBounds);
            Component label = Component.translatable("omniresonance.node_menu.tunnel.search");
            searchField = new TerminalSearchBox(
                    font, searchBounds.x(), searchBounds.y(), searchBounds.width(), searchBounds.height(), label);
            searchField.setMaxLength(EDIT_BOX_MAXIMUM_UTF16_UNITS);
            searchField.setHint(TerminalText.body(label));
            settingDraft = true;
            searchField.setValue(tunnelSearch.draft());
            settingDraft = false;
            searchField.setResponder(this::updateSearchDraft);
            searchField.active = !interaction.mutationPending() && modal == Modal.NONE;
            addRenderableWidget(searchField);
        }
        updateTunnelResultRows();
    }

    private void updateTunnelResultRows() {
        tunnelResultRows.clear();
        List<NodeTunnelSummary> entries = tunnelRows();
        RoutingListLayout listLayout =
                NodeRoutingView.tunnelList(bodyBounds, tunnelSearch.expanded(), entries.size(), listScroll);
        listScroll = listLayout.scroll();
        for (int row = 0; row < listLayout.visibleRows(); row++) {
            int index = listScroll + row;
            if (index >= entries.size()) {
                break;
            }
            NodeTunnelSummary tunnel = entries.get(index);
            String label = NodeRoutingView.tunnelLabel(tunnel).getString();
            TerminalRowButton entry = new TerminalRowButton(
                    listLayout.row(row),
                    Component.literal(label),
                    button -> send(
                            sequence -> new NodeMenuRequest.OpenTunnel(
                                    menu.containerId, menu.sessionId(), sequence, tunnel.tunnelId()),
                            NodeMenuInteractionPolicy.PendingKind.NAVIGATE));
            entry.active = !interaction.mutationPending() && modal == Modal.NONE;
            entry.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label))));
            tunnelResultRows.add(entry);
        }
    }

    private void buildChannelList(NodeMenuState.DirectChannelList state) {
        TerminalLayout.Rect listBody = channelListBody();
        RoutingListLayout listLayout =
                RoutingListLayout.calculate(listBody, state.page().entries().size(), listScroll);
        listScroll = listLayout.scroll();
        for (int row = 0; row < listLayout.visibleRows(); row++) {
            int index = listScroll + row;
            if (index >= state.page().entries().size()) {
                break;
            }
            NodeChannelSummary channel = state.page().entries().get(index);
            String direction = channel.currentDirection() == null
                    ? Component.translatable("omniresonance.node_menu.direction.none")
                            .getString()
                    : directionText(channel.currentDirection()).getString();
            String label = NodeRoutingView.channelLabel(channel, direction).getString();
            TerminalRowButton entry = new TerminalRowButton(
                    listLayout.row(row),
                    Component.literal(label),
                    button -> send(
                            sequence -> new NodeMenuRequest.OpenChannel(
                                    menu.containerId, menu.sessionId(), sequence, channel.channelId()),
                            NodeMenuInteractionPolicy.PendingKind.NAVIGATE));
            entry.active = !interaction.mutationPending() && modal == Modal.NONE;
            entry.setTooltip(Tooltip.create(TerminalText.body(Component.literal(label))));
            addRenderableWidget(entry);
        }
    }

    private void buildChannelRoot(NodeMenuState.DirectChannelRoot state) {
        int width = Math.min(420, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - width) / 2;
        int y = bodyBounds.bottom() - CONTROL_HEIGHT - 12;
        if (state.channel().currentDirection() == null) {
            addChannelAction(
                    new TerminalLayout.Rect(left, y, width, CONTROL_HEIGHT),
                    "omniresonance.node_menu.channel.join",
                    () -> beginBinding(state.channel().channelId()),
                    true,
                    true);
            return;
        }
    }

    private void buildChannelSettings(NodeMenuState.DirectChannelSettings state) {
        int width = Math.min(420, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - width) / 2;
        int half = Math.max(0, (width - TerminalLayout.GAP) / 2);
        int y = bodyBounds.y() + 56;
        if (state.channel().currentDirection() != null) {
            addChannelAction(
                    new TerminalLayout.Rect(left, y, half, CONTROL_HEIGHT),
                    "omniresonance.node_menu.channel.configure",
                    () -> beginBinding(state.channel().channelId()),
                    false,
                    true);
            addChannelAction(
                    new TerminalLayout.Rect(left + half + TerminalLayout.GAP, y, half, CONTROL_HEIGHT),
                    "omniresonance.node_menu.binding.exit",
                    () -> openRemoveConfirmation(true),
                    false,
                    true);
            y += CONTROL_HEIGHT + TerminalLayout.GAP;
        }
        addChannelAction(
                new TerminalLayout.Rect(left, y, half, CONTROL_HEIGHT),
                "omniresonance.node_menu.channel.rename",
                () -> send(
                        sequence -> new NodeMenuRequest.BeginRenameChannel(
                                menu.containerId,
                                menu.sessionId(),
                                sequence,
                                state.channel().channelId()),
                        NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT),
                false,
                true);
        addChannelAction(
                new TerminalLayout.Rect(left + half + TerminalLayout.GAP, y, half, CONTROL_HEIGHT),
                "omniresonance.node_menu.channel.delete",
                () -> send(
                        sequence -> new NodeMenuRequest.RequestDeleteChannel(
                                menu.containerId,
                                menu.sessionId(),
                                sequence,
                                state.channel().channelId()),
                        NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT),
                false,
                true);
    }

    private void buildTunnelSwitch(NodeMenuState.DirectTunnelSwitch state) {
        var footer = TerminalActionLayout.of(
                TerminalDialogLayout.confirmation(bodyBounds, font, tunnelSwitchMessage(state)));
        addChannelAction(footer.secondary(), "omniresonance.node_menu.cancel", this::navigateBack, false, true);
        addChannelAction(
                footer.primary(),
                "omniresonance.node_menu.tunnel.switch.confirm",
                () -> send(
                        sequence ->
                                new NodeMenuRequest.ConfirmTunnelSwitch(menu.containerId, menu.sessionId(), sequence),
                        NodeMenuInteractionPolicy.PendingKind.SAVE),
                true,
                true);
    }

    void applyTransfer(ManagementTransferMessage message) {
        if (!message.session().equals(menu.sessionId())) return;
        if (message instanceof ManagementTransferMessage.Abort
                && ((policyDownload != null && policyDownload.transfer().equals(message.transfer()))
                        || message.transfer().equals(uploadId))) {
            failPolicyTransfer();
            return;
        }
        if (policyDownload == null || !policyDownload.transfer().equals(message.transfer())) return;
        try {
            if (message instanceof ManagementTransferMessage.Chunk chunk) downloads.append(chunk, clientTicks);
            else if (message instanceof ManagementTransferMessage.Finish finish) {
                NodeMenuResponse.Download metadata = policyDownload;
                ResourcePolicyEdit[] decoded = new ResourcePolicyEdit[1];
                downloads.finish(finish, clientTicks, view -> decoded[0] = ResourcePolicyEditCodec.decode(view));
                policyDownload = null;
                if (decoded[0] != null)
                    applyResponse(new NodeMenuResponse.State(
                            menu.containerId,
                            menu.sessionId(),
                            metadata.sequence(),
                            metadata.metadata().withPolicy(decoded[0])));
            } else throw new IllegalArgumentException("Unexpected download frame");
        } catch (RuntimeException invalid) {
            failPolicyTransfer();
        }
    }

    private void cancelPolicyTransfer() {
        UUID id = policyDownload != null ? policyDownload.transfer() : uploadId;
        if (id != null) {
            downloads.abort(menu.sessionId(), id);
            PacketDistributor.sendToServer(new ManagementTransferMessage.Abort(menu.sessionId(), id));
        }
        policyDownload = null;
        uploadId = null;
        uploadPolicy = null;
        uploadBytes = null;
        uploadOffset = 0;
    }

    private void failPolicyTransfer() {
        cancelPolicyTransfer();
        if (interaction.pending() != null)
            applyResponse(new NodeMenuResponse.Failure(
                    menu.containerId,
                    menu.sessionId(),
                    interaction.pending().sequence(),
                    NodeMenuResponse.Reason.UNAVAILABLE,
                    interaction.authoritative()));
    }

    private void buildItemEditor(NodeMenuState.ResourceEdit edit) {
        if (!resourceCatalog.ready() || itemDraft == null && edit.policy() == null) return;
        if (itemDraft == null)
            itemDraft = new NodeResourcePolicyDraft(
                    edit.policy(),
                    edit.selectedPresetName(),
                    resourceCatalog.snapshot(),
                    edit instanceof NodeMenuState.DomainEdit);
        if (faceDraft == null)
            faceDraft = new NodeWorkingFacesDraft(
                    edit.workingFaces(), edit.node().form(), edit.node().facing());
        if (resourceSelection != null) {
            buildResourceSelection();
            return;
        }
        if (resourceSettingsPage) {
            NodeResourceSettingsView.buildList(
                    bodyBounds,
                    itemDraft,
                    resourceSettingsScroll,
                    !interaction.mutationPending(),
                    this::addRenderableWidget,
                    this::editResourceSetting);
            return;
        }
        if (faceDraft.openNow()) {
            buildFaceSelector(edit);
            return;
        }
        if (choosingItemPreset) {
            buildItemPresetChoices(edit);
            return;
        }
        var cancelBounds = TerminalActionLayout.of(bodyBounds).secondary();
        TerminalButton cancel = new TerminalButton(
                cancelBounds.x(),
                cancelBounds.y(),
                cancelBounds.width(),
                cancelBounds.height(),
                Component.translatable("omniresonance.node_menu.cancel"),
                ignored -> navigateBack(),
                false);
        cancel.active = !interaction.mutationPending();
        addRenderableWidget(cancel);
        buildResourceForm(
                font,
                bodyBounds,
                itemFormScroll,
                itemDraft,
                interaction.mutationPending(),
                this::addRenderableWidget,
                faceDraft.panel()
                        ? NodeFaceSelectorView.text("attached")
                        : faceDraft.value().mask() == 0
                                ? NodeFaceSelectorView.text("empty")
                                : Component.translatable(
                                        "omniresonance.working_faces.count",
                                        Integer.bitCount(faceDraft.value().mask())),
                new NodeResourcePolicyView.Actions(
                        this::markItemDirty,
                        this::rebuildIfActive,
                        () -> {
                            choosingItemPreset = true;
                            presetPicker.open();
                            listScroll = 0;
                            rebuildIfActive();
                        },
                        () -> {
                            modal = Modal.ITEM_DIRECTION;
                            rebuildIfActive();
                        },
                        () -> {
                            faceDraft.open();
                            faceScroll = 0;
                            rebuildIfActive();
                        },
                        () -> {
                            resourceSelection = NodeResourceTypeSelection.scope(
                                    itemDraft,
                                    itemDraft.openScope(),
                                    id -> NodeResourcePolicyView.typeName(id).getString());
                            rebuildIfActive();
                        },
                        () -> {
                            resourceSettingsPage = true;
                            resourceSettingsScroll = 0;
                            rebuildIfActive();
                        },
                        this::saveItemPolicy));
    }

    private void chooseResourceSetting() {
        resourceSelection = NodeResourceTypeSelection.overrides(
                itemDraft, id -> NodeResourcePolicyView.typeName(id).getString());
        rebuildIfActive();
    }

    private void editResourceSetting(net.minecraft.resources.ResourceLocation id) {
        typeEditor = new NodeResourceSettingEditor(itemDraft, id);
        modal = Modal.TYPE_EDIT;
        rebuildIfActive();
    }

    private void applyResourceSetting() {
        try {
            typeEditor.apply();
            typeEditor = null;
            modal = Modal.NONE;
            markItemDirty();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            typeEditor.invalid = true;
        }
        rebuildIfActive();
    }

    private void restoreResourceSetting() {
        itemDraft.restoreDefault(typeEditor.id);
        typeEditor = null;
        modal = Modal.NONE;
        markItemDirty();
        rebuildIfActive();
    }

    static NodeResourcePolicyView.Layout buildResourceForm(
            net.minecraft.client.gui.Font font,
            TerminalLayout.Rect body,
            int scroll,
            NodeResourcePolicyDraft draft,
            boolean pending,
            java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> add,
            Component faces,
            NodeResourcePolicyView.Actions actions) {
        var form = NodeResourcePolicyView.layout(body, scroll, draft);
        NodeResourcePolicyView.build(font, form, draft, !pending, add, faces, actions);
        var save = TerminalActionLayout.of(body).primary();
        TerminalButton submit = new TerminalButton(
                save.x(),
                save.y(),
                save.width(),
                save.height(),
                Component.translatable("omniresonance.node_menu.save"),
                ignored -> actions.save().run(),
                true);
        submit.active = !pending;
        add.accept(submit);
        return form;
    }

    static int scrollResourceForm(TerminalLayout.Rect body, int scroll, NodeResourcePolicyDraft draft, double wheel) {
        var form = NodeResourcePolicyView.layout(body, scroll, draft);
        return PagedListScroll.navigate(form.firstRow(), form.totalRows(), form.visibleRows(), false, false, wheel)
                .scroll();
    }

    private void buildResourceSelection() {
        resourceRows.clear();
        var selectionLayout = NodeResourceTypeSelectionView.layout(bodyBounds, resourceSelection);
        searchField = NodeResourceTypeSelectionView.buildSearch(
                font, selectionLayout, resourceSelection, () -> clientTicks, () -> {});
        if (searchField != null) addRenderableWidget(searchField);
        updateResourceRows();
        NodeResourceTypeSelectionView.buildScopeActions(
                selectionLayout,
                resourceSelection,
                !interaction.mutationPending(),
                this::addRenderableWidget,
                this::rebuildIfActive,
                () -> applyResourceScope(false),
                () -> {
                    resourceSelection = null;
                    rebuildIfActive();
                });
    }

    private void updateResourceRows() {
        if (resourceSelection == null) return;
        for (var row : resourceRows) removeWidget(row);
        resourceRows.clear();
        NodeResourceTypeSelectionView.buildRows(
                NodeResourceTypeSelectionView.layout(bodyBounds, resourceSelection),
                resourceSelection,
                !interaction.mutationPending(),
                row -> {
                    resourceRows.add(row);
                    addRenderableWidget(row);
                },
                () -> {
                    if (resourceSelection.scope() == null) {
                        var chosen = resourceSelection.chosen();
                        markItemDirty();
                        resourceSelection = null;
                        if (chosen != null) editResourceSetting(chosen);
                    }
                    rebuildIfActive();
                });
    }

    private void applyResourceScope(boolean confirmed) {
        if (resourceSelection == null || resourceSelection.scope() == null) return;
        if (!confirmed && itemDraft.removedOverrideCount(resourceSelection.scope()) > 0) {
            modal = Modal.RESOURCE_SCOPE;
            rebuildIfActive();
            return;
        }
        itemDraft.applyScope(resourceSelection.scope(), confirmed);
        resourceSelection = null;
        if (itemDraft.dirty()) markItemDirty();
        rebuildIfActive();
    }

    private void buildFaceSelector(NodeMenuState.ResourceEdit edit) {
        var faceLayout = NodeFaceSelectorView.layout(bodyBounds, faceScroll, faceDraft.panel());
        faceScroll = faceLayout.firstRow();
        NodeFaceSelectorView.build(
                faceLayout,
                faceDraft,
                edit.node().facing(),
                edit.previews(),
                !interaction.mutationPending(),
                this::addRenderableWidget,
                () -> {
                    markItemDirty();
                    rebuildIfActive();
                },
                () -> {
                    faceDraft.close();
                    rebuildIfActive();
                });
        addChannelAction(
                new TerminalLayout.Rect(
                        faceLayout.actions().right() - 64, faceLayout.actions().y(), 64, 20),
                "omniresonance.working_faces.refresh",
                () -> pageItemPresets(edit.presets().offset()),
                false,
                true);
    }

    private void syncResourceDirty() {
        if (itemDraft != null && interaction.authoritative() instanceof NodeMenuState.ResourceEdit edit) {
            boolean changed = resourceDirty(itemDraft, resourceSelection, faceDraft, edit)
                    || typeEditor != null && typeEditor.dirty();
            interaction = interaction.resourceDraftDirty(changed);
        }
    }

    static boolean resourceDirty(
            NodeResourcePolicyDraft draft,
            @Nullable NodeResourceTypeSelection selection,
            @Nullable NodeWorkingFacesDraft faces,
            NodeMenuState.ResourceEdit edit) {
        return draft.dirtyIncluding(selection == null ? null : selection.scope())
                || faces != null
                        && faces.value().effectiveMask(edit.node().facing())
                                != edit.workingFaces().effectiveMask(edit.node().facing());
    }

    static NodeMenuRequest resourceSaveRequest(
            int containerId,
            UUID sessionId,
            long sequence,
            NodeResourcePolicyDraft draft,
            NodeWorkingFacesDraft faces,
            UUID transferId) {
        ResourcePolicyEdit policy = draft.edit();
        return NodePolicyFrames.saveSize(containerId, policy) <= NodePolicyFrames.MAXIMUM_BYTES
                ? new NodeMenuRequest.SaveResourcePolicy(
                        containerId,
                        sessionId,
                        sequence,
                        policy,
                        faces.value(),
                        policy.discardPreviousDirectionFields())
                : new NodeMenuRequest.BeginPolicyUpload(
                        containerId,
                        sessionId,
                        sequence,
                        transferId,
                        ResourcePolicyEditCodec.encodedSize(policy),
                        faces.value(),
                        policy.discardPreviousDirectionFields());
    }

    private void markItemDirty() {
        syncResourceDirty();
        error = null;
    }

    private void saveItemPolicy() {
        if (itemDraft == null) return;
        ResourcePolicyEdit policy;
        int frameSize;
        try {
            policy = itemDraft.edit();
            frameSize = NodePolicyFrames.saveSize(menu.containerId, policy);
        } catch (IllegalArgumentException invalid) {
            error = NodeResourcePolicyView.text("invalid");
            return;
        }
        if (frameSize > NodePolicyFrames.MAXIMUM_BYTES) {
            UUID id = UUID.randomUUID();
            uploadPolicy = policy;
            uploadId = id;
            uploadOffset = 0;
            transferDeadline = clientTicks + 200;
            if (!send(
                    sequence ->
                            resourceSaveRequest(menu.containerId, menu.sessionId(), sequence, itemDraft, faceDraft, id),
                    NodeMenuInteractionPolicy.PendingKind.SAVE)) {
                uploadId = null;
                uploadPolicy = null;
            }
            return;
        }
        send(
                sequence -> resourceSaveRequest(
                        menu.containerId, menu.sessionId(), sequence, itemDraft, faceDraft, new UUID(0, 0)),
                NodeMenuInteractionPolicy.PendingKind.SAVE);
    }

    private TerminalLayout.Rect itemPickerBody() {
        return NodePresetPickerView.rows(bodyBounds, presetPicker.search().expanded());
    }

    private void buildItemPresetChoices(NodeMenuState.ResourceEdit edit) {
        searchField = NodePresetPickerView.buildSearch(font, bodyBounds, presetPicker, this::updatePresetResultRows);
        if (searchField != null) addRenderableWidget(searchField);
        updatePresetResultRows();
    }

    private void updatePresetResultRows() {
        presetResultRows.clear();
        NodePresetPickerView.buildRows(
                bodyBounds,
                presetPicker,
                !interaction.mutationPending() && modal == Modal.NONE,
                presetResultRows::add,
                preset -> {
                    itemDraft.presetId = preset == null ? null : preset.id();
                    itemDraft.presetName = preset == null ? null : preset.name();
                    choosingItemPreset = false;
                    presetPicker.close();
                    markItemDirty();
                    rebuildIfActive();
                });
    }

    private void requestPresetQuery() {
        if (!choosingItemPreset || interaction.mutationPending() || modal != Modal.NONE || minecraft == null) return;
        NodePresetPicker.Request request = presetPicker.nextRequest();
        if (request == null) return;
        long sequence = nextSequence();
        interaction = interaction.submit(NodeMenuInteractionPolicy.PendingKind.NAVIGATE, sequence);
        error = null;
        PacketDistributor.sendToServer(new NodeMenuRequest.PageItemPresets(
                menu.containerId,
                menu.sessionId(),
                sequence,
                request.offset(),
                request.query(),
                request.libraryRevision()));
        updatePresetResultRows();
    }

    private void pageItemPresets(int offset) {
        send(
                sequence -> new NodeMenuRequest.PageItemPresets(menu.containerId, menu.sessionId(), sequence, offset),
                NodeMenuInteractionPolicy.PendingKind.NAVIGATE);
    }

    private void buildDirectionEditor(@Nullable TransferDirection original, boolean directBinding) {
        NodeDirectionView.Draft current =
                directionDraft == null ? NodeDirectionView.Draft.start(original) : directionDraft;
        directionDraft = current;
        int width = Math.min(440, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - width) / 2;
        int top = bodyBounds.y() + 52;
        TerminalButton direction = new TerminalButton(
                left,
                top,
                width,
                CONTROL_HEIGHT,
                directionText(current.selected()),
                button -> directionClicked(original, directBinding),
                true);
        direction.active = !interaction.mutationPending() && modal == Modal.NONE;
        direction.setSelected(true);
        addRenderableWidget(direction);

        var footer = TerminalActionLayout.of(bodyBounds);
        int actionWidth = footer.primary().width();
        TerminalButton cancel = new TerminalButton(
                footer.secondary().x(),
                footer.secondary().y(),
                actionWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> navigateBack(),
                false);
        cancel.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(cancel);
        if (current.canRemove()) {
            TerminalButton remove = new TerminalButton(
                    footer.primary().x(),
                    footer.primary().y(),
                    actionWidth,
                    CONTROL_HEIGHT,
                    Component.translatable(
                            directBinding
                                    ? "omniresonance.node_menu.binding.exit"
                                    : "omniresonance.node_menu.domain.remove"),
                    button -> openRemoveConfirmation(directBinding),
                    false);
            remove.active = !interaction.mutationPending() && modal == Modal.NONE;
            addRenderableWidget(remove);
        } else {
            TerminalButton save = new TerminalButton(
                    footer.primary().x(),
                    footer.primary().y(),
                    actionWidth,
                    CONTROL_HEIGHT,
                    Component.translatable("omniresonance.node_menu.save"),
                    button -> submitDirection(directBinding),
                    true);
            save.active = !interaction.mutationPending() && modal == Modal.NONE;
            addRenderableWidget(save);
        }
    }

    private void buildDomainRoot(NodeMenuState.DomainRoot state) {
        int width = Math.min(360, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - width) / 2;
        int buttonY = bodyBounds.bottom() - CONTROL_HEIGHT - 12;
        TerminalButton configure = new TerminalButton(
                left,
                buttonY,
                width,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.domain.configure"),
                button -> send(
                        sequence -> new NodeMenuRequest.BeginDomainEdit(menu.containerId, menu.sessionId(), sequence),
                        NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT),
                true);
        configure.active = state.node().enabled() && !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(configure);
    }

    private void buildChannelDeleteConfirmation(NodeMenuState.DirectChannelDelete state) {
        var footer = TerminalActionLayout.of(
                TerminalDialogLayout.confirmation(bodyBounds, font, channelDeleteMessage(state)));
        TerminalButton cancel = new TerminalButton(
                footer.secondary().x(),
                footer.secondary().y(),
                footer.secondary().width(),
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> navigateBack(),
                false);
        cancel.active = !interaction.mutationPending();
        addRenderableWidget(cancel);
        TerminalButton delete = new TerminalButton(
                footer.primary().x(),
                footer.primary().y(),
                footer.primary().width(),
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.channel.delete"),
                button -> send(
                        sequence ->
                                new NodeMenuRequest.ConfirmDeleteChannel(menu.containerId, menu.sessionId(), sequence),
                        NodeMenuInteractionPolicy.PendingKind.SAVE),
                true);
        delete.active = !interaction.mutationPending();
        addRenderableWidget(delete);
    }

    private void addChannelAction(
            TerminalLayout.Rect bounds, String key, Runnable action, boolean primary, boolean active) {
        TerminalButton button = new TerminalButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                Component.translatable(key),
                ignored -> action.run(),
                primary);
        button.active = active && !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(button);
    }

    private void buildModal() {
        if (modal == Modal.NONE) {
            return;
        }
        if (modal == Modal.TYPE_EDIT) {
            modalBounds = NodeResourceSettingsView.dialog(bodyBounds, typeEditor);
            NodeResourceSettingsView.buildEditor(
                    font,
                    bodyBounds,
                    typeEditor,
                    !interaction.mutationPending(),
                    this::addRenderableWidget,
                    this::syncResourceDirty,
                    this::rebuildIfActive,
                    this::closeModal,
                    this::applyResourceSetting,
                    this::restoreResourceSetting);
            return;
        }
        modalBounds = TerminalDialogLayout.confirmation(bodyBounds, font, modalMessage());
        var footer = TerminalActionLayout.of(modalBounds);
        TerminalButton secondary = new TerminalButton(
                footer.secondary().x(),
                footer.secondary().y(),
                footer.secondary().width(),
                CONTROL_HEIGHT,
                Component.translatable(
                        (modal == Modal.DISCARD || modal == Modal.TYPE_DISCARD)
                                ? "omniresonance.node_menu.confirm.continue"
                                : "omniresonance.node_menu.cancel"),
                button -> closeModal(),
                false);
        addRenderableWidget(secondary);
        TerminalButton primary = new TerminalButton(
                footer.primary().x(),
                footer.primary().y(),
                footer.primary().width(),
                CONTROL_HEIGHT,
                Component.translatable(
                        modal == Modal.MODE
                                ? "omniresonance.node_menu.confirm.mode.action"
                                : modal == Modal.ITEM_DIRECTION
                                        ? "omniresonance.item_policy.switch.confirm"
                                        : (modal == Modal.DISCARD || modal == Modal.TYPE_DISCARD)
                                                ? "omniresonance.node_menu.confirm.discard"
                                                : modal == Modal.DISABLE
                                                        ? "omniresonance.node_menu.disable"
                                                        : modal == Modal.REMOVE_BINDING
                                                                ? "omniresonance.node_menu.binding.exit"
                                                                : modal == Modal.REMOVE_DOMAIN
                                                                        ? "omniresonance.node_menu.domain.remove"
                                                                        : "omniresonance.node_menu.save"),
                button -> confirmModal(),
                true);
        addRenderableWidget(primary);
    }

    private void requestPage(NodeNetworkPage page, boolean backwards) {
        List<NodeNetworkSummary> entries = page.entries();
        if (entries.isEmpty()) {
            return;
        }
        UUID anchor =
                backwards ? entries.getFirst().networkId() : entries.getLast().networkId();
        if (send(
                sequence -> new NodeMenuRequest.Page(menu.containerId, menu.sessionId(), sequence, anchor, backwards),
                NodeMenuInteractionPolicy.PendingKind.PAGE)) {
            pendingPageRequest = pageRequest(backwards);
        }
    }

    private boolean requestNextTunnelBatch() {
        if (!tunnelCatalog.loading()
                || interaction.mutationPending()
                || modal != Modal.NONE
                || !(interaction.authoritative() instanceof NodeMenuState.DirectTunnelList)) {
            return false;
        }
        UUID anchor = tunnelCatalog.nextAnchor();
        tunnelCatalogPagePending = true;
        boolean sent = send(
                sequence ->
                        new NodeMenuRequest.PageTunnels(menu.containerId, menu.sessionId(), sequence, anchor, false),
                NodeMenuInteractionPolicy.PendingKind.PAGE);
        if (!sent) {
            tunnelCatalogPagePending = false;
        }
        return sent;
    }

    private void retryTunnelCatalog() {
        if (interaction.mutationPending()) {
            return;
        }
        tunnelCatalog.reset();
        tunnelSearch.reset();
        appliedTunnelSearch = "";
        tunnelCatalogPagePending = false;
        send(
                sequence -> new NodeMenuRequest.PageTunnels(menu.containerId, menu.sessionId(), sequence, null, false),
                NodeMenuInteractionPolicy.PendingKind.PAGE);
    }

    private List<NodeTunnelSummary> tunnelRows() {
        return ClientTextSearch.filter(tunnelCatalog, appliedTunnelSearch);
    }

    private void requestChannelPage(NodeChannelPage page, boolean backwards) {
        if (page.entries().isEmpty()) {
            return;
        }
        UUID anchor = backwards
                ? page.entries().getFirst().channelId()
                : page.entries().getLast().channelId();
        if (send(
                sequence -> new NodeMenuRequest.PageChannels(
                        menu.containerId, menu.sessionId(), sequence, anchor, backwards),
                NodeMenuInteractionPolicy.PendingKind.PAGE)) {
            pendingPageRequest = pageRequest(backwards);
        }
    }

    private void selectNetwork(NodeNetworkSummary network, boolean moving) {
        if (moving) {
            send(
                    sequence -> new NodeMenuRequest.BeginNetworkMove(
                            menu.containerId, menu.sessionId(), sequence, network.networkId()),
                    NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT);
        } else {
            boolean sent = send(
                    sequence -> new NodeMenuRequest.BeginBlank(
                            menu.containerId, menu.sessionId(), sequence, network.networkId()),
                    NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT);
            if (sent) {
                automaticNameCommit = automaticNameCommit.arm(AutomaticNameCommit.Target.NODE_LINK);
            }
        }
    }

    private void beginAutomaticChannelCreate() {
        boolean sent = send(
                sequence -> new NodeMenuRequest.BeginCreateChannel(
                        menu.containerId,
                        menu.sessionId(),
                        sequence,
                        Component.translatable("omniresonance.node_menu.channel.prefix")
                                .getString()),
                NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT);
        if (sent) {
            automaticNameCommit = automaticNameCommit.arm(AutomaticNameCommit.Target.CHANNEL);
        }
    }

    private void beginBinding(UUID channelId) {
        send(
                sequence -> new NodeMenuRequest.BeginBinding(menu.containerId, menu.sessionId(), sequence, channelId),
                NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT);
    }

    private void submitName(NamePurpose purpose) {
        String canonical;
        try {
            canonical = new ManagedName(draft).value();
        } catch (IllegalArgumentException invalidName) {
            error = Component.translatable(NodeMenuResponse.Reason.INVALID_NAME.translationKey());
            rebuildIfActive();
            return;
        }
        send(
                sequence -> switch (purpose) {
                    case LINK -> new NodeMenuRequest.Link(menu.containerId, menu.sessionId(), sequence, canonical);
                    case RENAME -> new NodeMenuRequest.Rename(menu.containerId, menu.sessionId(), sequence, canonical);
                    case MOVE ->
                        new NodeMenuRequest.MoveNetwork(menu.containerId, menu.sessionId(), sequence, canonical);
                    case CHANNEL ->
                        new NodeMenuRequest.SaveChannel(menu.containerId, menu.sessionId(), sequence, canonical);
                },
                NodeMenuInteractionPolicy.PendingKind.SAVE);
    }

    private void selectDirection(TransferDirection direction) {
        if (directionDraft == null || interaction.mutationPending()) {
            return;
        }
        NodeDirectionView.Draft selected = directionDraft.select(direction);
        if (selected == directionDraft) {
            return;
        }
        directionDraft = selected;
        if (!interaction.dirty()) {
            interaction = interaction.edited();
        }
        error = null;
        rebuildIfActive();
    }

    private void submitDirection(boolean directBinding) {
        if (directionDraft == null) {
            return;
        }
        sendDirection(directBinding, directionDraft.selected(), false);
    }

    private void directionClicked(@Nullable TransferDirection original, boolean directBinding) {
        if (original == null) {
            NodeDirectionView.Draft current = Objects.requireNonNull(directionDraft, "directionDraft");
            selectDirection(NodeDirectionView.opposite(current.selected()));
            return;
        }
        sendDirection(directBinding, NodeDirectionView.opposite(original), true);
    }

    private void sendDirection(boolean directBinding, TransferDirection selected, boolean confirmedReset) {
        send(
                sequence -> directBinding
                        ? new NodeMenuRequest.SetBindingDirection(
                                menu.containerId, menu.sessionId(), sequence, selected, confirmedReset)
                        : new NodeMenuRequest.SetDomainDirection(
                                menu.containerId, menu.sessionId(), sequence, selected, confirmedReset),
                NodeMenuInteractionPolicy.PendingKind.SAVE);
    }

    private void openRemoveConfirmation(boolean directBinding) {
        modal = directBinding ? Modal.REMOVE_BINDING : Modal.REMOVE_DOMAIN;
        rebuildIfActive();
    }

    private void updateSearchDraft(String value) {
        if (settingDraft || tunnelSearch.draft().equals(value)) {
            return;
        }
        tunnelSearch.edit(value, clientTicks);
        error = null;
    }

    static TerminalSearchButton buildSearchButton(
            TerminalLayout.Rect bounds, ClientSearchState search, boolean preset, Runnable toggle) {
        String key = search.expanded()
                ? "omniresonance.node_menu.tunnel.search.close"
                : preset ? "omniresonance.item_policy.search" : "omniresonance.node_menu.tunnel.search";
        return new TerminalSearchButton(
                bounds, search.expanded(), Component.translatable(key), ignored -> toggle.run());
    }

    private void toggleSearch() {
        if (resourceSelection != null) {
            if (!resourceSelection.closeSearch(clientTicks) && searchEligible())
                resourceSelection.search().open();
            rebuildIfActive();
        } else if (choosingItemPreset) {
            if (!presetPicker.closeSearch() && searchEligible())
                presetPicker.search().open();
            rebuildIfActive();
        } else if (tunnelSearch.expanded()) {
            closeTunnelSearch();
        } else if (searchEligible()) {
            tunnelSearch.open();
            rebuildIfActive();
        }
    }

    private boolean closeTunnelSearch() {
        if (!(interaction.authoritative() instanceof NodeMenuState.DirectTunnelList)
                || !tunnelSearch.close(clientTicks)) {
            return false;
        }
        listScroll = 0;
        error = null;
        appliedTunnelSearch = "";
        tunnelSearch.handled();
        rebuildIfActive();
        return true;
    }

    private void applyLocalTunnelSearch() {
        if (!tunnelSearch.due(clientTicks)
                || !tunnelCatalog.ready()
                || interaction.mutationPending()
                || modal != Modal.NONE
                || !(interaction.authoritative() instanceof NodeMenuState.DirectTunnelList)) {
            return;
        }
        String canonical = ClientSearchState.normalizedQuery(tunnelSearch.draft());
        tunnelSearch.handled();
        if (canonical == null) {
            error = Component.translatable(NodeMenuResponse.Reason.INVALID_REQUEST.translationKey());
            return;
        }
        appliedTunnelSearch = canonical;
        listScroll = 0;
        updateTunnelResultRows();
    }

    private void toggleEnabled(NodeMenuNodeSummary node) {
        if (node.enabled()) {
            modal = Modal.DISABLE;
            rebuildIfActive();
            return;
        }
        send(
                sequence -> new NodeMenuRequest.SetEnabled(menu.containerId, menu.sessionId(), sequence, true),
                NodeMenuInteractionPolicy.PendingKind.TOGGLE);
    }

    private void toggleChunkRequest(NodeMenuNodeSummary node) {
        send(
                sequence -> new NodeMenuRequest.SetChunkLoadingRequested(
                        menu.containerId, menu.sessionId(), sequence, !node.chunkLoadingRequested()),
                NodeMenuInteractionPolicy.PendingKind.TOGGLE);
    }

    private void chooseMode(NodeMenuNodeSummary node, NodeMode mode) {
        if (node.mode() != NodeMode.UNCONFIGURED && node.mode() != mode) {
            modal = Modal.MODE;
            pendingMode = mode;
            rebuildIfActive();
            return;
        }
        sendMode(mode, false);
    }

    private void modeCardPressed(NodeMenuNodeSummary node, NodeMode mode) {
        if (node.mode() == mode) {
            openCurrentMode(mode);
            return;
        }
        if (node.mode() != NodeMode.UNCONFIGURED) {
            modal = Modal.MODE;
            pendingMode = mode;
            rebuildIfActive();
            return;
        }
        beginModeCommit(mode, false);
    }

    private void beginModeCommit(NodeMode mode, boolean confirmedReset) {
        modeCommitAfterBegin = mode;
        modeCommitConfirmedReset = confirmedReset;
        beginModeEdit();
    }

    private void sendMode(NodeMode mode, boolean confirmedReset) {
        send(
                sequence ->
                        new NodeMenuRequest.SetMode(menu.containerId, menu.sessionId(), sequence, mode, confirmedReset),
                NodeMenuInteractionPolicy.PendingKind.SAVE);
    }

    private void cancelEdit() {
        if (interaction.editKind() == NodeMenuInteractionPolicy.EditKind.NONE) {
            return;
        }
        interaction = interaction.discardDraft();
        send(
                sequence -> new NodeMenuRequest.CancelEdit(menu.containerId, menu.sessionId(), sequence),
                NodeMenuInteractionPolicy.PendingKind.CANCEL);
    }

    private boolean send(LongFunction<NodeMenuRequest> factory, NodeMenuInteractionPolicy.PendingKind kind) {
        if (interaction.authoritative() == null || interaction.mutationPending() || minecraft == null) {
            return false;
        }
        long sequence = nextSequence();
        NodeMenuInteractionPolicy.Model submitted = interaction.submit(kind, sequence);
        NodeMenuRequest request = Objects.requireNonNull(factory.apply(sequence), "request");
        interaction = submitted;
        if (kind != NodeMenuInteractionPolicy.PendingKind.STATUS) error = null;
        PacketDistributor.sendToServer(request);
        if (kind != NodeMenuInteractionPolicy.PendingKind.STATUS) rebuildIfActive();
        return true;
    }

    private long nextSequence() {
        return Math.addExact(interaction.lastIssuedSequence(), 1);
    }

    private void updateDraft(String value) {
        if (settingDraft || draft.equals(value)) {
            return;
        }
        draft = value;
        if (!interaction.dirty()) {
            interaction = interaction.edited();
        }
        error = null;
    }

    private void initializeDraft(NodeMenuInteractionPolicy.EditKind kind, @Nullable NodeMenuState authoritative) {
        itemDraft = null;
        resourceSelection = null;
        resourceSettingsPage = false;
        resourceSettingsScroll = 0;
        typeEditor = null;
        catalogRequest = null;
        if (authoritative instanceof NodeMenuState.ResourceEdit) resourceCatalog.open(menu.sessionId());
        else resourceCatalog.close();
        faceDraft = authoritative instanceof NodeMenuState.ResourceEdit edit
                ? new NodeWorkingFacesDraft(
                        edit.workingFaces(), edit.node().form(), edit.node().facing())
                : null;
        faceScroll = 0;
        itemFormScroll = 0;
        choosingItemPreset = false;
        presetPicker.close();
        draft = switch (kind) {
            case BLANK ->
                authoritative instanceof NodeMenuState.BlankEdit edit
                        ? Component.translatable("omniresonance.node_menu.suggested_name", edit.suggestedNodeNumber())
                                .getString()
                        : "";
            case RENAME ->
                authoritative instanceof NodeMenuState.LinkedRename rename
                        ? rename.node().nodeName()
                        : "";
            case NETWORK_MOVE ->
                authoritative instanceof NodeMenuState.NetworkMoveEdit move
                        ? move.node().nodeName()
                        : "";
            case CHANNEL ->
                authoritative instanceof NodeMenuState.DirectChannelEdit edit
                        ? edit.existing() == null
                                ? edit.suggestedName()
                                : edit.existing().name()
                        : "";
            case NONE, MODE, BINDING, DOMAIN, CHANNEL_DELETE -> "";
        };
        directionDraft = switch (kind) {
            case BINDING ->
                authoritative instanceof NodeMenuState.DirectBindingEdit edit
                        ? NodeDirectionView.Draft.start(edit.channel().currentDirection())
                        : null;
            case DOMAIN ->
                authoritative instanceof NodeMenuState.DomainEdit edit
                        ? NodeDirectionView.Draft.start(edit.direction())
                        : null;
            default -> null;
        };
    }

    enum LocalBackAction {
        CLOSE_MODAL,
        BLOCK,
        CLOSE_FACES,
        CLOSE_PRESET_SEARCH,
        CLOSE_PRESET,
        CONTINUE
    }

    static LocalBackAction localBackAction(
            NodeMenuInteractionPolicy.Model interaction,
            boolean modalOpen,
            boolean faceOpen,
            boolean choosingPreset,
            boolean presetQueryPending) {
        return localBackAction(interaction, modalOpen, faceOpen, choosingPreset, presetQueryPending, false);
    }

    static LocalBackAction localBackAction(
            NodeMenuInteractionPolicy.Model interaction,
            boolean modalOpen,
            boolean faceOpen,
            boolean choosingPreset,
            boolean presetQueryPending,
            boolean searchExpanded) {
        if (modalOpen) return LocalBackAction.CLOSE_MODAL;
        boolean presetReadPending = presetReadPending(interaction, presetQueryPending);
        if (choosingPreset && searchExpanded && (!interaction.mutationPending() || presetReadPending))
            return LocalBackAction.CLOSE_PRESET_SEARCH;
        if (choosingPreset && presetReadPending) return LocalBackAction.CLOSE_PRESET;
        if (interaction.mutationPending()) return LocalBackAction.BLOCK;
        if (faceOpen) return LocalBackAction.CLOSE_FACES;
        if (choosingPreset) return LocalBackAction.CLOSE_PRESET;
        return LocalBackAction.CONTINUE;
    }

    private void navigateBack() {
        syncResourceDirty();
        if (modal == Modal.NONE && resourceSelection != null && !interaction.mutationPending()) {
            if (!resourceSelection.closeSearch(clientTicks)) {
                resourceSelection = null;
                syncResourceDirty();
            }
            rebuildIfActive();
            return;
        }
        if (modal == Modal.NONE && resourceSettingsPage && !interaction.mutationPending()) {
            resourceSettingsPage = false;
            rebuildIfActive();
            return;
        }
        switch (localBackAction(
                interaction,
                modal != Modal.NONE,
                faceDraft != null && faceDraft.openNow(),
                choosingItemPreset,
                presetPicker.pending(),
                presetPicker.search().expanded())) {
            case CLOSE_MODAL -> {
                closeModal();
                return;
            }
            case BLOCK -> {
                return;
            }
            case CLOSE_FACES -> {
                faceDraft.close();
                rebuildIfActive();
                return;
            }
            case CLOSE_PRESET_SEARCH -> {
                presetPicker.closeSearch();
                rebuildIfActive();
                return;
            }
            case CLOSE_PRESET -> {
                choosingItemPreset = false;
                presetPicker.close();
                rebuildIfActive();
                return;
            }
            case CONTINUE -> {}
        }
        if (closeTunnelSearch()) {
            return;
        }
        if (tunnelCatalog.loading() && tunnelCatalogPagePending) {
            returnAfterTunnelBatch = true;
            return;
        }
        switch (interaction.backAction()) {
            case BLOCK -> {
                return;
            }
            case CLOSE_SCREEN -> super.onClose();
            case SERVER_BACK ->
                send(
                        sequence -> new NodeMenuRequest.Back(menu.containerId, menu.sessionId(), sequence),
                        NodeMenuInteractionPolicy.PendingKind.NAVIGATE);
            case CANCEL_EDIT -> cancelEdit();
            case CONFIRM_DISCARD -> {
                closeAfterDiscard = false;
                interaction = interaction.confirmDiscard();
                modal = Modal.DISCARD;
                rebuildIfActive();
            }
            case CLOSE_CONFIRMATION -> closeModal();
        }
    }

    private void closeModal() {
        if (modal == Modal.TYPE_EDIT && typeEditor != null && typeEditor.dirty()) {
            modal = Modal.TYPE_DISCARD;
            rebuildIfActive();
            return;
        }
        if (modal == Modal.TYPE_DISCARD || modal == Modal.DISCARD && typeEditor != null) {
            modal = Modal.TYPE_EDIT;
            closeAfterDiscard = false;
            interaction = interaction.continueEditing();
            rebuildIfActive();
            return;
        }
        if (modal == Modal.TYPE_EDIT) typeEditor = null;
        modal = Modal.NONE;
        closeAfterDiscard = false;
        pendingMode = null;
        if (interaction.discardConfirmation()) {
            interaction = interaction.continueEditing();
        }
        rebuildIfActive();
    }

    private void confirmModal() {
        Modal accepted = modal;
        modal = Modal.NONE;
        if (accepted == Modal.TYPE_DISCARD) {
            typeEditor = null;
            syncResourceDirty();
            rebuildIfActive();
        } else if (accepted == Modal.RESOURCE_SCOPE) {
            applyResourceScope(true);
        } else if (accepted == Modal.ITEM_DIRECTION && itemDraft != null) {
            itemDraft.confirmDirectionChange();
            markItemDirty();
            rebuildIfActive();
        } else if (accepted == Modal.DISCARD) {
            interaction = interaction.continueEditing().discardDraft();
            if (closeAfterDiscard) super.onClose();
            else cancelEdit();
        } else if (accepted == Modal.DISABLE) {
            send(
                    sequence -> new NodeMenuRequest.SetEnabled(menu.containerId, menu.sessionId(), sequence, false),
                    NodeMenuInteractionPolicy.PendingKind.TOGGLE);
        } else if (accepted == Modal.MODE && pendingMode != null) {
            NodeMode selected = pendingMode;
            pendingMode = null;
            beginModeCommit(selected, true);
        } else if (accepted == Modal.REMOVE_BINDING) {
            send(
                    sequence -> new NodeMenuRequest.RemoveBinding(menu.containerId, menu.sessionId(), sequence),
                    NodeMenuInteractionPolicy.PendingKind.SAVE);
        } else if (accepted == Modal.REMOVE_DOMAIN) {
            send(
                    sequence -> new NodeMenuRequest.RemoveDomain(menu.containerId, menu.sessionId(), sequence),
                    NodeMenuInteractionPolicy.PendingKind.SAVE);
        }
    }

    @Override
    public void removed() {
        cancelPolicyTransfer();
        resourceRows.clear();
        downloads.close();
        resourceCatalog.close();
        resourceSelection = null;
        presetPicker.close();
        tunnelSearch.reset();
        super.removed();
    }

    @Override
    public void onClose() {
        navigateBack();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        ClientSearchState search = activeSearch();
        boolean searchWasExpanded = search.expanded();
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        search.finishToggleClick(searchWasExpanded, this, searchField);
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            navigateBack();
            return true;
        }
        if (routeSearchKey(
                keyCode,
                scanCode,
                modifiers,
                getFocused(),
                minecraft != null
                        && TerminalInteractionPolicy.inventoryShortcut(
                                minecraft.options.keyInventory, getFocused(), keyCode, scanCode),
                this::closeFromInventory,
                activeSearch(),
                searchEligible(),
                () -> {
                    rebuildIfActive();
                    if (minecraft != null)
                        minecraft
                                .getSoundManager()
                                .play(TerminalClickButton.clickFeedback().createSound());
                })) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    static boolean routeSearchKey(
            int keyCode,
            int scanCode,
            int modifiers,
            @Nullable net.minecraft.client.gui.components.events.GuiEventListener focused,
            boolean inventoryShortcut,
            Runnable close,
            ClientSearchState search,
            boolean eligible,
            Runnable opened) {
        if (focused instanceof TerminalEditBox field && field.ownsKey(keyCode))
            return field.keyPressed(keyCode, scanCode, modifiers);
        if (inventoryShortcut) {
            close.run();
            return true;
        }
        if (search.openFromKey(keyCode, modifiers, eligible)) {
            opened.run();
            return true;
        }
        return false;
    }

    private void closeFromInventory() {
        syncResourceDirty();
        if (interaction.exitAction() == NodeMenuInteractionPolicy.BackAction.CONFIRM_DISCARD) {
            closeAfterDiscard = true;
            interaction = interaction.confirmDiscard();
            modal = Modal.DISCARD;
            rebuildIfActive();
        } else {
            super.onClose();
        }
    }

    static boolean scrollIntervalField(
            Iterable<? extends net.minecraft.client.gui.components.events.GuiEventListener> widgets,
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY) {
        for (var widget : widgets)
            if (widget instanceof TerminalIntervalBox field && field.mouseScrolled(mouseX, mouseY, scrollX, scrollY))
                return true;
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (modal != Modal.NONE || interaction.mutationPending()) return true;
        if (scrollIntervalField(children(), mouseX, mouseY, scrollX, scrollY)) return true;
        if (contains(bodyBounds, mouseX, mouseY)) {
            NodeMenuState state = interaction.authoritative();
            if (state instanceof NodeMenuState.ResourceEdit edit) {
                if (interaction.mutationPending()) return true;
                if (resourceSelection != null) {
                    resourceSelection.wheel(
                            scrollY,
                            NodeResourceTypeSelectionView.layout(bodyBounds, resourceSelection)
                                    .list()
                                    .visibleRows());
                    updateResourceRows();
                    return true;
                }
                if (resourceSettingsPage) {
                    var rows = NodeResourceSettingsView.list(bodyBounds, itemDraft, resourceSettingsScroll);
                    resourceSettingsScroll = PagedListScroll.navigate(
                                    rows.scroll(),
                                    itemDraft.settingIds().size(),
                                    rows.visibleRows(),
                                    false,
                                    false,
                                    scrollY)
                            .scroll();
                    rebuildIfActive();
                    return true;
                }
                if (itemDraft != null && !choosingItemPreset && !(faceDraft != null && faceDraft.openNow())) {
                    itemFormScroll = scrollResourceForm(bodyBounds, itemFormScroll, itemDraft, scrollY);
                    rebuildIfActive();
                    return true;
                }
                if (choosingItemPreset && !(faceDraft != null && faceDraft.openNow())) {
                    presetPicker.wheel(
                            scrollY,
                            NodePresetPickerView.visibleRows(
                                    bodyBounds, presetPicker.search().expanded()));
                    updatePresetResultRows();
                    return true;
                }
                return true;
            }
            if (state instanceof NodeMenuState.BlankList list) {
                return applyPageScroll(
                        scrollY,
                        list.page().entries().size(),
                        RoutingListLayout.calculate(
                                        bodyBounds, list.page().entries().size(), listScroll)
                                .visibleRows(),
                        list.page().hasPrevious(),
                        list.page().hasNext(),
                        () -> requestPage(list.page(), true),
                        () -> requestPage(list.page(), false));
            }
            if (state instanceof NodeMenuState.NetworkSelection list) {
                return applyPageScroll(
                        scrollY,
                        list.page().entries().size(),
                        RoutingListLayout.calculate(
                                        bodyBounds, list.page().entries().size(), listScroll)
                                .visibleRows(),
                        list.page().hasPrevious(),
                        list.page().hasNext(),
                        () -> requestPage(list.page(), true),
                        () -> requestPage(list.page(), false));
            }
            if (state instanceof NodeMenuState.DirectTunnelList list) {
                if (!tunnelCatalog.ready() || modal != Modal.NONE || interaction.mutationPending()) {
                    return true;
                }
                int count = tunnelRows().size();
                RoutingListLayout rows =
                        NodeRoutingView.tunnelList(bodyBounds, tunnelSearch.expanded(), count, listScroll);
                int nextScroll = PagedListScroll.navigate(listScroll, count, rows.visibleRows(), false, false, scrollY)
                        .scroll();
                if (nextScroll != listScroll) {
                    listScroll = nextScroll;
                    updateTunnelResultRows();
                }
                return true;
            }
            if (state instanceof NodeMenuState.DirectChannelList list) {
                return applyPageScroll(
                        scrollY,
                        list.page().entries().size(),
                        RoutingListLayout.calculate(
                                        channelListBody(), list.page().entries().size(), listScroll)
                                .visibleRows(),
                        list.page().hasPrevious(),
                        list.page().hasNext(),
                        () -> requestChannelPage(list.page(), true),
                        () -> requestChannelPage(list.page(), false));
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean applyPageScroll(
            double scrollY,
            int entryCount,
            int visibleRows,
            boolean hasPrevious,
            boolean hasNext,
            Runnable previousPage,
            Runnable nextPage) {
        PagedListScroll.Result result =
                PagedListScroll.navigate(listScroll, entryCount, visibleRows, hasPrevious, hasNext, scrollY);
        listScroll = result.scroll();
        if (result.pageRequest() == PagedListScroll.PageRequest.PREVIOUS) {
            previousPage.run();
        } else if (result.pageRequest() == PagedListScroll.PageRequest.NEXT) {
            nextPage.run();
        } else {
            rebuildIfActive();
        }
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, TerminalTheme.WORLD_DIM);
        TerminalTheme.renderWindow(graphics, layout);
        renderTopBarText(graphics);
        TerminalTheme.renderPanel(graphics, bodyBounds);
        renderState(graphics);
        if (modal != Modal.NONE) modalBackdrop.render(widget -> widget.render(graphics, -1, -1, partialTick));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        NodeMenuNodeSummary headerNode = NodeMenuInteractionPolicy.linkedNode(interaction.authoritative());
        if (modal == Modal.NONE
                && headerNode != null
                && !networkHeaderButtonVisible(interaction)
                && mouseX >= networkTitleBounds.x()
                && mouseX < networkTitleBounds.right()
                && mouseY >= networkTitleBounds.y()
                && mouseY < networkTitleBounds.bottom()) {
            graphics.renderTooltip(
                    font, TerminalText.body(Component.literal(headerNode.networkName())), mouseX, mouseY);
        }
        if (modal != Modal.NONE)
            modalBackdrop.renderForeground(graphics, mouseX, mouseY, partialTick, () -> {
                graphics.fill(
                        layout.window().x(),
                        layout.titleBar().bottom(),
                        layout.window().right(),
                        layout.window().bottom(),
                        0x88000000);
                TerminalTheme.renderPanel(graphics, modalBounds);
                renderModal(graphics);
            });
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {}

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

    static boolean networkHeaderButtonVisible(NodeMenuInteractionPolicy.Model model) {
        NodeMenuNodeSummary node = NodeMenuInteractionPolicy.linkedNode(model.authoritative());
        return (model.authoritative() instanceof NodeMenuState.LinkedRoot
                        || model.authoritative() instanceof NodeMenuState.ModeRoot)
                && node != null
                && node.enabled()
                && model.editKind() == NodeMenuInteractionPolicy.EditKind.NONE;
    }

    static @Nullable TerminalButton buildNetworkHeaderButton(
            TerminalLayout.Rect bounds,
            String text,
            java.util.function.Supplier<NodeMenuInteractionPolicy.Model> model,
            java.util.function.BooleanSupplier modalClosed,
            Runnable open) {
        if (!networkHeaderButtonVisible(model.get())) return null;
        NodeMenuState original = model.get().authoritative();
        TerminalButton button = new TerminalButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                Component.literal(text),
                ignored -> {
                    NodeMenuInteractionPolicy.Model current = model.get();
                    if (networkHeaderButtonVisible(current)
                            && java.util.Objects.equals(original, current.authoritative())
                            && !current.mutationPending()
                            && modalClosed.getAsBoolean()) open.run();
                },
                false);
        button.active = !model.get().mutationPending() && modalClosed.getAsBoolean();
        button.setTooltip(Tooltip.create(TerminalText.body(
                Component.literal(NodeMenuInteractionPolicy.linkedNode(original).networkName())
                        .append("\n")
                        .append(Component.translatable("omniresonance.node_menu.network.switch")))));
        return button;
    }

    private void renderTopBarText(GuiGraphics graphics) {
        NodeMenuState state = interaction.authoritative();
        NodeMenuNodeSummary node = NodeMenuInteractionPolicy.linkedNode(state);
        String nodeName = node == null
                ? Component.translatable("omniresonance.node_menu.title.unconfigured")
                        .getString()
                : node.nodeName();
        String networkName = node != null
                ? node.networkName()
                : state instanceof NodeMenuState.BlankEdit edit ? edit.network().name() : "";
        if (nodeTitleBounds.width() > 0) {
            graphics.drawString(
                    font,
                    ellipsize(nodeName, Math.max(0, nodeTitleBounds.width() - 4)),
                    nodeTitleBounds.x() + 2,
                    nodeTitleBounds.y() + 6,
                    TerminalTheme.TEXT,
                    false);
        }
        if (networkTitleBounds.width() > 0 && !networkHeaderButtonVisible(interaction)) {
            TerminalText.drawNetworkLabel(
                    networkName,
                    networkTitleBounds,
                    candidate -> font.width(TerminalText.body(Component.literal(candidate))),
                    TerminalTheme.MUTED,
                    (text, x, y, color, shadow) -> graphics.drawString(font, text, x, y, color, shadow));
        }
    }

    private void renderState(GuiGraphics graphics) {
        NodeMenuState state = interaction.authoritative();
        if (automaticNameCommit.suppressEditor()) {
            drawCenteredWrapped(
                    graphics, Component.translatable("omniresonance.node_menu.pending"), TerminalTheme.MUTED);
        } else if (state == null) {
            drawCenteredWrapped(
                    graphics, Component.translatable("omniresonance.node_menu.loading"), TerminalTheme.MUTED);
        } else if (state instanceof NodeMenuState.Unavailable) {
            drawCenteredWrapped(
                    graphics,
                    Component.translatable(NodeMenuResponse.Reason.UNAVAILABLE.translationKey()),
                    TerminalTheme.ERROR);
        } else if (state instanceof NodeMenuState.NoAccess) {
            drawStateMessage(
                    graphics, "omniresonance.node_menu.no_access.title", "omniresonance.node_menu.no_access.message");
        } else if (state instanceof NodeMenuState.NoNetworks) {
            drawStateMessage(
                    graphics,
                    "omniresonance.node_menu.no_networks.title",
                    "omniresonance.node_menu.no_networks.message");
            Component key = Component.translatable(
                    "omniresonance.node_menu.no_networks.key", NetworkTerminalClient.terminalKeyText());
            TerminalText.drawCentered(
                    graphics,
                    font,
                    key,
                    bodyBounds.x() + bodyBounds.width() / 2,
                    bodyBounds.y() + 104,
                    TerminalTheme.ACCENT);
        } else if (state instanceof NodeMenuState.BlankList list) {
            graphics.drawString(
                    font,
                    Component.translatable("omniresonance.node_menu.networks"),
                    bodyBounds.x() + 5,
                    bodyBounds.y() + 5,
                    TerminalTheme.TEXT,
                    false);
            RoutingListLayout listLayout = RoutingListLayout.calculate(
                    bodyBounds, list.page().entries().size(), listScroll);
            TerminalTheme.renderScrollbar(
                    graphics,
                    listLayout.scrollbar().x(),
                    listLayout.scrollbar().y(),
                    listLayout.scrollbar().height(),
                    list.page().entries().size(),
                    listLayout.visibleRows(),
                    listLayout.scroll());
        } else if (state instanceof NodeMenuState.BlankEdit edit) {
            renderEditorHeading(graphics, edit.network().name());
        } else if (state instanceof NodeMenuState.LinkedRoot root) {
            renderModeRoot(graphics, root.node());
        } else if (state instanceof NodeMenuState.ModeRoot root) {
            renderModeRoot(graphics, root.node());
        } else if (state instanceof NodeMenuState.LinkedRename rename) {
            renderEditorHeading(graphics, rename.node().networkName());
        } else if (state instanceof NodeMenuState.LinkedMode mode) {
            renderEditorHeading(graphics, mode.node().networkName());
        } else if (state instanceof NodeMenuState.NetworkSelection selection) {
            renderListHeading(
                    graphics,
                    "omniresonance.node_menu.network.switch",
                    selection.page().entries().size(),
                    false);
        } else if (state instanceof NodeMenuState.NetworkMoveEdit move) {
            renderEditorHeading(graphics, move.target().name());
            graphics.drawString(
                    font,
                    Component.translatable("omniresonance.node_menu.network.move.message"),
                    TerminalDialogLayout.editor(bodyBounds).x() + 12,
                    TerminalDialogLayout.editor(bodyBounds).y() + 30,
                    TerminalTheme.MUTED,
                    false);
        } else if (state instanceof NodeMenuState.DirectTunnelList list) {
            renderListHeading(
                    graphics,
                    "omniresonance.node_menu.tunnels",
                    tunnelCatalog.ready() ? tunnelRows().size() : tunnelCatalog.total(),
                    true);
            if (!tunnelCatalog.ready()) {
                Component message = tunnelCatalog.failed()
                        ? Component.translatable("omniresonance.node_menu.tunnels.failed")
                        : Component.translatable(
                                "omniresonance.node_menu.tunnels.loading",
                                tunnelCatalog.received(),
                                tunnelCatalog.total());
                graphics.drawString(
                        font,
                        message,
                        bodyBounds.x() + 5,
                        bodyBounds.y() + 32,
                        tunnelCatalog.failed() ? TerminalTheme.ERROR : TerminalTheme.MUTED,
                        false);
            }
        } else if (state instanceof NodeMenuState.RestrictedTunnel restricted) {
            drawStateMessage(
                    graphics,
                    "omniresonance.node_menu.tunnel.restricted",
                    "omniresonance.node_menu.error.tunnel_disabled");
            TerminalText.drawCentered(
                    graphics,
                    font,
                    Component.literal(restricted.tunnel().name()),
                    bodyBounds.x() + bodyBounds.width() / 2,
                    bodyBounds.y() + 34,
                    TerminalTheme.TEXT);
        } else if (state instanceof NodeMenuState.DirectChannelList list) {
            TerminalText.drawHeaderTitle(
                    graphics, font, list.tunnel().name(), TerminalHeaderLayout.contentTitle(bodyBounds));
            renderRoutingScrollbar(
                    graphics,
                    RoutingListLayout.calculate(
                            channelListBody(), list.page().entries().size(), listScroll),
                    list.page().entries().size());
        } else if (state instanceof NodeMenuState.DirectChannelRoot root) {
            renderChannelRoot(graphics, root);
        } else if (state instanceof NodeMenuState.DirectChannelSettings settings) {
            renderChannelSettings(graphics, settings);
        } else if (state instanceof NodeMenuState.DirectTunnelSwitch tunnelSwitch) {
            renderTunnelSwitch(graphics, tunnelSwitch);
        } else if (state instanceof NodeMenuState.ResourceEdit edit) {
            if (choosingItemPreset || faceDraft != null && faceDraft.openNow())
                TerminalText.drawHeaderTitle(
                        graphics,
                        font,
                        choosingItemPreset
                                ? NodeItemPolicyView.text("choose").getString()
                                : edit instanceof NodeMenuState.DirectBindingEdit direct
                                        ? direct.channel().name()
                                        : Component.translatable("omniresonance.node_menu.mode.domain")
                                                .getString(),
                        choosingItemPreset
                                ? TerminalHeaderLayout.contentTitle(bodyBounds)
                                : new TerminalLayout.Rect(
                                        bodyBounds.x() + 12,
                                        bodyBounds.y() + 4,
                                        Math.max(
                                                0,
                                                bodyBounds.width()
                                                        - (faceDraft != null && faceDraft.openNow()
                                                                ? 170
                                                                : choosingItemPreset ? 24 : 100)),
                                        20));
            if (resourceSelection != null) {
                var rows = NodeResourceTypeSelectionView.layout(bodyBounds, resourceSelection)
                        .list();
                TerminalTheme.renderScrollbar(
                        graphics,
                        rows.scrollbar().x(),
                        rows.scrollbar().y(),
                        rows.scrollbar().height(),
                        resourceSelection.results().size(),
                        rows.visibleRows(),
                        resourceSelection.scroll());
            } else if (resourceSettingsPage && itemDraft != null) {
                TerminalText.drawHeaderTitle(
                        graphics,
                        font,
                        NodeResourcePolicyView.text("settings_title").getString(),
                        TerminalHeaderLayout.contentTitle(bodyBounds));
                var rows = NodeResourceSettingsView.list(bodyBounds, itemDraft, resourceSettingsScroll);
                TerminalTheme.renderScrollbar(
                        graphics,
                        rows.scrollbar().x(),
                        rows.scrollbar().y(),
                        rows.scrollbar().height(),
                        itemDraft.settingIds().size(),
                        rows.visibleRows(),
                        rows.scroll());
                if (itemDraft.settingIds().isEmpty())
                    NodeResourcePolicyView.label(
                            graphics,
                            font,
                            bodyBounds.x() + 8,
                            bodyBounds.y() + 36,
                            bodyBounds.width() - 16,
                            NodeResourcePolicyView.text("empty_types"));
            } else if (faceDraft != null && faceDraft.openNow()) {
                // The fixed 3-by-2 card grid never scrolls.
            } else if (!choosingItemPreset && itemDraft != null) {
                var form = NodeResourcePolicyView.layout(bodyBounds, itemFormScroll, itemDraft);
                TerminalTheme.renderScrollbar(
                        graphics,
                        bodyBounds.right() - 4,
                        form.form().y(),
                        form.form().height(),
                        form.totalRows(),
                        form.visibleRows(),
                        form.firstRow());
                NodeResourcePolicyView.render(
                        graphics,
                        font,
                        NodeResourcePolicyView.layout(bodyBounds, itemFormScroll, itemDraft),
                        itemDraft);
            } else if (choosingItemPreset) {
                var bounds = itemPickerBody();
                TerminalTheme.renderScrollbar(
                        graphics,
                        bounds.right() + 3,
                        bounds.y(),
                        bounds.height(),
                        presetPicker.count(),
                        NodePresetPickerView.visibleRows(
                                bodyBounds, presetPicker.search().expanded()),
                        presetPicker.scroll());
            }
        } else if (state instanceof NodeMenuState.DirectChannelEdit edit) {
            renderEditorHeading(graphics, edit.tunnel().name());
        } else if (state instanceof NodeMenuState.DirectChannelDelete delete) {
            renderChannelDelete(graphics, delete);
        } else if (state instanceof NodeMenuState.DomainRoot root) {
            renderDomainRoot(graphics, root);
        }
        if (interaction.mutationPending()) {
            graphics.drawString(
                    font,
                    Component.translatable("omniresonance.node_menu.pending"),
                    bodyBounds.x() + 5,
                    bodyBounds.bottom() - 13,
                    TerminalTheme.MUTED,
                    false);
        } else if (error != null) {
            graphics.drawString(
                    font,
                    ellipsize(error.getString(), Math.max(0, bodyBounds.width() - 10)),
                    bodyBounds.x() + 5,
                    bodyBounds.bottom() - 13,
                    TerminalTheme.ERROR,
                    false);
        }
    }

    private void renderModeRoot(GuiGraphics graphics, NodeMenuNodeSummary node) {
        if (!node.enabled()) {
            drawCenteredWrapped(
                    graphics, Component.translatable("omniresonance.node_menu.disabled.message"), TerminalTheme.ERROR);
        }
    }

    private void renderChannelRoot(GuiGraphics graphics, NodeMenuState.DirectChannelRoot state) {
        TerminalText.drawHeaderTitle(
                graphics, font, state.channel().name(), TerminalHeaderLayout.contentTitle(bodyBounds));
        drawPair(
                graphics,
                bodyBounds.x() + 12,
                bodyBounds.y() + 36,
                "omniresonance.node_menu.tunnel",
                state.tunnel().name());
        drawPair(
                graphics,
                bodyBounds.x() + 12,
                bodyBounds.y() + 54,
                "omniresonance.node_menu.direction",
                state.channel().currentDirection() == null
                        ? Component.translatable("omniresonance.node_menu.direction.none")
                                .getString()
                        : directionText(state.channel().currentDirection()).getString());
        if (state.policy() != null) {
            drawPair(
                    graphics,
                    bodyBounds.x() + 12,
                    bodyBounds.y() + 74,
                    "omniresonance.item_policy.status",
                    (state.transferStatus() == NodeTransferStatus.NO_WORK_FACES
                                    ? NodeFaceSelectorView.text("empty")
                                    : NodeItemPolicyView.text("status."
                                            + state.transferStatus().name().toLowerCase(Locale.ROOT)))
                            .getString());
            drawPair(
                    graphics,
                    bodyBounds.x() + 12,
                    bodyBounds.y() + 94,
                    "omniresonance.item_policy.parameters",
                    Component.translatable(
                                    "omniresonance.resource_policy.summary",
                                    state.policy().overrideCount(),
                                    state.policy().intervalTicks())
                            .getString());
        }
    }

    private void renderChannelSettings(GuiGraphics graphics, NodeMenuState.DirectChannelSettings state) {
        graphics.drawString(
                font,
                TerminalText.title(Component.translatable("omniresonance.node_menu.channel.manage")),
                bodyBounds.x() + 12,
                bodyBounds.y() + 18,
                TerminalTheme.TEXT,
                false);
        graphics.drawString(
                font,
                ellipsize(state.channel().name(), Math.max(0, bodyBounds.width() - 24)),
                bodyBounds.x() + 12,
                bodyBounds.y() + 34,
                TerminalTheme.ACCENT,
                false);
    }

    private Component tunnelSwitchMessage(NodeMenuState.DirectTunnelSwitch state) {
        return Component.translatable(
                "omniresonance.node_menu.tunnel.switch.message",
                state.summary().targetTunnelName(),
                state.summary().removedBindingCount());
    }

    private void renderTunnelSwitch(GuiGraphics graphics, NodeMenuState.DirectTunnelSwitch state) {
        renderConfirmationDialog(
                graphics,
                Component.translatable("omniresonance.node_menu.tunnel.switch.title"),
                tunnelSwitchMessage(state));
    }

    private void renderConfirmationDialog(GuiGraphics graphics, Component title, Component message) {
        var dialog = TerminalDialogLayout.confirmation(bodyBounds, font, message);
        TerminalDialogLayout.render(graphics, bodyBounds, dialog);
        TerminalText.drawCentered(
                graphics, font, title, dialog.x() + dialog.width() / 2, dialog.y() + 12, TerminalTheme.TEXT);
        graphics.drawWordWrap(
                font,
                TerminalText.body(message),
                dialog.x() + 10,
                dialog.y() + 34,
                dialog.width() - 20,
                TerminalTheme.MUTED);
    }

    private void renderEditorHeading(GuiGraphics graphics, String networkName) {
        var bounds = interaction.editKind() == NodeMenuInteractionPolicy.EditKind.MODE
                ? bodyBounds
                : TerminalDialogLayout.editor(bodyBounds);
        if (bounds != bodyBounds) TerminalDialogLayout.render(graphics, bodyBounds, bounds);
        drawPair(
                graphics,
                bounds.x() + 12,
                bounds.y() + 16,
                "omniresonance.node_menu.network",
                networkName,
                bounds.right());
        graphics.drawString(
                font,
                Component.translatable(
                        interaction.editKind() == NodeMenuInteractionPolicy.EditKind.MODE
                                ? "omniresonance.node_menu.mode"
                                : "omniresonance.node_menu.name"),
                bounds.x() + 12,
                bounds.y() + 42,
                TerminalTheme.MUTED,
                false);
    }

    private void renderListHeading(GuiGraphics graphics, String key, int entryCount, boolean search) {
        Component heading = search && ClientTextSearch.failed()
                ? Component.translatable(key, entryCount)
                        .append(" · ")
                        .append(Component.translatable("omniresonance.search.jec_unavailable"))
                : Component.translatable(key, entryCount);
        TerminalText.drawHeaderTitle(
                graphics, font, heading.getString(), TerminalHeaderLayout.contentTitle(bodyBounds));
        if (search && !tunnelCatalog.ready()) {
            return;
        }
        RoutingListLayout listLayout = search
                ? NodeRoutingView.tunnelList(bodyBounds, tunnelSearch.expanded(), entryCount, listScroll)
                : RoutingListLayout.calculate(bodyBounds, entryCount, listScroll);
        renderRoutingScrollbar(graphics, listLayout, entryCount);
    }

    private void renderRoutingScrollbar(GuiGraphics graphics, RoutingListLayout listLayout, int entryCount) {
        TerminalTheme.renderScrollbar(
                graphics,
                listLayout.scrollbar().x(),
                listLayout.scrollbar().y(),
                listLayout.scrollbar().height(),
                entryCount,
                listLayout.visibleRows(),
                listLayout.scroll());
    }

    private void renderDirectionHeading(GuiGraphics graphics, String subject, @Nullable TransferDirection original) {
        graphics.drawString(
                font,
                TerminalText.title(font, subject, Math.max(0, bodyBounds.width() - 24)),
                bodyBounds.x() + 12,
                bodyBounds.y() + 16,
                TerminalTheme.TEXT,
                false);
        drawPair(
                graphics,
                bodyBounds.x() + 12,
                bodyBounds.y() + 34,
                "omniresonance.node_menu.direction",
                original == null
                        ? Component.translatable("omniresonance.node_menu.direction.none")
                                .getString()
                        : directionText(original).getString());
    }

    private void renderDomainRoot(GuiGraphics graphics, NodeMenuState.DomainRoot root) {
        drawPair(
                graphics,
                bodyBounds.x() + 12,
                bodyBounds.y() + 28,
                "omniresonance.node_menu.direction",
                root.direction() == null
                        ? Component.translatable("omniresonance.node_menu.direction.none")
                                .getString()
                        : directionText(root.direction()).getString());
        graphics.drawWordWrap(
                font,
                Component.translatable(
                        "omniresonance.domain_status." + root.status().name().toLowerCase(java.util.Locale.ROOT)),
                bodyBounds.x() + 12,
                bodyBounds.y() + 54,
                Math.max(1, bodyBounds.width() - 24),
                TerminalTheme.MUTED);
    }

    private Component channelDeleteMessage(NodeMenuState.DirectChannelDelete state) {
        return Component.literal(state.summary().name())
                .append("\n")
                .append(Component.translatable(
                        "omniresonance.node_menu.channel.delete.impact",
                        state.summary().bindingCount()));
    }

    private void renderChannelDelete(GuiGraphics graphics, NodeMenuState.DirectChannelDelete state) {
        renderConfirmationDialog(
                graphics,
                Component.translatable("omniresonance.node_menu.channel.delete"),
                channelDeleteMessage(state));
    }

    private String modalMessageKey() {
        return switch (modal) {
            case RESOURCE_SCOPE -> "omniresonance.resource_policy.scope_narrowing";
            case ITEM_DIRECTION -> "omniresonance.item_policy.switch.message";
            case DISCARD, TYPE_DISCARD -> "omniresonance.node_menu.confirm.discard.message";
            case TYPE_EDIT -> "omniresonance.resource_policy.settings_title";
            case DISABLE -> "omniresonance.node_menu.confirm.disable.message";
            case MODE -> "omniresonance.node_menu.confirm.mode.message";
            case REMOVE_BINDING -> "omniresonance.node_menu.confirm.remove_binding.message";
            case REMOVE_DOMAIN -> "omniresonance.node_menu.confirm.remove_domain.message";
            case NONE -> throw new IllegalStateException("No node modal is open");
        };
    }

    private Component modalMessage() {
        return modal == Modal.RESOURCE_SCOPE
                ? Component.translatable(modalMessageKey(), itemDraft.removedOverrideCount(resourceSelection.scope()))
                : Component.translatable(modalMessageKey());
    }

    private void renderModal(GuiGraphics graphics) {
        if (modal == Modal.TYPE_EDIT) {
            NodeResourceSettingsView.renderEditor(graphics, font, bodyBounds, typeEditor);
            return;
        }
        String titleKey =
                switch (modal) {
                    case RESOURCE_SCOPE -> "omniresonance.resource_policy.scope";
                    case ITEM_DIRECTION -> "omniresonance.item_policy.switch.confirm";
                    case DISCARD, TYPE_DISCARD -> "omniresonance.node_menu.confirm.discard.title";
                    case TYPE_EDIT -> "omniresonance.resource_policy.settings_title";
                    case DISABLE -> "omniresonance.node_menu.confirm.disable.title";
                    case MODE -> "omniresonance.node_menu.confirm.mode.title";
                    case REMOVE_BINDING -> "omniresonance.node_menu.confirm.remove_binding.title";
                    case REMOVE_DOMAIN -> "omniresonance.node_menu.confirm.remove_domain.title";
                    case NONE -> throw new IllegalStateException("No node modal is open");
                };
        TerminalText.drawCentered(
                graphics,
                font,
                TerminalText.title(Component.translatable(titleKey)),
                modalBounds.x() + modalBounds.width() / 2,
                modalBounds.y() + 12,
                TerminalTheme.TEXT);
        List<net.minecraft.util.FormattedCharSequence> lines =
                font.split(TerminalText.body(modalMessage()), Math.max(1, modalBounds.width() - 20));
        int y = modalBounds.y() + 31;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            TerminalText.drawCentered(
                    graphics, font, line, modalBounds.x() + modalBounds.width() / 2, y, TerminalTheme.MUTED);
            y += 10;
        }
    }

    private void drawStateMessage(GuiGraphics graphics, String titleKey, String messageKey) {
        int centerX = bodyBounds.x() + bodyBounds.width() / 2;
        int centerY = bodyBounds.y() + bodyBounds.height() / 2;
        TerminalText.drawCentered(
                graphics,
                font,
                TerminalText.title(Component.translatable(titleKey)),
                centerX,
                centerY - 24,
                TerminalTheme.TEXT);
        List<net.minecraft.util.FormattedCharSequence> lines =
                font.split(Component.translatable(messageKey), Math.max(1, bodyBounds.width() - 32));
        int y = centerY - 4;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            TerminalText.drawCentered(graphics, font, line, centerX, y, TerminalTheme.MUTED);
            y += 10;
        }
    }

    private void drawCenteredWrapped(GuiGraphics graphics, Component message, int color) {
        List<net.minecraft.util.FormattedCharSequence> lines =
                font.split(message, Math.max(1, bodyBounds.width() - 32));
        int y = bodyBounds.y() + (bodyBounds.height() - lines.size() * 10) / 2;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            TerminalText.drawCentered(graphics, font, line, bodyBounds.x() + bodyBounds.width() / 2, y, color);
            y += 10;
        }
    }

    private void drawPair(GuiGraphics graphics, int x, int y, String labelKey, String value) {
        drawPair(graphics, x, y, labelKey, value, bodyBounds.right());
    }

    private void drawPair(GuiGraphics graphics, int x, int y, String labelKey, String value, int rightEdge) {
        Component label = Component.translatable(labelKey);
        graphics.drawString(font, label, x, y, TerminalTheme.MUTED, false);
        int valueX = x + Math.min(92, font.width(label) + 12);
        graphics.drawString(
                font, ellipsize(value, Math.max(0, rightEdge - valueX - 10)), valueX, y, TerminalTheme.TEXT, false);
    }

    private static String modeKey(NodeMode mode) {
        return switch (mode) {
            case UNCONFIGURED -> "omniresonance.node_menu.mode.unconfigured";
            case DIRECT -> "omniresonance.node_menu.mode.direct";
            case DOMAIN -> "omniresonance.node_menu.mode.domain";
        };
    }

    private void beginModeEdit() {
        send(
                sequence -> new NodeMenuRequest.BeginMode(menu.containerId, menu.sessionId(), sequence),
                NodeMenuInteractionPolicy.PendingKind.BEGIN_EDIT);
    }

    private void openCurrentMode(NodeMode mode) {
        send(
                sequence -> switch (mode) {
                    case DIRECT -> new NodeMenuRequest.OpenDirect(menu.containerId, menu.sessionId(), sequence);
                    case DOMAIN -> new NodeMenuRequest.OpenDomain(menu.containerId, menu.sessionId(), sequence);
                    case UNCONFIGURED -> throw new IllegalArgumentException("Unconfigured node has no mode view");
                },
                NodeMenuInteractionPolicy.PendingKind.NAVIGATE);
    }

    private TerminalLayout.Rect channelListBody() {
        return bodyBounds;
    }

    private static Component directionText(TransferDirection direction) {
        return Component.translatable(
                direction == TransferDirection.INPUT
                        ? "omniresonance.node_menu.direction.input"
                        : "omniresonance.node_menu.direction.output");
    }

    private String ellipsize(String value, int maximumWidth) {
        return TerminalText.ellipsize(font, value, maximumWidth);
    }

    private void rebuildIfActive() {
        if (minecraft != null && minecraft.screen == this) {
            rebuildWidgets();
        }
    }

    private static int clampScroll(int current, int total, int visible) {
        return Math.max(0, Math.min(current, Math.max(0, total - visible)));
    }

    private static PagedListScroll.PageRequest pageRequest(boolean backwards) {
        return backwards ? PagedListScroll.PageRequest.PREVIOUS : PagedListScroll.PageRequest.NEXT;
    }

    private static int pageLanding(PagedListScroll.PageRequest request) {
        return request == PagedListScroll.PageRequest.PREVIOUS ? Integer.MAX_VALUE : 0;
    }

    private static boolean contains(TerminalLayout.Rect bounds, double x, double y) {
        return x >= bounds.x() && x < bounds.right() && y >= bounds.y() && y < bounds.bottom();
    }

    private static TerminalLayout.Rect centered(TerminalLayout.Rect parent, int width, int height) {
        return new TerminalLayout.Rect(
                parent.x() + (parent.width() - width) / 2, parent.y() + (parent.height() - height) / 2, width, height);
    }

    private enum Modal {
        TYPE_EDIT,
        TYPE_DISCARD,
        RESOURCE_SCOPE,
        ITEM_DIRECTION,
        NONE,
        DISCARD,
        DISABLE,
        MODE,
        REMOVE_BINDING,
        REMOVE_DOMAIN
    }

    private enum NamePurpose {
        LINK,
        RENAME,
        MOVE,
        CHANNEL
    }
}
