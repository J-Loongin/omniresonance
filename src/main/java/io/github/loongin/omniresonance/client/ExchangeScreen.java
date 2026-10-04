// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.exchange.ExchangeTerms;
import io.github.loongin.omniresonance.exchange.ExchangeTermsDraft;
import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.networking.ExchangeFrame;
import io.github.loongin.omniresonance.networking.ExchangeIntent;
import io.github.loongin.omniresonance.networking.ExchangeIntentCodec;
import io.github.loongin.omniresonance.networking.ExchangeRequest;
import io.github.loongin.omniresonance.networking.ExchangeRuleView;
import io.github.loongin.omniresonance.networking.FilterPresetSummary;
import io.github.loongin.omniresonance.networking.ManagementTransferPool;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Client-only exchange child; the parent owns the authenticated terminal session and request sequence. */
final class ExchangeScreen extends Screen {
    private record FormLabel(Component text, TerminalLayout.Rect bounds) {}

    private final List<FormLabel> formLabels = new ArrayList<>();
    private final ModalBackdrop modalBackdrop = new ModalBackdrop();
    private String intervalDraft = "1";

    private enum Page {
        TUNNELS,
        RULES,
        DETAIL,
        EDIT,
        SCOPE,
        RATES,
        TYPES,
        PRESETS,
        FILTER,
        CODES
    }

    private record Row(Component text, @Nullable Runnable action, boolean selected) {
        Row(Component text, @Nullable Runnable action) {
            this(text, action, false);
        }
    }

    private record DetailCell(
            Component label,
            Component value,
            int span,
            @Nullable Runnable action) {}

    private int detailRowCount;
    private @Nullable RoutingListLayout parameterRows;

    private record Type(ResourceLocation id, String unit, long batch) {}

    private record Code(UUID id, long revision, long expiresTick, String value) {
        @Override
        public String toString() {
            return "ExchangeCode[redacted]";
        }
    }

    private final NetworkSetupScreen parent;
    private final NetworkSummary network;
    private final UUID generation = UUID.randomUUID();
    private final ClientSearchState search = new ClientSearchState();
    private @Nullable ResourceTypeSelection resourceSelection;
    private @Nullable TerminalSearchBox resourceSearchField;
    private final TerminalResultRows results =
            new TerminalResultRows(this, this::addRenderableWidget, this::removeWidget);
    private final Map<UUID, io.github.loongin.omniresonance.exchange.ExchangeFilterStatus> filterStates =
            new LinkedHashMap<>();
    private final List<io.github.loongin.omniresonance.networking.ExchangeTunnelView> tunnels = new ArrayList<>();
    private final Map<UUID, io.github.loongin.omniresonance.networking.ExchangeChannelView> placements =
            new LinkedHashMap<>();
    private @Nullable io.github.loongin.omniresonance.networking.ExchangeTunnelView selectedTunnel;
    private String channelName = "";
    private boolean sending = true;
    private final List<ExchangeRuleView> rules = new ArrayList<>();
    private final List<Type> types = new ArrayList<>();
    private final List<FilterPresetSummary> presets = new ArrayList<>();
    private final List<Code> codes = new ArrayList<>();
    private final List<Row> filterRows = new ArrayList<>();
    private final Map<ResourceLocation, ResourceTransferPolicy.InputOverride> parameters = new LinkedHashMap<>();
    private final Set<ResourceLocation> selectedTypes = new LinkedHashSet<>();
    private List<Row> rows = List.of();
    private TerminalLayout layout;
    private TerminalLayout.Rect listBounds;
    private Page page = Page.TUNNELS;
    private @Nullable ExchangeRuleView detail;
    private @Nullable ExchangeTermsDraft baseline;
    private ExchangeTermsDraft.FilterChoice filter = new ExchangeTermsDraft.None();
    private FilterMode mode = FilterMode.WHITELIST;
    private String filterName = "", receiverName = "", receiverOwner = "", proposalCode = "";
    private @Nullable UUID receiver;
    private long defaultRate = ExchangeTerms.DEFAULT_RATE, libraryRevision, tick, sentTick;
    private int interval = 1, firstRow, visibleRows;
    private boolean allTypes = true, history, owner, opened, pending, dirty, submitted, leaving, editingExisting;
    private @Nullable ExchangeRequest outstanding;
    private byte[] downloaded;
    private final ClientUploadBuffer upload = new ClientUploadBuffer();
    private int downloadOffset, downloadKind;
    private @Nullable Component message, dialogText;
    private @Nullable Runnable dialogAction;
    private @Nullable Code shownCode;
    private boolean uncertain;
    private @Nullable Consumer<String> dialogSubmit;
    private String inputValue = "", inputOriginal = "";

    private @Nullable TerminalResourceParameterView.Form parameterInput;
    private @Nullable ResourceParameterDraft parameterEditor;
    private @Nullable TerminalEditBox input;
    private @Nullable InputDialog suspendedInput;

    private record InputDialog(
            Component prompt,
            Consumer<String> submit,
            @Nullable TerminalResourceParameterView.Form form,
            @Nullable ResourceParameterDraft editor,
            String value,
            String original,
            @Nullable Component error) {}

    ExchangeScreen(NetworkSetupScreen parent, NetworkSummary network) {
        super(text("title"));
        this.parent = parent;
        this.network = network;
    }

    NetworkSetupScreen terminalParent() {
        return parent;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("omniresonance.exchange." + key, args);
    }

    private static String label(String key, Object... args) {
        return text(key, args).getString();
    }

    @Override
    protected void init() {
        font = TerminalText.font(minecraft);
        layout = TerminalLayout.terminal(width, height);
        rebuild();
        if (!opened) {
            opened = true;
            send(ExchangeRequest.OPEN, 0, new byte[0]);
        }
    }

    private void send(int kind, int offset, byte[] body) {
        pending = true;
        sentTick = tick;
        outstanding = parent.sendExchange(generation, kind, offset, body);
        rebuild();
    }

    private static byte[] bytes(Consumer<FriendlyByteBuf> writer) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(b);
            byte[] result = new byte[b.readableBytes()];
            b.readBytes(result);
            return result;
        } finally {
            b.release();
        }
    }

    private void intent(ExchangeIntent intent) {
        if (pending || uncertain) return;
        byte[] body = bytes(b -> ExchangeIntentCodec.encode(b, intent));
        boolean write = !(intent instanceof ExchangeIntent.Detail)
                && !(intent instanceof ExchangeIntent.ListRules)
                && !(intent instanceof ExchangeIntent.ListTunnels)
                && !(intent instanceof ExchangeIntent.ListChannels);
        downloaded = null;
        downloadOffset = 0;
        message = null;
        if (body.length > ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES) {
            upload.begin(body);
            send(ExchangeRequest.UPLOAD, body.length, new byte[0]);
        } else {
            submitted = write;
            send(ExchangeRequest.ACTION, 0, body);
        }
    }

    void accept(ExchangeFrame frame) {
        if (leaving
                || outstanding == null
                || !frame.view().equals(outstanding.view())
                || !frame.session().equals(outstanding.session())
                || !frame.generation().equals(generation)
                || frame.sequence() != outstanding.sequence()) return;
        pending = false;
        try {
            if (frame.kind() == ExchangeFrame.ERROR) {
                String reason = new String(frame.body(), StandardCharsets.UTF_8);
                upload.clear();
                downloaded = null;
                downloadOffset = 0;
                if (reason.equals("session_expired") || reason.equals("stale_request") || reason.equals("committed")) {
                    disconnected();
                    return;
                }
                submitted = false;
                message = text("error."
                        + (Set.of("no_access", "quota", "paired", "channel_name")
                                        .contains(reason)
                                ? reason
                                : "unavailable"));
                rebuild();
                return;
            }
            owner = frame.owner();
            uncertain = false;
            if (frame.kind() == ExchangeFrame.ACK) {
                if (!upload.active()) throw new IllegalArgumentException("Unexpected upload acknowledgement");
                if (upload.hasNext()) {
                    var fragment = upload.next();
                    send(ExchangeRequest.CHUNK, fragment.offset(), fragment.bytes());
                } else {
                    upload.clear();
                    submitted = true;
                    send(ExchangeRequest.COMMIT, 0, new byte[0]);
                }
                return;
            }
            if (downloaded == null) {
                if (frame.offset() != 0) throw new IllegalArgumentException("Invalid initial fragment");
                downloaded = new byte[frame.total()];
                downloadKind = frame.kind();
                downloadOffset = 0;
            }
            if (frame.total() != downloaded.length || frame.kind() != downloadKind || frame.offset() != downloadOffset)
                throw new IllegalArgumentException("Invalid exchange fragment");
            byte[] chunk = frame.body();
            System.arraycopy(chunk, 0, downloaded, downloadOffset, chunk.length);
            downloadOffset += chunk.length;
            if (downloadOffset < downloaded.length) {
                if (chunk.length == 0) throw new IllegalArgumentException("Empty fragment");
                send(ExchangeRequest.MORE, downloadOffset, new byte[0]);
                return;
            }
            byte[] complete = downloaded;
            downloaded = null;
            downloadOffset = 0;
            var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(complete));
            try {
                read(downloadKind, b);
                if (b.isReadable()) throw new IllegalArgumentException("Trailing exchange response");
            } finally {
                b.release();
            }
            rebuild();
        } catch (RuntimeException failure) {
            uncertain = true;
            upload.clear();
            downloaded = null;
            pending = false;
            message = text("error.unavailable");
            rebuild();
        }
    }

    private static int count(FriendlyByteBuf b, int minimum) {
        int n = b.readVarInt();
        if (n < 0 || n > 262144 || n > b.readableBytes() / minimum)
            throw new IllegalArgumentException("Invalid exchange count");
        return n;
    }

    private static io.github.loongin.omniresonance.exchange.ExchangeFilterStatus readFilterStatus(FriendlyByteBuf b) {
        int id = b.readUnsignedByte();
        var values = io.github.loongin.omniresonance.exchange.ExchangeFilterStatus.values();
        if (id >= values.length) throw new IllegalArgumentException("Unknown filter status");
        return values[id];
    }

    private String ruleStatus(ExchangeRuleView rule) {
        var status = filterStates.get(rule.id());
        if (!rule.terminated() && status != null && status.blocked())
            return label("filter_status." + status.name().toLowerCase(java.util.Locale.ROOT));
        return label("state." + rule.state().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Code readCode(FriendlyByteBuf b) {
        return new Code(b.readUUID(), b.readLong(), b.readLong(), b.readUtf(22));
    }

    private void read(int kind, FriendlyByteBuf b) {
        switch (kind) {
            case ExchangeFrame.META -> {
                types.clear();
                int n = count(b, 10);
                for (int i = 0; i < n; i++) types.add(new Type(b.readResourceLocation(), b.readUtf(32), b.readLong()));
                b.readInt();
                refreshDirectory();
            }
            case ExchangeFrame.TUNNELS -> {
                history = b.readBoolean();
                tunnels.clear();
                selectedTunnel = null;
                int n = count(b, 58);
                for (int i = 0; i < n; i++)
                    tunnels.add(io.github.loongin.omniresonance.networking.ExchangeTunnelView.read(b));
                dirty = false;
                submitted = false;
                navigate(Page.TUNNELS);
            }
            case ExchangeFrame.CHANNELS -> {
                selectedTunnel = io.github.loongin.omniresonance.networking.ExchangeTunnelView.read(b);
                history = b.readBoolean();
                rules.clear();
                filterStates.clear();
                placements.clear();
                int n = count(b, 1);
                for (int i = 0; i < n; i++) {
                    var rule = ExchangeRuleView.read(b);
                    rules.add(rule);
                    filterStates.put(rule.id(), readFilterStatus(b));
                    readPlacement(b, rule.id());
                }
                dirty = false;
                submitted = false;
                navigate(Page.RULES);
            }
            case ExchangeFrame.RULES -> {
                history = b.readBoolean();
                rules.clear();
                filterStates.clear();
                int n = count(b, 1);
                for (int i = 0; i < n; i++) {
                    var rule = ExchangeRuleView.read(b);
                    rules.add(rule);
                    filterStates.put(rule.id(), readFilterStatus(b));
                }
                navigate(Page.RULES);
            }
            case ExchangeFrame.DETAIL -> {
                detail = ExchangeRuleView.read(b);
                filterStates.put(detail.id(), readFilterStatus(b));
                readPlacement(b, detail.id());
                var decoded = ExchangeIntentCodec.decode(b);
                if (!(decoded instanceof ExchangeIntent.Revise revise)
                        || !revise.agreement().equals(detail.id()))
                    throw new IllegalArgumentException("Mismatched terms");
                baseline = revise.draft();
                dirty = false;
                submitted = false;
                navigate(Page.DETAIL);
            }
            case ExchangeFrame.PRESETS -> {
                libraryRevision = b.readLong();
                presets.clear();
                int n = count(b, 29);
                for (int i = 0; i < n; i++) presets.add(FilterPresetSummary.read(b));
                navigate(Page.PRESETS);
            }
            case ExchangeFrame.RECEIVER -> {
                receiver = b.readUUID();
                receiverName = b.readUtf(256);
                receiverOwner = b.readUtf(256);
                confirm(
                        text("pair_confirm", network.name(), receiverName, receiverOwner),
                        () -> intent(new ExchangeIntent.Pair(proposalCode)));
            }
            case ExchangeFrame.CODE -> {
                submitted = false;
                Code code = readCode(b);
                showCode(code);
            }
            case ExchangeFrame.CODES -> {
                codes.clear();
                int n = count(b, 33);
                for (int i = 0; i < n; i++) codes.add(readCode(b));
                navigate(Page.CODES);
            }
            case ExchangeFrame.FILTER -> {
                filterRows.clear();
                int n = count(b, 4);
                for (int i = 0; i < n; i++) {
                    ResourceLocation type = b.readResourceLocation();
                    int selector = b.readUnsignedByte();
                    String value = b.readUtf(65535);
                    int component = b.readUnsignedByte();
                    filterRows.add(new Row(
                            Component.literal(
                                    NodeResourcePolicyView.typeName(type).getString() + " · "
                                            + (selector == 2 ? "#" : "") + (selector == 0 ? label("whole_type") : value)
                                            + " · " + label("components." + component)),
                            null));
                }
                navigate(Page.FILTER);
            }
            case ExchangeFrame.DONE -> {
                submitted = false;
                dirty = false;
                if (page == Page.CODES) send(ExchangeRequest.CODES, 0, new byte[0]);
                else refreshDirectory();
            }
            default -> throw new IllegalArgumentException("Unknown exchange response");
        }
    }

    private void navigate(Page next) {
        page = next;
        firstRow = 0;
        search.reset();
        message = null;
        dialogText = null;
        dialogAction = null;
        dialogSubmit = null;
        shownCode = null;
        resourceSelection = null;
        resourceSearchField = null;
        if (next == Page.SCOPE || next == Page.TYPES) {
            var available = new ArrayList<ResourceLocation>();
            for (var type : types) available.add(type.id());
            resourceSelection = next == Page.SCOPE
                    ? ExchangeResourceSelection.scope(this, available, allTypes, selectedTypes, parameters.keySet())
                    : ExchangeResourceSelection.types(available, parameters.keySet(), allTypes, selectedTypes);
        }
    }

    private void go(Page next) {
        navigate(next);
        rebuild();
    }

    private void readPlacement(FriendlyByteBuf b, UUID channel) {
        int present = b.readUnsignedByte();
        if (present > 1) throw new IllegalArgumentException("Invalid channel placement");
        if (present == 1)
            placements.put(channel, io.github.loongin.omniresonance.networking.ExchangeChannelView.read(b));
    }

    private void refreshDirectory() {
        if (selectedTunnel == null) intent(new ExchangeIntent.ListTunnels(history));
        else intent(new ExchangeIntent.ListChannels(selectedTunnel.id(), history));
    }

    private boolean creationPage() {
        return owner
                && !history
                && (page == Page.TUNNELS
                        || page == Page.RULES
                                && selectedTunnel != null
                                && selectedTunnel.approved()
                                && selectedTunnel.available());
    }

    private String peerName() {
        return selectedTunnel == null
                ? label("unavailable_network")
                : selectedTunnel.peerName(network.id()) == null
                        ? label("unavailable_network")
                        : selectedTunnel.peerName(network.id());
    }

    private String relativeDirection(ExchangeRuleView rule) {
        return label(
                rule.source().network().equals(network.id()) ? "sending_to" : "receiving_from",
                rule.source().network().equals(network.id()) ? endpoint(rule.target()) : endpoint(rule.source()));
    }

    private void beginChannel() {
        input(text("channel_name_prompt"), "", name -> {
            channelName = new io.github.loongin.omniresonance.network.ManagedName(name).value();
            editingExisting = false;
            detail = null;
            baseline = null;
            sending = true;
            allTypes = true;
            selectedTypes.clear();
            parameters.clear();
            filter = new ExchangeTermsDraft.None();
            filterName = "";
            mode = FilterMode.WHITELIST;
            defaultRate = ExchangeTerms.DEFAULT_RATE;
            interval = 1;
            intervalDraft = "1";
            dirty = true;
            submitted = false;
            receiver = selectedTunnel.peer(network.id());
            receiverName = peerName();
            go(Page.EDIT);
        });
    }

    private void beginEdit() {
        if (baseline == null || detail == null) return;
        editingExisting = true;
        channelName = placements.get(detail.id()).name();
        sending = detail.source().network().equals(network.id());
        allTypes = baseline.scope().kind() == ResourceScope.Kind.ALL;
        selectedTypes.clear();
        selectedTypes.addAll(baseline.scope().resourceTypeIds());
        filter = baseline.filter();
        filterName = detail.filterName() == null
                ? ""
                : io.github.loongin.omniresonance.filter.BuiltInPresets.label(detail.filterId(), detail.filterName())
                        .getString();
        mode = baseline.filterMode();
        defaultRate = baseline.defaultRate();
        interval = baseline.intervalTicks();
        intervalDraft = Integer.toString(interval);
        parameters.clear();
        parameters.putAll(baseline.resourceParameters());
        dirty = false;
        submitted = false;
        go(Page.EDIT);
    }

    private ExchangeTermsDraft draft() {
        return new ExchangeTermsDraft(
                allTypes ? ResourceScope.all() : ResourceScope.customSet(selectedTypes),
                mode,
                filter,
                defaultRate,
                Map.of(),
                interval,
                parameters);
    }

    private void save() {
        try {
            interval = Integer.parseInt(intervalDraft.trim());
            var d = draft();
            intent(
                    editingExisting
                            ? new ExchangeIntent.ReviseChannel(
                                    detail.id(),
                                    detail.revision(),
                                    new io.github.loongin.omniresonance.exchange.ExchangeChannelDraft(
                                            new io.github.loongin.omniresonance.network.ManagedName(channelName),
                                            sending,
                                            d))
                            : new ExchangeIntent.CreateChannel(
                                    selectedTunnel.id(),
                                    selectedTunnel.revision(),
                                    new io.github.loongin.omniresonance.exchange.ExchangeChannelDraft(
                                            new io.github.loongin.omniresonance.network.ManagedName(channelName),
                                            sending,
                                            d)));
        } catch (IllegalArgumentException failure) {
            message = text("invalid");
            rebuild();
        }
    }

    private void changed() {
        dirty = true;
        rebuild();
    }

    private boolean searchable() {
        return page == Page.TUNNELS
                || page == Page.RULES
                || page == Page.PRESETS
                || page == Page.TYPES
                || page == Page.SCOPE;
    }

    private boolean editing() {
        return page == Page.EDIT || page == Page.SCOPE || page == Page.TYPES || page == Page.PRESETS;
    }

    private ClientSearchState activeSearch() {
        return resourceSelection == null ? search : resourceSelection.search();
    }

    private void toggleSearch() {
        if (pending || uncertain || dialogText != null) return;
        var active = activeSearch();
        active.toggle(tick);
        firstRow = 0;
        rebuild();
        if (active.expanded())
            setFocused(
                    resourceSelection == null
                            ? search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, tick))
                            : resourceSearchField);
    }

    private TerminalLayout.Rect searchBounds() {
        var c = layout.content();
        return new TerminalLayout.Rect(c.x() + 4, c.y(), c.width() - (creationPage() ? 34 : 14), 20);
    }

    private void rebuild() {
        if (layout == null) return;
        var focused = getFocused();
        boolean keepSearch = activeSearch().expanded() && focused instanceof TerminalSearchBox;
        results.clear();
        modalBackdrop.clear();
        parameterRows = null;
        formLabels.clear();
        clearWidgets();
        setFocused(null);
        buildPageLayers(
                () -> buildPage(keepSearch),
                dialogText == null
                        ? null
                        : () -> modalBackdrop.open(children(), this::removeWidget, renderables, () -> {
                            setFocused(null);
                            buildDialog();
                        }));
    }

    static void buildPageLayers(Runnable page, @Nullable Runnable dialog) {
        ModalBackdrop.buildLayers(page, dialog);
    }

    private void buildPage(boolean keepSearch) {
        var c = layout.content();
        var h = TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(layout.window()), searchable());
        if (resourceSelection != null) {
            buildResourceSelection(keepSearch, h.action());
            return;
        }
        if (creationPage()) {
            var r = h.action();
            var add = addRenderableWidget(new TerminalIconButton(
                    r.x(),
                    r.y(),
                    r.width(),
                    r.height(),
                    text(page == Page.TUNNELS ? "pair_create" : "channel_create"),
                    ignored -> {
                        if (page == Page.TUNNELS) enterCode();
                        else beginChannel();
                    }));
            add.active = !pending && !uncertain;
            var searchButton = addRenderableWidget(new TerminalSearchButton(
                    new TerminalLayout.Rect(c.right() - 20, c.y(), 20, 20),
                    search.expanded(),
                    text("search"),
                    ignored -> toggleSearch()));
            searchButton.active = !pending && !uncertain;
        } else if (page == Page.RATES) {
            addRenderableWidget(TerminalResourceSettingsList.add(
                    TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(layout.window()), true)
                            .action(),
                    !pending && !uncertain,
                    () -> go(Page.TYPES)));
        } else if (searchable()) {
            var button = addRenderableWidget(
                    new TerminalSearchButton(h.action(), search.expanded(), text("search"), ignored -> toggleSearch()));
            button.active = !pending;
        }
        if (creationPage() && !search.expanded())
            formLabels.add(new FormLabel(
                    page == Page.RULES ? text("paired_with", peerName()) : text("paired_networks"),
                    new TerminalLayout.Rect(c.x() + 4, c.y() + 5, c.width() - 34, 10)));
        int y = c.y() + (creationPage() ? 26 : 0);
        if (search.expanded()) {
            var field = search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, tick));
            addRenderableWidget(field);
            if (keepSearch) setFocused(field);
            if (!creationPage()) y += 26;
        }
        if (page == Page.EDIT) {
            buildTermsForm();
            return;
        }
        if (page == Page.RATES) {
            buildParameterPage();
            return;
        }
        List<Row> content = new ArrayList<>();
        if (page == Page.TUNNELS) {
            var actions = new ArrayList<Row>();
            actions.add(new Row(
                    text(history ? "active" : "history"), () -> intent(new ExchangeIntent.ListTunnels(!history))));
            if (owner) actions.add(new Row(text("codes"), () -> send(ExchangeRequest.CODES, 0, new byte[0])));
            actions.add(new Row(text("refresh"), this::refreshDirectory));
            toolbar(actions, y);
            y += 26;
            for (var tunnel : tunnels) {
                String peer = tunnel.peerName(network.id());
                String state = label(
                        tunnel.closed()
                                ? "pair_closed"
                                : !tunnel.available()
                                        ? "unavailable_network"
                                        : tunnel.approved() ? "pair_approved" : "pair_pending");
                content.add(new Row(
                        text("pair_row", peer == null ? label("unavailable_network") : peer, state),
                        () -> intent(new ExchangeIntent.ListChannels(tunnel.id(), tunnel.closed()))));
            }
        } else if (page == Page.RULES && selectedTunnel != null) {
            var actions = new ArrayList<Row>();
            if (owner && !selectedTunnel.closed() && !selectedTunnel.ownApproved(network.id()))
                actions.add(new Row(
                        text("pair_approve"),
                        () -> confirm(
                                text("pair_approve_confirm", peerName()),
                                () -> intent(new ExchangeIntent.ApprovePair(
                                        selectedTunnel.id(), selectedTunnel.revision())))));
            actions.add(new Row(
                    text(history ? "active" : "history"),
                    () -> intent(new ExchangeIntent.ListChannels(selectedTunnel.id(), !history))));
            if (owner && !selectedTunnel.closed())
                actions.add(new Row(
                        text("pair_close"),
                        () -> confirm(
                                text("pair_close_confirm"),
                                () -> intent(new ExchangeIntent.ClosePair(
                                        selectedTunnel.id(), selectedTunnel.revision())))));
            actions.add(new Row(text("refresh"), this::refreshDirectory));
            toolbar(actions, y);
            y += 26;
            if (!selectedTunnel.approved())
                content.add(
                        new Row(text(selectedTunnel.closed() ? "pair_closed" : "pair_pending_hint", peerName()), null));
            for (var rule : rules) {
                var place = placements.get(rule.id());
                content.add(new Row(
                        text(
                                "channel_row",
                                place == null ? "?" : place.name(),
                                relativeDirection(rule),
                                ruleStatus(rule)),
                        () -> intent(new ExchangeIntent.Detail(rule.id()))));
            }
        } else if (page == Page.DETAIL && detail != null) {
            var actions = new ArrayList<Row>();
            boolean source = detail.source().network().equals(network.id());
            boolean approved = source ? detail.sourceApproved() : detail.targetApproved();
            boolean paused = source ? detail.sourcePaused() : detail.targetPaused();
            if (!detail.terminated()) {
                if (owner && !approved)
                    actions.add(new Row(
                            text("approve"),
                            () -> confirm(text("approve_confirm"), () -> change(ExchangeIntent.Action.APPROVE))));
                if (!paused) actions.add(new Row(text("pause"), () -> change(ExchangeIntent.Action.PAUSE)));
                else if (owner) actions.add(new Row(text("resume"), () -> change(ExchangeIntent.Action.RESUME)));
                if (owner) {
                    actions.add(new Row(text("edit"), this::beginEdit));
                    actions.add(new Row(
                            text("terminate"),
                            () -> confirm(text("terminate_confirm"), () -> change(ExchangeIntent.Action.TERMINATE))));
                }
            }
            actions.add(new Row(text("refresh"), () -> intent(new ExchangeIntent.Detail(detail.id()))));
            toolbar(actions, y);
            buildDetailForm(y + 26);
            return;
        } else if (page == Page.PRESETS) {
            content.add(new Row(text("none"), () -> {
                filter = new ExchangeTermsDraft.None();
                filterName = "";
                dirty = true;
                go(Page.EDIT);
            }));
            if (editingExisting)
                content.add(new Row(text("keep_filter"), () -> {
                    filter = new ExchangeTermsDraft.KeepApproved();
                    filterName = detail.filterName() == null
                            ? ""
                            : io.github.loongin.omniresonance.filter.BuiltInPresets.label(
                                            detail.filterId(), detail.filterName())
                                    .getString();
                    dirty = true;
                    go(Page.EDIT);
                }));
            for (var preset : presets)
                content.add(new Row(Component.literal(preset.name()), () -> {
                    filter = new ExchangeTermsDraft.OwnerPreset(preset.id(), libraryRevision);
                    filterName = preset.name();
                    dirty = true;
                    go(Page.EDIT);
                }));
        } else if (page == Page.FILTER) content.addAll(filterRows);
        else if (page == Page.CODES) {
            toolbar(
                    List.of(
                            new Row(text("issue_code"), () -> intent(new ExchangeIntent.IssueCode())),
                            new Row(text("refresh"), () -> send(ExchangeRequest.CODES, 0, new byte[0]))),
                    y);
            y += 36;
            for (int i = 0; i < codes.size(); i++) {
                Code code = codes.get(i);
                content.add(new Row(text("code_row", i + 1), () -> showCode(code)));
            }
        }
        int bottom = c.bottom();
        if (editing()) {
            var footer = TerminalActionLayout.of(c);
            bottom = footer.content().bottom();
            button(page == Page.EDIT ? footer.secondary() : footer.primary(), text("cancel"), () -> back(false), false);
            if (page == Page.EDIT) button(footer.primary(), text("save"), this::save, true);
        }
        if (message != null || pending) bottom -= 14;
        listBounds = new TerminalLayout.Rect(c.x() + 8, y, Math.max(0, c.width() - 16), Math.max(0, bottom - y));
        final List<Row> unfiltered = content;
        rows = searchable()
                ? ClientTextSearch.filter(
                        (query, matcher, revision) -> unfiltered.stream()
                                .filter(row -> matcher.test(
                                        ClientTextSearch.fold(row.text().getString()), query))
                                .toList(),
                        ClientTextSearch.fold(search.draft()))
                : content;
        visibleRows = Math.max(1, listBounds.height() / 26);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, rows.size() - visibleRows));
        for (int i = firstRow; i < Math.min(rows.size(), firstRow + visibleRows); i++) {
            Row row = rows.get(i);
            var button = new TerminalRowButton(
                    listBounds.x(),
                    listBounds.y() + (i - firstRow) * 26,
                    listBounds.width() - 8,
                    20,
                    row.text(),
                    ignored -> {
                        if (!pending && !uncertain && row.action() != null)
                            row.action().run();
                    });
            if (row.action() == null) button.setReadOnly();
            else button.active = !pending && !uncertain;
            button.setSelected(row.selected());
            button.setTooltip(Tooltip.create(TerminalText.body(row.text())));
            results.add(button);
        }
    }

    private void buildResourceSelection(boolean keepSearch, TerminalLayout.Rect action) {
        var selection = resourceSelection;
        var bounds = ResourceTypeSelectionView.layout(layout.content(), selection);
        var button = addRenderableWidget(new TerminalSearchButton(
                action,
                selection.search().expanded(),
                NodeResourcePolicyView.text("search"),
                ignored -> toggleSearch()));
        button.active = !pending && !uncertain;
        resourceSearchField = ResourceTypeSelectionView.buildSearch(font, bounds, selection, () -> tick, () -> {});
        if (resourceSearchField != null) {
            resourceSearchField.active = !pending && !uncertain;
            resourceSearchField.setEditable(!pending && !uncertain);
            addRenderableWidget(resourceSearchField);
            if (keepSearch) setFocused(resourceSearchField);
        }
        ResourceTypeSelectionView.buildRows(bounds, selection, !pending && !uncertain, results::add, () -> {
            if (resourceSelection != selection || pending || uncertain) return;
            if (selection.scope() == null) {
                var chosen = selection.chosen();
                if (chosen != null) {
                    go(Page.RATES);
                    editRate(chosen);
                }
            } else rebuild();
        });
        ResourceTypeSelectionView.buildScopeActions(
                bounds,
                selection,
                !pending && !uncertain,
                this::addRenderableWidget,
                this::rebuild,
                () -> {
                    if (resourceSelection != selection || pending || uncertain) return;
                    selection.scope().requireOwner(this);
                    var next = selection.scope().selection();
                    if (next.kind() == ResourceScope.Kind.CUSTOM_SET
                            && next.ids().isEmpty()) return;
                    allTypes = next.kind() == ResourceScope.Kind.ALL;
                    selectedTypes.clear();
                    selectedTypes.addAll(next.ids());
                    dirty = true;
                    go(Page.EDIT);
                },
                () -> {
                    if (resourceSelection == selection && !pending && !uncertain) go(Page.EDIT);
                });
    }

    boolean draftDirty() {
        return dirty
                || dialogSubmit != null && parameterEditor != null && parameterEditor.dirty()
                || dialogSubmit != null && !inputValue.equals(inputOriginal)
                || resourceSelection != null
                        && resourceSelection.scope() != null
                        && resourceSelection.scope().dirty();
    }

    private void buildParameterPage() {
        var ids = List.copyOf(parameters.keySet());
        parameterRows = parameterPageLayout(layout.content(), ids.size(), firstRow);
        firstRow = parameterRows.scroll();
        visibleRows = parameterRows.visibleRows();
        listBounds = parameterRows.rows();
        TerminalResourceSettingsList.buildRows(
                layout.content(),
                new TerminalResourceSettingsList.Entries() {
                    @Override
                    public int size() {
                        return ids.size();
                    }

                    @Override
                    public TerminalResourceSettingsList.Row row(int index) {
                        var id = ids.get(index);
                        boolean available = false;
                        for (var type : types)
                            if (type.id().equals(id)) {
                                available = true;
                                break;
                            }
                        var label = available
                                ? TerminalResourceSettingsList.summary(
                                        NodeResourcePolicyView.typeName(id),
                                        Component.literal(rateValueText(
                                                id, parameters.get(id).rate())),
                                        rateUnit(id),
                                        parameters.get(id).batchMode() == ResourceTransferPolicy.BatchMode.GREEDY
                                                ? NodeResourcePolicyView.text("greedy")
                                                : NodeResourcePolicyView.text(
                                                        "batch_summary",
                                                        parameters.get(id).batchSize()))
                                : NodeResourcePolicyView.text("unavailable_type", id.toString());
                        return new TerminalResourceSettingsList.Row(
                                label,
                                label.copy().append("\n").append(id.toString()),
                                available ? null : Component.literal(id.toString()),
                                () -> {
                                    if (!pending && !uncertain) editRate(id);
                                });
                    }
                },
                firstRow,
                !pending && !uncertain,
                results::add);
    }

    private void editDefaultRate() {
        String value = Long.toString(defaultRate);
        input(
                text("default_help"),
                value,
                changed -> {
                    defaultRate = positive(changed);
                    dirty = true;
                },
                TerminalResourceParameterView.rateForm(
                        text("default_rate"), NodeResourcePolicyView.text("native_units"), value, 20, true, null));
    }

    static RoutingListLayout parameterPageLayout(TerminalLayout.Rect body, int count, int scroll) {
        return TerminalResourceSettingsList.page(body, count, scroll).list();
    }

    private int visibleRowCount() {
        return page == Page.EDIT
                ? termsRows()
                : page == Page.DETAIL ? detailRowCount : page == Page.RATES ? parameters.size() : rows.size();
    }

    static TerminalLayout.Rect detailContent(TerminalLayout.Rect body, int top) {
        int y = top + 28;
        return new TerminalLayout.Rect(body.x() + 12, y, body.width() - 24, Math.max(0, body.bottom() - y));
    }

    private void buildDetailForm(int top) {
        var body = layout.content();
        formLabels.add(new FormLabel(
                text(
                        "channel_direction",
                        placements.containsKey(detail.id())
                                ? placements.get(detail.id()).name()
                                : "?",
                        relativeDirection(detail)),
                new TerminalLayout.Rect(body.x() + 12, top, body.width() - 24, 10)));
        var status = filterStates.get(detail.id());
        String statusText = ruleStatus(detail);
        if (status != null && !status.blocked())
            statusText += " · " + label("filter_status." + status.name().toLowerCase(java.util.Locale.ROOT));
        if (!detail.terminated() && !detail.targetApproved())
            statusText += " · " + label("approve_at", endpoint(detail.target()));
        else if (!detail.terminated() && !detail.sourceApproved())
            statusText += " · " + label("approve_at", endpoint(detail.source()));
        formLabels.add(new FormLabel(
                Component.literal(statusText),
                new TerminalLayout.Rect(body.x() + 12, top + 12, body.width() - 24, 10)));
        var fields = new ArrayList<List<DetailCell>>();
        fields.add(List.of(
                new DetailCell(
                        text("source_consent"), consentText(detail.sourceApproved(), detail.sourcePaused()), 1, null),
                new DetailCell(
                        text("target_consent"), consentText(detail.targetApproved(), detail.targetPaused()), 1, null),
                new DetailCell(text("interval_label"), Component.literal(detail.intervalTicks() + " gt"), 1, null)));
        fields.add(List.of(
                new DetailCell(
                        text("filter_label"),
                        detail.filterName() == null
                                ? text("filter_required")
                                : io.github.loongin.omniresonance.filter.BuiltInPresets.label(
                                        detail.filterId(), detail.filterName()),
                        2,
                        () -> send(ExchangeRequest.FILTER, 0, bytes(b -> b.writeUUID(detail.id())))),
                new DetailCell(
                        text("mode_label"),
                        text(detail.filterMode() == FilterMode.WHITELIST ? "whitelist" : "blacklist"),
                        1,
                        null)));
        fields.add(List.of(
                new DetailCell(
                        text("scope"), text(detail.allTypes() ? "all" : "type_count", detail.typeCount()), 1, null),
                new DetailCell(text("default_rate"), Component.literal(rateText(null, detail.defaultRate())), 1, null),
                new DetailCell(text("page.rates"), text("override_summary", detail.overrideCount()), 1, null)));
        if (baseline != null) {
            var extra = new ArrayList<DetailCell>();
            if (!detail.allTypes())
                for (var type : baseline.scope().resourceTypeIds())
                    extra.add(new DetailCell(text("scope"), NodeResourcePolicyView.typeName(type), 1, null));
            for (var entry : baseline.resourceParameters().entrySet())
                extra.add(new DetailCell(
                        NodeResourcePolicyView.typeName(entry.getKey()),
                        NodeResourcePolicyView.text(
                                        "rate_summary",
                                        Component.literal(
                                                Long.toString(entry.getValue().rate())),
                                        rateUnit(entry.getKey()))
                                .copy()
                                .append(" · ")
                                .append(
                                        entry.getValue().batchMode() == ResourceTransferPolicy.BatchMode.GREEDY
                                                ? NodeResourcePolicyView.text("greedy")
                                                : NodeResourcePolicyView.text(
                                                        "batch_summary",
                                                        entry.getValue().batchSize())),
                        1,
                        null));
            for (int i = 0; i < extra.size(); i += 3)
                fields.add(List.copyOf(extra.subList(i, Math.min(extra.size(), i + 3))));
        }
        if (reverse(detail.target().network(), detail.source().network()))
            fields.add(List.of(new DetailCell(text("notice"), text("cycle"), 3, null)));
        detailRowCount = fields.size();
        listBounds = detailContent(body, top);
        if (message != null || pending)
            listBounds = new TerminalLayout.Rect(
                    listBounds.x(), listBounds.y(), listBounds.width(), Math.max(0, listBounds.height() - 14));
        visibleRows = Math.max(1, listBounds.height() / 32);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, detailRowCount - visibleRows));
        for (int i = firstRow; i < Math.min(detailRowCount, firstRow + visibleRows); i++) {
            var row = new TerminalLayout.Rect(
                    listBounds.x(), listBounds.y() + (i - firstRow) * 32, listBounds.width() - 6, 32);
            int col = 0;
            for (var field : fields.get(i)) {
                var control = formCell(row, col, field.span(), field.label());
                col += field.span();
                if (field.action() != null) fieldButton(control, field.value(), field.action());
                else formLabels.add(new FormLabel(field.value(), control));
            }
        }
    }

    private static Component consentText(boolean approved, boolean paused) {
        return Component.literal(
                label(approved ? "approved" : "pending") + " · " + label(paused ? "paused" : "not_paused"));
    }

    private boolean termsWarning() {
        return editingExisting || reverse(receiver, network.id());
    }

    private int termsRows() {
        return 3;
    }

    private void buildTermsForm() {
        var geometry = ExchangeTermsLayout.of(layout.content(), termsWarning(), message != null || pending);
        var footer = geometry.footer();
        fieldButton(
                geometry.name(),
                text("channel_named", channelName),
                () -> input(text("channel_name_prompt"), channelName, value -> {
                    channelName = new io.github.loongin.omniresonance.network.ManagedName(value).value();
                    dirty = true;
                }));
        formLabels.add(new FormLabel(text(sending ? "sending_to" : "receiving_from", peerName()), geometry.peer()));
        int top = geometry.form().y();
        visibleRows = Math.min(termsRows(), Math.max(1, geometry.form().height() / 32));
        firstRow = Math.clamp(firstRow, 0, Math.max(0, termsRows() - visibleRows));
        listBounds = geometry.form();
        boolean active = !pending && !uncertain;
        for (int i = firstRow; i < Math.min(termsRows(), firstRow + visibleRows); i++) {
            var row = new TerminalLayout.Rect(listBounds.x(), top + (i - firstRow) * 32, listBounds.width(), 32);
            if (i == 0) {
                fieldButton(formCell(row, 0, 1, text("direction")), text(sending ? "send" : "receive"), () -> {
                    sending = !sending;
                    changed();
                });
                fieldButton(
                        formCell(row, 1, 1, text("scope")),
                        text(allTypes ? "all" : "type_count", selectedTypes.size()),
                        () -> go(Page.SCOPE));
                addRenderableWidget(intervalControl(
                        font, formCell(row, 2, 1, text("interval_label")), intervalDraft, active, value -> {
                            intervalDraft = value;
                            dirty = true;
                        }));
            } else if (i == 1) {
                fieldButton(
                        formCell(row, 0, 2, text("filter_label")),
                        Component.literal(filterName.isEmpty() ? label("filter_required") : filterName),
                        () -> send(ExchangeRequest.PRESETS, 0, new byte[0]));
                fieldButton(
                        formCell(row, 2, 1, text("mode_label")),
                        text(mode == FilterMode.WHITELIST ? "whitelist" : "blacklist"),
                        () -> {
                            mode = mode == FilterMode.WHITELIST ? FilterMode.BLACKLIST : FilterMode.WHITELIST;
                            changed();
                        });
            } else if (i == 2) {
                addRenderableWidget(TerminalResourceSettingsList.entry(
                        new TerminalLayout.Rect(row.x(), row.y() + 6, row.width(), 20),
                        parameters.size(),
                        !pending && !uncertain,
                        () -> {
                            if (!pending && !uncertain) go(Page.RATES);
                        }));
            }
        }
        if (termsWarning()) {
            Component warning = text(editingExisting ? "revision_warning" : "cycle");
            if (editingExisting
                    && reverse(detail.target().network(), detail.source().network()))
                warning = warning.copy().append(" · ").append(text("cycle"));
            formLabels.add(new FormLabel(warning, geometry.warning()));
        }
        var defaults = new TerminalLayout.Rect(
                layout.content().x() + 8,
                footer.primary().y(),
                Math.max(
                        0,
                        Math.min(140, footer.secondary().x() - layout.content().x() - 14)),
                20);
        button(defaults, text("default_control", rateText(null, defaultRate)), this::editDefaultRate, false);
        button(footer.secondary(), text("cancel"), () -> back(false), false);
        button(footer.primary(), text("save"), this::save, true);
    }

    static TerminalIntervalBox intervalControl(
            net.minecraft.client.gui.Font font,
            TerminalLayout.Rect bounds,
            String value,
            boolean active,
            Consumer<String> changed) {
        var box = new TerminalIntervalBox(
                font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), text("interval_help"));
        box.setMaxLength(10);
        box.setValue(value);
        box.setEditable(active);
        box.active = active;
        box.setResponder(changed);
        return box;
    }

    private TerminalRowButton fieldButton(TerminalLayout.Rect bounds, Component label, Runnable action) {
        var button = addRenderableWidget(TerminalFormGrid.field(bounds, label, !pending && !uncertain, () -> {
            if (!pending && !uncertain) action.run();
        }));
        return button;
    }

    private TerminalLayout.Rect formCell(TerminalLayout.Rect row, int column, int span, Component label) {
        var control = TerminalFormGrid.control(row, 3, column, span);
        formLabels.add(new FormLabel(label, new TerminalLayout.Rect(control.x(), row.y(), control.width(), 10)));
        return control;
    }

    private String endpoint(ExchangeRuleView.Endpoint endpoint) {
        return endpoint.name() == null ? label("unavailable_network") : endpoint.name();
    }

    private boolean reverse(UUID source, UUID target) {
        for (var r : rules)
            if (!r.terminated()
                    && r.source().network().equals(source)
                    && r.target().network().equals(target)) return true;
        return false;
    }

    private void toolbar(List<Row> actions, int y) {
        var c = layout.content();
        var bounds = new TerminalLayout.Rect(c.x() - 4, y - 8, c.width() + 8, 36);
        for (int i = 0; i < actions.size(); i++) {
            var row = actions.get(i);
            button(TerminalActionLayout.toolbarButton(bounds, actions.size(), i), row.text(), row.action(), false);
        }
    }

    private void button(TerminalLayout.Rect r, Component label, Runnable action, boolean primary) {
        var b = addRenderableWidget(new TerminalButton(
                r.x(),
                r.y(),
                r.width(),
                r.height(),
                label,
                ignored -> {
                    if ((!pending && !uncertain) || dialogText != null) action.run();
                },
                primary));
        b.active = (!pending && !uncertain) || dialogText != null;
        if (primary
                && dialogText != null
                && dialogSubmit == null
                && shownCode == null
                && dialogText.getContents()
                        instanceof net.minecraft.network.chat.contents.TranslatableContents prompt) {
            String key = prompt.getKey();
            b.setDanger(key.endsWith("terminate_confirm")
                    || key.endsWith("pair_close_confirm")
                    || key.endsWith("discard")
                    || key.endsWith("revoke_confirm")
                    || key.endsWith("remove_unknown"));
        }
        b.setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    private void enterCode() {
        input(text("enter_code"), "", value -> {
            proposalCode = value.trim();
            send(ExchangeRequest.RESOLVE, 0, bytes(b -> b.writeUtf(proposalCode, 22)));
        });
    }

    private void showCode(Code code) {
        suspendedInput = null;
        parameterInput = null;
        parameterEditor = null;
        shownCode = code;
        dialogText = text("code_help", code.value());
        dialogSubmit = null;
        dialogAction = () -> {
            minecraft.keyboardHandler.setClipboard(code.value());
            dialogText = null;
            message = text("copied");
            rebuild();
        };
        inputValue = "";
        rebuild();
    }

    private void change(ExchangeIntent.Action action) {
        intent(new ExchangeIntent.Change(detail.id(), detail.revision(), action));
    }

    private Component confirmationHeading() {
        if (dialogText != null
                && dialogText.getContents()
                        instanceof net.minecraft.network.chat.contents.TranslatableContents content) {
            String key = content.getKey();
            if (key.endsWith("terminate_confirm")) {
                Component heading = text("terminate");
                if (detail != null) {
                    var placement = placements.get(detail.id());
                    if (placement != null)
                        heading = heading.copy().append(" · ").append(placement.name());
                }
                return heading;
            }
            if (key.endsWith("pair_close_confirm"))
                return text("pair_close").copy().append(" · ").append(peerName());
            if (key.endsWith("discard")) return text("discard");
            if (key.endsWith("approve_confirm")) return text("approve");
            if (key.endsWith("revoke_confirm")) return text("revoke");
        }
        return text("confirm");
    }

    private void confirm(Component prompt, Runnable action) {
        suspendedInput = dialogSubmit == null
                ? null
                : new InputDialog(
                        dialogText,
                        dialogSubmit,
                        parameterInput,
                        parameterEditor,
                        parameterEditor == null ? inputValue : parameterEditor.rate,
                        inputOriginal,
                        message);
        parameterInput = null;
        parameterEditor = null;
        shownCode = null;
        dialogText = prompt;
        dialogAction = () -> {
            suspendedInput = null;
            dialogText = null;
            dialogAction = null;
            action.run();
        };
        dialogSubmit = null;
        rebuild();
    }

    void input(Component prompt, String initial, Consumer<String> apply) {
        input(prompt, initial, apply, null);
    }

    private void input(
            Component prompt,
            String initial,
            Consumer<String> apply,
            @Nullable TerminalResourceParameterView.Form parameter) {
        input(prompt, initial, apply, parameter, null);
    }

    private void input(
            Component prompt,
            String initial,
            Consumer<String> apply,
            @Nullable TerminalResourceParameterView.Form parameter,
            @Nullable ResourceParameterDraft editor) {
        suspendedInput = null;
        parameterEditor = editor;
        parameterInput = parameter;
        shownCode = null;
        dialogText = prompt;
        dialogSubmit = apply;
        dialogAction = null;
        inputValue = initial;
        inputOriginal = initial;
        message = null;
        rebuild();
    }

    private TerminalLayout.Rect dialogBounds() {
        return dialogSubmit == null
                ? TerminalDialogLayout.confirmation(layout.window(), font, dialogText)
                : parameterInput != null
                        ? TerminalResourceParameterLayout.of(layout.content(), parameterEditor != null, false)
                                .dialog()
                        : TerminalDialogLayout.editor(layout.window());
    }

    private void buildDialog() {
        var bounds = dialogBounds();
        var footer = TerminalActionLayout.of(bounds);
        boolean parameter = dialogSubmit != null && parameterInput != null;
        if (parameter) {
            var geometry = TerminalResourceParameterLayout.of(layout.content(), parameterEditor != null, false);
            input = TerminalResourceParameterView.build(
                    font,
                    geometry,
                    parameterForm(),
                    parameterEditor == null
                            ? new TerminalResourceParameterView.Bindings(value -> inputValue = value, null, null)
                            : parameterEditor.bindings(
                                    () -> {
                                        inputValue = parameterEditor.rate;
                                        message = null;
                                    },
                                    this::rebuild),
                    new TerminalResourceParameterView.Actions(
                            this::restoreParameter, this::cancelDialog, () -> applyInput(inputValue)),
                    this::addRenderableWidget);
            setFocused(input);
            return;
        }
        if (shownCode != null) {
            Code code = shownCode;
            button(
                    TerminalActionLayout.button(bounds, 3, 0),
                    text("revoke"),
                    () -> confirm(
                            text("revoke_confirm"),
                            () -> intent(new ExchangeIntent.RevokeCode(code.id(), code.revision()))),
                    false);
        }
        if (dialogSubmit != null) {
            var field = new TerminalLayout.Rect(bounds.x() + 12, bounds.y() + 48, bounds.width() - 24, 20);
            input = new TerminalEditBox(font, field.x(), field.y(), field.width(), field.height(), dialogText);
            input.setMaxLength(64);
            input.setValue(inputValue);
            input.setResponder(value -> inputValue = value);
            addRenderableWidget(input);
            setFocused(input);
        } else input = null;
        button(
                (shownCode == null ? footer.secondary() : TerminalActionLayout.button(bounds, 3, 1)),
                text("cancel"),
                this::cancelDialog,
                false);
        button(
                (shownCode == null ? footer.primary() : TerminalActionLayout.button(bounds, 3, 2)),
                text(shownCode != null ? "copy" : dialogSubmit == null ? "confirm" : "apply"),
                () -> {
                    if (dialogSubmit != null) {
                        applyInput(inputValue);
                    } else if (dialogAction != null) dialogAction.run();
                },
                true);
    }

    TerminalResourceParameterView.Form parameterForm() {
        return parameterEditor == null
                ? parameterInput.withRate(inputValue)
                : parameterEditor.form(parameterInput.unit(), true, true);
    }

    void applyInput(String value) {
        Consumer<String> apply = dialogSubmit;
        Component prompt = dialogText;
        try {
            dialogText = null;
            dialogSubmit = null;
            apply.accept(value);
            clearDialog();
            rebuild();
        } catch (IllegalArgumentException | ArithmeticException failure) {
            dialogText = prompt;
            dialogSubmit = apply;
            message = parameterInput == null ? text("invalid") : NodeResourcePolicyView.text("invalid");
            if (parameterEditor != null) parameterEditor.invalid = true;
            inputValue = value;
            rebuild();
        }
    }

    boolean editingParameter() {
        return dialogSubmit != null && parameterInput != null;
    }

    @Nullable
    Component inputError() {
        return message;
    }

    void cancelDialog() {
        var retained = suspendedInput;
        clearDialog();
        if (retained != null) {
            dialogText = retained.prompt();
            dialogSubmit = retained.submit();
            parameterInput = retained.form();
            parameterEditor = retained.editor();
            inputValue = retained.value();
            inputOriginal = retained.original();
            message = retained.error();
        }
        rebuild();
    }

    private void clearDialog() {
        message = null;
        suspendedInput = null;
        dialogText = null;
        dialogSubmit = null;
        dialogAction = null;
        parameterInput = null;
        parameterEditor = null;
        input = null;
        inputValue = "";
        inputOriginal = "";
    }

    void openParameterEditor(
            ResourceParameterDraft editor, Component unit, Consumer<ResourceTransferPolicy.InputOverride> apply) {
        input(
                text("rate_help", NodeResourcePolicyView.typeName(editor.id), unit),
                editor.rate,
                value -> {
                    editor.rate = value;
                    apply.accept(editor.value());
                },
                editor.form(unit, true, true),
                editor);
    }

    static Component parameterUnit(ResourceLocation type, String unit) {
        return type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM)
                ? NodeResourcePolicyView.text("unit.item")
                : Component.literal(unit);
    }

    private void editRate(ResourceLocation type) {
        Type known = types.stream().filter(t -> t.id().equals(type)).findFirst().orElse(null);
        if (known == null) {
            confirm(text("remove_unknown"), () -> {
                parameters.remove(type);
                dirty = true;
                go(Page.RATES);
            });
            return;
        }
        var editor = new ResourceParameterDraft(type, initialParameter(parameters, type, defaultRate, known.batch()));
        openParameterEditor(editor, parameterUnit(type, known.unit()), complete -> {
            parameters.put(type, complete);
            dirty = true;
            go(Page.RATES);
        });
    }

    private void restoreParameter() {
        if (parameterEditor == null) {
            applyInput(Long.toString(ExchangeTerms.DEFAULT_RATE));
            return;
        }
        parameters.remove(parameterEditor.id);
        dirty = true;
        clearDialog();
        go(Page.RATES);
    }

    static ResourceTransferPolicy.InputOverride initialParameter(
            Map<ResourceLocation, ResourceTransferPolicy.InputOverride> parameters,
            ResourceLocation type,
            long defaultLimit,
            long defaultBatch) {
        var override = parameters.get(type);
        return override == null
                ? new ResourceTransferPolicy.InputOverride(
                        defaultLimit, ResourceTransferPolicy.BatchMode.GREEDY, defaultBatch)
                : override;
    }

    private static long positive(String value) {
        long n = Long.parseLong(value.trim());
        if (n < 1) throw new IllegalArgumentException();
        return n;
    }

    private Component rateUnit(ResourceLocation type) {
        for (var known : types) if (known.id().equals(type)) return parameterUnit(type, known.unit());
        return NodeResourcePolicyView.text("native_units");
    }

    private String rateValueText(ResourceLocation type, long amount) {
        return Long.toString(amount);
    }

    private String rateText(@Nullable ResourceLocation type, long amount) {
        Type known = types.stream().filter(t -> t.id().equals(type)).findFirst().orElse(null);
        return amount
                + (known == null ? "" : " " + parameterUnit(type, known.unit()).getString());
    }

    @Override
    public void tick() {
        tick++;
        if (resourceSelection != null && resourceSelection.tick(tick)) rebuild();
        if (resourceSelection == null && search.due(tick)) {
            search.handled();
            firstRow = 0;
            rebuild();
        }
        if (pending && tick - sentTick > 240) {
            pending = false;
            upload.clear();
            downloaded = null;
            uncertain = true;
            message = text(submitted ? "unknown_result" : "timeout");
            rebuild();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, TerminalTheme.WORLD_DIM);
        TerminalTheme.renderWindow(g, layout);
        var top = TerminalHeaderLayout.topBarContent(layout.window());
        var header = TerminalHeaderLayout.atRightEdge(top, searchable() || page == Page.RATES);
        var name = TerminalNetworkContext.layout(header.remaining(), false, false);
        TerminalText.drawHeaderTitle(
                g,
                font,
                label(
                        page == Page.EDIT && !editingExisting
                                ? "page.create"
                                : "page." + page.name().toLowerCase(java.util.Locale.ROOT)),
                new TerminalLayout.Rect(top.x(), top.y(), name.x() - top.x() - 6, 20));
        TerminalText.drawNetworkLabel(
                network.name(),
                name,
                font::width,
                TerminalTheme.TEXT,
                (value, x, y, color, shadow) -> g.drawString(font, value, x, y, color, shadow));
        for (var label : formLabels) {
            var r = label.bounds();
            if (r.height() > 10) {
                var lines = font.split(TerminalText.body(label.text()), r.width());
                for (int i = 0; i < Math.min(lines.size(), r.height() / 10); i++)
                    g.drawString(font, lines.get(i), r.x(), r.y() + i * 10, TerminalTheme.MUTED, false);
            } else
                g.drawString(
                        font,
                        TerminalText.ellipsize(font, label.text().getString(), r.width()),
                        r.x(),
                        r.y(),
                        TerminalTheme.MUTED,
                        false);
            if (dialogText == null
                    && mouseX >= r.x()
                    && mouseX < r.right()
                    && mouseY >= r.y()
                    && mouseY < r.bottom()
                    && font.width(label.text()) > r.width())
                setTooltipForNextRenderPass(TerminalText.body(label.text()));
        }
        if (resourceSelection != null)
            ResourceTypeSelectionView.render(g, font, layout.content(), resourceSelection, tick);
        else if (page == Page.RATES)
            TerminalResourceSettingsList.renderPage(g, font, layout.content(), parameters.size(), firstRow);
        else if (listBounds != null)
            TerminalTheme.renderScrollbar(
                    g,
                    listBounds.right() - 6,
                    listBounds.y(),
                    listBounds.height(),
                    visibleRowCount(),
                    visibleRows,
                    firstRow);
        if (dialogText == null && (message != null || pending))
            g.drawString(
                    font,
                    TerminalText.ellipsize(
                            font,
                            (pending ? text("waiting") : message).getString(),
                            layout.content().width() - 8),
                    layout.content().x() + 4,
                    (editing()
                                    ? TerminalActionLayout.of(layout.content())
                                            .content()
                                            .bottom()
                                    : layout.content().bottom())
                            - 10,
                    TerminalTheme.MUTED,
                    false);
        if (dialogText != null) modalBackdrop.render(widget -> widget.render(g, -1, -1, partialTick));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderPageLayers(
                () -> super.render(graphics, mouseX, mouseY, partialTick),
                dialogText == null
                        ? null
                        : () -> modalBackdrop.renderForeground(
                                graphics, mouseX, mouseY, partialTick, () -> renderDialog(graphics)));
    }

    static void renderPageLayers(Runnable page, @Nullable Runnable dialog) {
        if (dialog == null) page.run();
        else TerminalForegroundLayer.ordered(page, dialog);
    }

    private void renderDialog(GuiGraphics g) {
        var bounds = dialogBounds();
        ModalBackdrop.renderShade(g, layout);
        if (parameterInput != null && dialogSubmit != null) {
            TerminalResourceParameterView.render(
                    g,
                    font,
                    TerminalResourceParameterLayout.of(layout.content(), parameterEditor != null, false),
                    parameterForm(),
                    inputError());
        } else TerminalTheme.renderDialogPanel(g, bounds);
        if (dialogSubmit == null) {
            TerminalText.drawDialogTitle(g, font, confirmationHeading(), bounds);
            int y = bounds.y() + 34;
            for (var line : font.split(TerminalText.body(dialogText), bounds.width() - 24)) {
                g.drawString(font, line, bounds.x() + 12, y, TerminalTheme.MUTED, false);
                y += 10;
            }
        } else if (parameterInput == null) {
            TerminalText.drawDialogTitle(g, font, dialogText, bounds);
        }
        if (dialogText != null && message != null && dialogSubmit != null && parameterInput == null) {
            var d = dialogBounds();
            TerminalDialogLayout.renderInputError(
                    g,
                    font,
                    input,
                    inputError(),
                    TerminalActionLayout.of(d).primary().y());
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (dialogText != null) return super.mouseScrolled(x, y, dx, dy);
        if (resourceSelection != null) {
            var bounds = ResourceTypeSelectionView.layout(layout.content(), resourceSelection)
                    .list();
            if (x >= bounds.rows().x()
                    && x < bounds.scrollbar().right()
                    && y >= bounds.rows().y()
                    && y < bounds.rows().bottom()
                    && dy != 0) {
                resourceSelection.wheel(dy, bounds.visibleRows());
                rebuild();
                return true;
            }
            return super.mouseScrolled(x, y, dx, dy);
        }
        if (page == Page.EDIT)
            for (var widget : children())
                if (widget instanceof TerminalIntervalBox field && field.mouseScrolled(x, y, dx, dy)) return true;
        if (listBounds != null
                && x >= listBounds.x()
                && x < listBounds.right()
                && y >= listBounds.y()
                && y < listBounds.bottom()
                && dy != 0) {
            firstRow = Math.clamp(firstRow + (dy > 0 ? -1 : 1), 0, Math.max(0, (visibleRowCount()) - visibleRows));
            rebuild();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return TerminalInteractionPolicy.dispatchClick(this, () -> handleMouseClick(x, y, button));
    }

    private boolean handleMouseClick(double x, double y, int button) {
        var currentSearch = activeSearch();
        boolean was = currentSearch.expanded();
        boolean handled = super.mouseClicked(x, y, button);
        if (currentSearch != activeSearch()) return handled;
        currentSearch.finishToggleClick(
                was,
                this,
                currentSearch.expanded()
                        ? resourceSelection == null
                                ? search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, tick))
                                : resourceSearchField
                        : null);
        return handled;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (TerminalInteractionPolicy.routeKey(
                getFocused(),
                key,
                scan,
                modifiers,
                () -> TerminalInteractionPolicy.inventoryShortcut(
                                minecraft.options.keyInventory, getFocused(), key, scan)
                        || parent.exchangeCloseKey(key, scan),
                () -> back(true),
                () -> ClientSearchState.handleToggleKey(
                        key,
                        modifiers,
                        dialogText == null && searchable() && !pending && !uncertain,
                        this::toggleSearch))) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            back(false);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    void back(boolean root) {
        if (root) {
            leave(true);
            return;
        }
        if (dialogText != null) {
            cancelDialog();
            return;
        }
        if (pending || uncertain) {
            leave(false);
            return;
        }
        if (resourceSelection != null) {
            if (resourceSelection.closeSearch(tick)) rebuild();
            else go(page == Page.SCOPE ? Page.EDIT : Page.RATES);
            return;
        }
        if (search.close(tick)) {
            rebuild();
            return;
        }
        if (page == Page.SCOPE || page == Page.RATES || page == Page.PRESETS) {
            go(Page.EDIT);
            return;
        }
        if (page == Page.TYPES) {
            go(Page.RATES);
            return;
        }
        if (page == Page.FILTER) {
            go(Page.DETAIL);
            return;
        }
        if (page == Page.EDIT) {
            if (new ClientDraftExit(draftDirty(), submitted).requiresConfirmation()) {
                confirm(text("discard"), () -> {
                    dirty = false;
                    go(editingExisting ? Page.DETAIL : Page.RULES);
                });
            } else go(editingExisting ? Page.DETAIL : Page.RULES);
            return;
        }
        if (page == Page.TUNNELS) {
            leave(false);
            return;
        }
        if (page == Page.RULES || page == Page.CODES) {
            selectedTunnel = null;
            history = false;
            intent(new ExchangeIntent.ListTunnels(false));
            return;
        }
        refreshDirectory();
    }

    private void leave(boolean root) {
        if (new ClientDraftExit(draftDirty(), submitted).requiresConfirmation()) {
            confirm(text("discard"), () -> {
                dirty = false;
                resourceSelection = null;
                leave(root);
            });
            return;
        }
        leaving = true;
        upload.clear();
        downloaded = null;
        if (root) {
            parent.closeFromConfiguration();
            minecraft.setScreen(null);
        } else {
            parent.sendExchange(generation, ExchangeRequest.CLOSE, 0, new byte[0]);
            parent.resumeFromConfiguration();
        }
    }

    void disconnected() {
        leaving = true;
        parent.closeFromConfiguration();
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }

    @Override
    public void removed() {
        if (!leaving) parent.closeFromConfiguration();
        upload.clear();
        downloaded = null;
    }

    @Override
    public void onClose() {
        back(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
