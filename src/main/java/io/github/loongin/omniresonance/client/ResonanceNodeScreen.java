// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.networking.NodeChannelPage;
import io.github.loongin.omniresonance.networking.NodeChannelSummary;
import io.github.loongin.omniresonance.networking.NodeMenuNodeSummary;
import io.github.loongin.omniresonance.networking.NodeMenuRequest;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import io.github.loongin.omniresonance.networking.NodeMenuState;
import io.github.loongin.omniresonance.networking.NodeNetworkPage;
import io.github.loongin.omniresonance.networking.NodeNetworkSummary;
import io.github.loongin.omniresonance.networking.NodeTunnelSummary;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.node.ResonanceNodeMenu;
import java.util.List;
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
    private @Nullable NodeMode pendingMode;
    private @Nullable NodeMode modeCommitAfterBegin;
    private boolean modeCommitConfirmedReset;
    private @Nullable NodeDirectionView.Draft directionDraft;
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
        NodeMenuInteractionPolicy.EditKind previous = interaction.editKind();
        PagedListScroll.PageRequest completedPage = pendingPageRequest;
        NodeMenuInteractionPolicy.Transition transition = interaction.apply(response);
        if (!transition.accepted()) {
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
        modal = Modal.NONE;
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
            } else if (state instanceof NodeMenuState.DirectBindingEdit edit) {
                buildDirectionEditor(edit.channel().currentDirection(), true);
            } else if (state instanceof NodeMenuState.DirectChannelEdit edit) {
                buildNameEditor(edit.tunnel().name(), NamePurpose.CHANNEL);
            } else if (state instanceof NodeMenuState.DirectChannelDelete delete) {
                buildChannelDeleteConfirmation(delete);
            } else if (state instanceof NodeMenuState.DomainRoot root) {
                buildDomainRoot(root);
            } else if (state instanceof NodeMenuState.DomainEdit edit) {
                buildDirectionEditor(edit.direction(), false);
            }
        }
        buildModal();
    }

    @Override
    protected void setInitialFocus() {
        if (searchField != null) {
            setInitialFocus(searchField);
        } else {
            super.setInitialFocus();
        }
    }

    @Override
    protected void containerTick() {
        clientTicks++;
        if (interaction.heartbeatDue(clientTicks)) {
            long sequence = nextSequence();
            interaction = interaction.heartbeatSent(sequence, clientTicks);
            PacketDistributor.sendToServer(new NodeMenuRequest.Heartbeat(menu.containerId, menu.sessionId(), sequence));
        }
        applyLocalTunnelSearch();
    }

    private void buildTopBar() {
        TerminalLayout.Rect topBar = TerminalHeaderLayout.topBarContent(layout.window());
        int y = topBar.y();

        NodeMenuNodeSummary node = NodeMenuInteractionPolicy.linkedNode(interaction.authoritative());
        int right = topBar.right();
        int left = topBar.x();
        TerminalHeaderLayout.Action action = NodeMenuInteractionPolicy.topBarAction(interaction.authoritative());
        if (action != TerminalHeaderLayout.Action.NONE) {
            TerminalHeaderLayout.ActionLayout actionLayout = TerminalHeaderLayout.atRightEdge(
                    new TerminalLayout.Rect(left, y, Math.max(0, right - left), CONTROL_HEIGHT), true);
            buildTopBarAction(action, actionLayout.action());
            right = actionLayout.remaining().right();
        }
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
            int nameWidth = Math.max(0, available * 54 / 100);
            nodeTitleBounds = new TerminalLayout.Rect(left, y, 0, CONTROL_HEIGHT);
            networkTitleBounds = new TerminalLayout.Rect(
                    left + nameWidth + TerminalLayout.GAP,
                    y,
                    Math.max(0, right - left - nameWidth - TerminalLayout.GAP),
                    CONTROL_HEIGHT);
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
            TerminalButton network = new TerminalButton(
                    networkTitleBounds.x(),
                    y,
                    networkTitleBounds.width(),
                    CONTROL_HEIGHT,
                    Component.literal(ellipsize(node.networkName(), Math.max(0, networkTitleBounds.width() - 8))),
                    button -> send(
                            sequence -> new NodeMenuRequest.OpenNetworkSelection(
                                    menu.containerId, menu.sessionId(), sequence),
                            NodeMenuInteractionPolicy.PendingKind.NAVIGATE),
                    false);
            network.active = !interaction.mutationPending() && modal == Modal.NONE;
            network.setTooltip(Tooltip.create(
                    TerminalText.body(Component.translatable("omniresonance.node_menu.network.switch"))));
            addRenderableWidget(network);
        } else if (node != null) {
            int nameWidth = Math.max(0, available * 54 / 100);
            nodeTitleBounds = new TerminalLayout.Rect(left, y, nameWidth, CONTROL_HEIGHT);
            networkTitleBounds = new TerminalLayout.Rect(
                    left + nameWidth + TerminalLayout.GAP,
                    y,
                    Math.max(0, right - left - nameWidth - TerminalLayout.GAP),
                    CONTROL_HEIGHT);
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

    private void buildTopBarAction(TerminalHeaderLayout.Action action, TerminalLayout.Rect bounds) {
        TerminalClickButton button;
        if (action == TerminalHeaderLayout.Action.SEARCH) {
            button = new TerminalSearchButton(
                    bounds,
                    tunnelSearch.expanded(),
                    Component.translatable(
                            tunnelSearch.expanded()
                                    ? "omniresonance.node_menu.tunnel.search.close"
                                    : "omniresonance.node_menu.tunnel.search"),
                    ignored -> toggleTunnelSearch());
        } else if (action == TerminalHeaderLayout.Action.CREATE) {
            button = new TerminalIconButton(
                    bounds.x(),
                    bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    Component.translatable("omniresonance.node_menu.channel.create"),
                    ignored -> beginAutomaticChannelCreate());
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
                        || (action == TerminalHeaderLayout.Action.SEARCH && tunnelSearch.expanded()))
                && (action != TerminalHeaderLayout.Action.SEARCH || tunnelCatalog.ready());
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
        int formWidth = Math.min(440, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - formWidth) / 2;
        int top = bodyBounds.y() + 54;
        nameField = new TerminalEditBox(
                font, left, top, formWidth, CONTROL_HEIGHT, Component.translatable("omniresonance.node_menu.name"));
        nameField.setMaxLength(EDIT_BOX_MAXIMUM_UTF16_UNITS);
        settingDraft = true;
        nameField.setValue(draft);
        settingDraft = false;
        nameField.setResponder(this::updateDraft);
        nameField.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(nameField);

        int actionWidth = Math.max(0, (formWidth - TerminalLayout.GAP) / 2);
        TerminalButton cancel = new TerminalButton(
                left,
                top + 30,
                actionWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> cancelEdit(),
                false);
        cancel.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(cancel);
        TerminalButton save = new TerminalButton(
                left + actionWidth + TerminalLayout.GAP,
                top + 30,
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
        TerminalButton cancel = new TerminalButton(
                left,
                top + 30,
                width,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> cancelEdit(),
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
        int width = Math.min(400, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - width) / 2;
        int half = Math.max(0, (width - TerminalLayout.GAP) / 2);
        int y = bodyBounds.bottom() - CONTROL_HEIGHT - 12;
        addChannelAction(
                new TerminalLayout.Rect(left, y, half, CONTROL_HEIGHT),
                "omniresonance.node_menu.cancel",
                this::navigateBack,
                false,
                true);
        addChannelAction(
                new TerminalLayout.Rect(left + half + TerminalLayout.GAP, y, half, CONTROL_HEIGHT),
                "omniresonance.node_menu.tunnel.switch.confirm",
                () -> send(
                        sequence ->
                                new NodeMenuRequest.ConfirmTunnelSwitch(menu.containerId, menu.sessionId(), sequence),
                        NodeMenuInteractionPolicy.PendingKind.SAVE),
                true,
                true);
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

        int actionWidth = Math.max(0, (width - TerminalLayout.GAP) / 2);
        TerminalButton cancel = new TerminalButton(
                left,
                top + 30,
                actionWidth,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> cancelEdit(),
                false);
        cancel.active = !interaction.mutationPending() && modal == Modal.NONE;
        addRenderableWidget(cancel);
        if (current.canRemove()) {
            TerminalButton remove = new TerminalButton(
                    left + actionWidth + TerminalLayout.GAP,
                    top + 30,
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
                    left + actionWidth + TerminalLayout.GAP,
                    top + 30,
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
        int width = Math.min(400, Math.max(0, bodyBounds.width() - 24));
        int left = bodyBounds.x() + (bodyBounds.width() - width) / 2;
        int y = bodyBounds.bottom() - CONTROL_HEIGHT - 18;
        int half = Math.max(0, (width - TerminalLayout.GAP) / 2);
        TerminalButton cancel = new TerminalButton(
                left,
                y,
                half,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.node_menu.cancel"),
                button -> cancelEdit(),
                false);
        cancel.active = !interaction.mutationPending();
        addRenderableWidget(cancel);
        TerminalButton delete = new TerminalButton(
                left + half + TerminalLayout.GAP,
                y,
                half,
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
        int width = Math.min(360, Math.max(0, bodyBounds.width() - 16));
        int height = Math.min(108, Math.max(0, bodyBounds.height() - 8));
        modalBounds = centered(bodyBounds, width, height);
        int buttonY = modalBounds.bottom() - CONTROL_HEIGHT - 8;
        int half = Math.max(0, (modalBounds.width() - 16 - TerminalLayout.GAP) / 2);
        TerminalButton secondary = new TerminalButton(
                modalBounds.x() + 8,
                buttonY,
                half,
                CONTROL_HEIGHT,
                Component.translatable(
                        modal == Modal.DISCARD
                                ? "omniresonance.node_menu.confirm.continue"
                                : "omniresonance.node_menu.cancel"),
                button -> closeModal(),
                false);
        addRenderableWidget(secondary);
        TerminalButton primary = new TerminalButton(
                modalBounds.x() + 8 + half + TerminalLayout.GAP,
                buttonY,
                half,
                CONTROL_HEIGHT,
                Component.translatable(
                        modal == Modal.DISCARD
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

    private void toggleTunnelSearch() {
        if (tunnelSearch.expanded()) {
            closeTunnelSearch();
        } else if (!interaction.mutationPending() && modal == Modal.NONE && tunnelCatalog.ready()) {
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
        error = null;
        PacketDistributor.sendToServer(request);
        rebuildIfActive();
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

    private void navigateBack() {
        if (modal != Modal.NONE) {
            closeModal();
            return;
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
                interaction = interaction.confirmDiscard();
                modal = Modal.DISCARD;
                rebuildIfActive();
            }
            case CLOSE_CONFIRMATION -> closeModal();
        }
    }

    private void closeModal() {
        modal = Modal.NONE;
        pendingMode = null;
        if (interaction.discardConfirmation()) {
            interaction = interaction.continueEditing();
        }
        rebuildIfActive();
    }

    private void confirmModal() {
        Modal accepted = modal;
        modal = Modal.NONE;
        if (accepted == Modal.DISCARD) {
            interaction = interaction.continueEditing().discardDraft();
            cancelEdit();
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
    public void onClose() {
        navigateBack();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean searchWasExpanded = tunnelSearch.expanded();
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        tunnelSearch.finishToggleClick(searchWasExpanded, this, searchField);
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            navigateBack();
            return true;
        }
        if (tunnelSearch.openFromKey(keyCode, modifiers, interaction, modal != Modal.NONE, tunnelCatalog.ready())) {
            rebuildIfActive();
            if (minecraft != null) {
                minecraft
                        .getSoundManager()
                        .play(TerminalClickButton.clickFeedback().createSound());
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (contains(bodyBounds, mouseX, mouseY)) {
            NodeMenuState state = interaction.authoritative();
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
        if (modal != Modal.NONE) {
            graphics.fill(
                    layout.window().x(),
                    layout.titleBar().bottom(),
                    layout.window().right(),
                    layout.window().bottom(),
                    0x88000000);
            TerminalTheme.renderPanel(graphics, modalBounds);
            renderModal(graphics);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {}

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

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
        if (networkTitleBounds.width() > 0
                && !(node != null
                        && node.enabled()
                        && interaction.editKind() == NodeMenuInteractionPolicy.EditKind.NONE)) {
            graphics.drawString(
                    font,
                    ellipsize(networkName, Math.max(0, networkTitleBounds.width() - 4)),
                    networkTitleBounds.x() + 2,
                    networkTitleBounds.y() + 6,
                    TerminalTheme.MUTED,
                    false);
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
                    bodyBounds.x() + 12,
                    bodyBounds.y() + 30,
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
        } else if (state instanceof NodeMenuState.DirectBindingEdit edit) {
            renderDirectionHeading(
                    graphics, edit.channel().name(), edit.channel().currentDirection());
        } else if (state instanceof NodeMenuState.DirectChannelEdit edit) {
            renderEditorHeading(graphics, edit.tunnel().name());
        } else if (state instanceof NodeMenuState.DirectChannelDelete delete) {
            renderChannelDelete(graphics, delete);
        } else if (state instanceof NodeMenuState.DomainRoot root) {
            renderDomainRoot(graphics, root);
        } else if (state instanceof NodeMenuState.DomainEdit edit) {
            renderDirectionHeading(
                    graphics,
                    Component.translatable("omniresonance.node_menu.mode.domain")
                            .getString(),
                    edit.direction());
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

    private void renderTunnelSwitch(GuiGraphics graphics, NodeMenuState.DirectTunnelSwitch state) {
        int centerX = bodyBounds.x() + bodyBounds.width() / 2;
        TerminalText.drawCentered(
                graphics,
                font,
                Component.translatable("omniresonance.node_menu.tunnel.switch.title"),
                centerX,
                bodyBounds.y() + 32,
                TerminalTheme.TEXT);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                Component.translatable(
                        "omniresonance.node_menu.tunnel.switch.message",
                        state.summary().targetTunnelName(),
                        state.summary().removedBindingCount()),
                Math.max(1, bodyBounds.width() - 32));
        int y = bodyBounds.y() + 54;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            TerminalText.drawCentered(graphics, font, line, centerX, y, TerminalTheme.MUTED);
            y += 10;
        }
    }

    private void renderEditorHeading(GuiGraphics graphics, String networkName) {
        drawPair(graphics, bodyBounds.x() + 12, bodyBounds.y() + 16, "omniresonance.node_menu.network", networkName);
        graphics.drawString(
                font,
                Component.translatable(
                        interaction.editKind() == NodeMenuInteractionPolicy.EditKind.MODE
                                ? "omniresonance.node_menu.mode"
                                : "omniresonance.node_menu.name"),
                bodyBounds.x() + 12,
                bodyBounds.y() + 42,
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
    }

    private void renderChannelDelete(GuiGraphics graphics, NodeMenuState.DirectChannelDelete state) {
        int centerX = bodyBounds.x() + bodyBounds.width() / 2;
        TerminalText.drawCentered(
                graphics,
                font,
                Component.translatable("omniresonance.node_menu.channel.delete"),
                centerX,
                bodyBounds.y() + 34,
                TerminalTheme.TEXT);
        TerminalText.drawCentered(
                graphics,
                font,
                Component.literal(state.summary().name()),
                centerX,
                bodyBounds.y() + 54,
                TerminalTheme.ACCENT);
        drawCenteredWrapped(
                graphics,
                Component.translatable(
                        "omniresonance.node_menu.channel.delete.impact",
                        state.summary().bindingCount()),
                TerminalTheme.MUTED);
    }

    private void renderModal(GuiGraphics graphics) {
        String titleKey =
                switch (modal) {
                    case DISCARD -> "omniresonance.node_menu.confirm.discard.title";
                    case DISABLE -> "omniresonance.node_menu.confirm.disable.title";
                    case MODE -> "omniresonance.node_menu.confirm.mode.title";
                    case REMOVE_BINDING -> "omniresonance.node_menu.confirm.remove_binding.title";
                    case REMOVE_DOMAIN -> "omniresonance.node_menu.confirm.remove_domain.title";
                    case NONE -> throw new IllegalStateException("No node modal is open");
                };
        String messageKey =
                switch (modal) {
                    case DISCARD -> "omniresonance.node_menu.confirm.discard.message";
                    case DISABLE -> "omniresonance.node_menu.confirm.disable.message";
                    case MODE -> "omniresonance.node_menu.confirm.mode.message";
                    case REMOVE_BINDING -> "omniresonance.node_menu.confirm.remove_binding.message";
                    case REMOVE_DOMAIN -> "omniresonance.node_menu.confirm.remove_domain.message";
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
                font.split(Component.translatable(messageKey), Math.max(1, modalBounds.width() - 20));
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
        Component label = Component.translatable(labelKey);
        graphics.drawString(font, label, x, y, TerminalTheme.MUTED, false);
        int valueX = x + Math.min(92, font.width(label) + 12);
        graphics.drawString(
                font,
                ellipsize(value, Math.max(0, bodyBounds.right() - valueX - 10)),
                valueX,
                y,
                TerminalTheme.TEXT,
                false);
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
