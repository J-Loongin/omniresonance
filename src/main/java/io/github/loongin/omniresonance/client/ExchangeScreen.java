// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

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
import io.netty.buffer.Unpooled;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

    private record Row(Component text, @Nullable Runnable action) {}

    private record DetailCell(
            Component label,
            Component value,
            int span,
            @Nullable Runnable action) {}

    private int detailRowCount;

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
    private final Map<ResourceLocation, Long> rates = new LinkedHashMap<>();
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
    private long defaultRate = Long.MAX_VALUE, libraryRevision, tick, sentTick;
    private int interval = 1, firstRow, visibleRows;
    private boolean allTypes = true, history, owner, opened, pending, dirty, submitted, leaving, editingExisting;
    private @Nullable ExchangeRequest outstanding;
    private byte[] downloaded, upload;
    private int downloadOffset, downloadKind, uploadOffset;
    private @Nullable Component message, dialogText;
    private @Nullable Runnable dialogAction;
    private @Nullable Code shownCode;
    private boolean uncertain;
    private @Nullable Consumer<String> dialogSubmit;
    private String inputValue = "", inputOriginal = "";
    private boolean intervalInput;
    private @Nullable TerminalEditBox input;

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
            upload = body;
            uploadOffset = 0;
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
                upload = null;
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
                if (upload == null) throw new IllegalArgumentException("Unexpected upload acknowledgement");
                if (uploadOffset < upload.length) {
                    int end = Math.min(upload.length, uploadOffset + ManagementTransferPool.MAXIMUM_FRAGMENT_BYTES);
                    byte[] chunk = Arrays.copyOfRange(upload, uploadOffset, end);
                    int start = uploadOffset;
                    uploadOffset = end;
                    send(ExchangeRequest.CHUNK, start, chunk);
                } else {
                    upload = null;
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
            upload = null;
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
        input(text("channel_name_prompt"), "", false, name -> {
            channelName = new io.github.loongin.omniresonance.network.ManagedName(name).value();
            editingExisting = false;
            detail = null;
            baseline = null;
            sending = true;
            allTypes = true;
            selectedTypes.clear();
            rates.clear();
            filter = new ExchangeTermsDraft.None();
            filterName = "";
            mode = FilterMode.WHITELIST;
            defaultRate = Long.MAX_VALUE;
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
        rates.clear();
        rates.putAll(baseline.rates());
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
                rates,
                interval);
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
                || page == Page.SCOPE
                || page == Page.RATES;
    }

    private boolean editing() {
        return page == Page.EDIT
                || page == Page.SCOPE
                || page == Page.RATES
                || page == Page.TYPES
                || page == Page.PRESETS;
    }

    private void toggleSearch() {
        if (pending || dialogText != null) return;
        search.toggle(tick);
        firstRow = 0;
        rebuild();
        if (search.expanded())
            setFocused(search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, tick)));
    }

    private TerminalLayout.Rect searchBounds() {
        var c = layout.content();
        return new TerminalLayout.Rect(c.x() + 4, c.y(), c.width() - (creationPage() ? 34 : 14), 20);
    }

    private void rebuild() {
        if (layout == null) return;
        var focused = getFocused();
        boolean keepSearch = search.expanded() && focused instanceof TerminalSearchBox;
        results.clear();
        formLabels.clear();
        clearWidgets();
        setFocused(null);
        var c = layout.content();
        var h = TerminalHeaderLayout.atRightEdge(TerminalHeaderLayout.topBarContent(layout.window()), searchable());
        if (dialogText != null) {
            buildDialog();
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
        } else if (page == Page.SCOPE) {
            content.add(new Row(text(allTypes ? "all_selected" : "all"), () -> {
                allTypes = true;
                changed();
            }));
            Set<ResourceLocation> choices = new LinkedHashSet<>();
            for (var type : types) choices.add(type.id());
            choices.addAll(selectedTypes);
            for (var type : choices)
                content.add(new Row(
                        Component.literal((!allTypes && selectedTypes.contains(type) ? "[+] " : "[ ] ")
                                + NodeResourcePolicyView.typeName(type).getString()),
                        () -> {
                            if (allTypes) {
                                allTypes = false;
                                selectedTypes.clear();
                            }
                            if (!selectedTypes.remove(type)) selectedTypes.add(type);
                            changed();
                        }));
        } else if (page == Page.RATES) {
            content.add(new Row(
                    text("default_value", rateText(null, defaultRate)),
                    () -> input(
                            text("default_help"),
                            defaultRate == Long.MAX_VALUE ? "" : Long.toString(defaultRate),
                            false,
                            value -> {
                                defaultRate = value.isBlank() ? Long.MAX_VALUE : positive(value);
                                dirty = true;
                            })));
            toolbar(List.of(new Row(text("add_type"), () -> go(Page.TYPES))), y);
            y += 36;
            for (var entry : rates.entrySet())
                content.add(new Row(
                        Component.literal(NodeResourcePolicyView.typeName(entry.getKey())
                                        .getString() + " · " + rateText(entry.getKey(), entry.getValue())),
                        () -> editRate(entry.getKey())));
        } else if (page == Page.TYPES) {
            for (var type : types)
                if (!rates.containsKey(type.id()))
                    content.add(new Row(NodeResourcePolicyView.typeName(type.id()), () -> editRate(type.id())));
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
            button(footer.secondary(), text("cancel"), () -> back(false), false);
            button(
                    footer.primary(),
                    text(page == Page.EDIT ? "save" : "back"),
                    page == Page.EDIT ? this::save : () -> go(page == Page.TYPES ? Page.RATES : Page.EDIT),
                    true);
        }
        if (message != null || pending) bottom -= 14;
        listBounds = new TerminalLayout.Rect(c.x() + 4, y, c.width() - 8, Math.max(0, bottom - y));
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
            button.setTooltip(Tooltip.create(TerminalText.body(row.text())));
            results.add(button);
        }
    }

    private int visibleRowCount() {
        return page == Page.EDIT ? termsRows() : page == Page.DETAIL ? detailRowCount : rows.size();
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
            for (var entry : baseline.rates().entrySet())
                extra.add(new DetailCell(
                        NodeResourcePolicyView.typeName(entry.getKey()),
                        Component.literal(rateText(entry.getKey(), entry.getValue())),
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
                if (field.action() != null) button(control, field.value(), field.action(), false);
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
        return termsWarning() ? 4 : 3;
    }

    private void buildTermsForm() {
        var c = layout.content();
        var footer = TerminalActionLayout.of(c);
        button(
                new TerminalLayout.Rect(c.x() + 12, c.y(), c.width() - 24, 20),
                text("channel_named", channelName),
                () -> input(text("channel_name_prompt"), channelName, false, value -> {
                    channelName = new io.github.loongin.omniresonance.network.ManagedName(value).value();
                    dirty = true;
                }),
                false);
        formLabels.add(new FormLabel(
                text(sending ? "sending_to" : "receiving_from", peerName()),
                new TerminalLayout.Rect(c.x() + 12, c.y() + 23, c.width() - 24, 10)));
        int top = c.y() + 36;
        int available = footer.content().bottom() - top - (message != null || pending ? 14 : 0);
        visibleRows = Math.max(1, available / 32);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, termsRows() - visibleRows));
        listBounds = new TerminalLayout.Rect(c.x() + 12, top, c.width() - 24, Math.max(0, available));
        boolean active = !pending && !uncertain;
        for (int i = firstRow; i < Math.min(termsRows(), firstRow + visibleRows); i++) {
            var row = new TerminalLayout.Rect(listBounds.x(), top + (i - firstRow) * 32, listBounds.width() - 6, 32);
            if (i == 0) {
                button(
                        formCell(row, 0, 1, text("direction")),
                        text(sending ? "send" : "receive"),
                        () -> {
                            sending = !sending;
                            changed();
                        },
                        false);
                button(
                        formCell(row, 1, 1, text("scope")),
                        text(allTypes ? "all" : "type_count", selectedTypes.size()),
                        () -> go(Page.SCOPE),
                        false);
                addRenderableWidget(intervalControl(
                        font, formCell(row, 2, 1, text("interval_label")), intervalDraft, active, value -> {
                            intervalDraft = value;
                            dirty = true;
                        }));
            } else if (i == 1) {
                button(
                        formCell(row, 0, 2, text("filter_label")),
                        Component.literal(filterName.isEmpty() ? label("filter_required") : filterName),
                        () -> send(ExchangeRequest.PRESETS, 0, new byte[0]),
                        false);
                button(
                        formCell(row, 2, 1, text("mode_label")),
                        text(mode == FilterMode.WHITELIST ? "whitelist" : "blacklist"),
                        () -> {
                            mode = mode == FilterMode.WHITELIST ? FilterMode.BLACKLIST : FilterMode.WHITELIST;
                            changed();
                        },
                        false);
            } else if (i == 2) {
                button(
                        new TerminalLayout.Rect(row.x(), row.y() + 6, row.width(), 20),
                        text("rates_value", rates.size()),
                        () -> go(Page.RATES),
                        false);
            } else {
                Component warning = text(editingExisting ? "revision_warning" : "cycle");
                if (editingExisting
                        && reverse(detail.target().network(), detail.source().network()))
                    warning = warning.copy().append(" · ").append(text("cycle"));
                formLabels.add(new FormLabel(warning, new TerminalLayout.Rect(row.x(), row.y() + 4, row.width(), 20)));
            }
        }
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

    private TerminalLayout.Rect formCell(TerminalLayout.Rect row, int column, int span, Component label) {
        var control = TerminalFormGrid.control(row, 3, column, span);
        formLabels.add(new FormLabel(label, new TerminalLayout.Rect(control.x(), row.y(), control.width(), 10)));
        return control;
    }

    private static Row info(String key, String value) {
        return new Row(text("field", label(key), value), null);
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
        b.setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    private void enterCode() {
        input(text("enter_code"), "", false, value -> {
            proposalCode = value.trim();
            send(ExchangeRequest.RESOLVE, 0, bytes(b -> b.writeUtf(proposalCode, 22)));
        });
    }

    private void showCode(Code code) {
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

    private void confirm(Component prompt, Runnable action) {
        shownCode = null;
        dialogText = prompt;
        dialogAction = () -> {
            dialogText = null;
            dialogAction = null;
            action.run();
        };
        dialogSubmit = null;
        rebuild();
    }

    private void input(Component prompt, String initial, boolean interval, Consumer<String> apply) {
        shownCode = null;
        dialogText = prompt;
        dialogSubmit = apply;
        dialogAction = null;
        inputValue = initial;
        inputOriginal = initial;
        message = null;
        intervalInput = interval;
        rebuild();
    }

    private void buildDialog() {
        var bounds = dialogSubmit == null
                ? TerminalDialogLayout.confirmation(layout.window(), font, dialogText)
                : TerminalDialogLayout.editor(layout.window());
        var footer = TerminalActionLayout.of(bounds);
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
            input = intervalInput
                    ? new TerminalIntervalBox(
                            font, bounds.x() + 12, bounds.y() + 48, bounds.width() - 24, 20, dialogText)
                    : new TerminalEditBox(font, bounds.x() + 12, bounds.y() + 48, bounds.width() - 24, 20, dialogText);
            input.setMaxLength(64);
            input.setValue(inputValue);
            input.setResponder(value -> inputValue = value);
            addRenderableWidget(input);
            setFocused(input);
        } else input = null;
        button(
                (shownCode == null ? footer.secondary() : TerminalActionLayout.button(bounds, 3, 1)),
                text("cancel"),
                () -> {
                    dialogText = null;
                    dialogSubmit = null;
                    dialogAction = null;
                    rebuild();
                },
                false);
        button(
                (shownCode == null ? footer.primary() : TerminalActionLayout.button(bounds, 3, 2)),
                text(shownCode != null ? "copy" : dialogSubmit == null ? "confirm" : "apply"),
                () -> {
                    if (dialogSubmit != null) {
                        Consumer<String> apply = dialogSubmit;
                        Component prompt = dialogText;
                        String value = inputValue;
                        try {
                            dialogText = null;
                            dialogSubmit = null;
                            apply.accept(value);
                            rebuild();
                        } catch (IllegalArgumentException | ArithmeticException failure) {
                            dialogText = prompt;
                            dialogSubmit = apply;
                            message = text("invalid");
                            rebuild();
                        }
                    } else if (dialogAction != null) dialogAction.run();
                },
                true);
    }

    private void editRate(ResourceLocation type) {
        Type known = types.stream().filter(t -> t.id().equals(type)).findFirst().orElse(null);
        if (known == null) {
            confirm(text("remove_unknown"), () -> {
                rates.remove(type);
                dirty = true;
                go(Page.RATES);
            });
            return;
        }
        long value = rates.getOrDefault(type, known.batch());
        boolean fluid = known.unit().equals("mB");
        input(
                text("rate_help", NodeResourcePolicyView.typeName(type), fluid ? "B" : known.unit()),
                fluid ? BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString() : Long.toString(value),
                false,
                v -> {
                    if (v.isBlank()) rates.remove(type);
                    else {
                        long amount = fluid
                                ? new BigDecimal(v.trim()).movePointRight(3).longValueExact()
                                : positive(v);
                        if (amount < 1) throw new IllegalArgumentException();
                        rates.put(type, amount);
                    }
                    dirty = true;
                    go(Page.RATES);
                });
    }

    private static long positive(String value) {
        long n = Long.parseLong(value.trim());
        if (n < 1) throw new IllegalArgumentException();
        return n;
    }

    private String rateText(@Nullable ResourceLocation type, long amount) {
        if (amount == Long.MAX_VALUE) return label("unlimited");
        Type known = types.stream().filter(t -> t.id().equals(type)).findFirst().orElse(null);
        return known != null && known.unit().equals("mB")
                ? BigDecimal.valueOf(amount, 3).stripTrailingZeros().toPlainString() + " B"
                : amount + (known == null ? "" : " " + known.unit());
    }

    @Override
    public void tick() {
        tick++;
        if (search.due(tick)) {
            search.handled();
            firstRow = 0;
            rebuild();
        }
        if (pending && tick - sentTick > 240) {
            pending = false;
            upload = null;
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
        var header = TerminalHeaderLayout.atRightEdge(top, searchable());
        var name = TerminalNetworkContext.layout(header.remaining(), false, false);
        TerminalText.drawHeaderTitle(
                g,
                font,
                label("page." + page.name().toLowerCase(java.util.Locale.ROOT)),
                new TerminalLayout.Rect(top.x(), top.y(), name.x() - top.x() - 6, 20));
        TerminalText.drawNetworkLabel(
                network.name(),
                name,
                font::width,
                TerminalTheme.TEXT,
                (value, x, y, color, shadow) -> g.drawString(font, value, x, y, color, shadow));
        TerminalTheme.renderPanel(g, layout.content());
        if (dialogText == null)
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
                if (mouseX >= r.x()
                        && mouseX < r.right()
                        && mouseY >= r.y()
                        && mouseY < r.bottom()
                        && font.width(label.text()) > r.width())
                    setTooltipForNextRenderPass(TerminalText.body(label.text()));
            }
        if (dialogText != null) {
            var bounds = dialogSubmit == null
                    ? TerminalDialogLayout.confirmation(layout.window(), font, dialogText)
                    : TerminalDialogLayout.editor(layout.window());
            TerminalDialogLayout.render(g, layout.window(), bounds);
            int y = bounds.y() + 12;
            for (var line : font.split(TerminalText.body(dialogText), bounds.width() - 24)) {
                g.drawString(font, line, bounds.x() + 12, y, TerminalTheme.TEXT, false);
                y += 10;
            }
        } else {
            if (listBounds != null)
                TerminalTheme.renderScrollbar(
                        g,
                        listBounds.right() - 6,
                        listBounds.y(),
                        listBounds.height(),
                        visibleRowCount(),
                        visibleRows,
                        firstRow);
            if (message != null || pending)
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
        }
        if (dialogText != null && message != null && dialogSubmit != null) {
            var d = TerminalDialogLayout.editor(layout.window());
            g.drawString(
                    font,
                    TerminalText.ellipsize(font, message.getString(), d.width() - 24),
                    d.x() + 12,
                    d.y() + 74,
                    TerminalTheme.ERROR,
                    false);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (dialogText != null) return super.mouseScrolled(x, y, dx, dy);
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
        boolean was = search.expanded();
        boolean handled = super.mouseClicked(x, y, button);
        search.finishToggleClick(
                was,
                this,
                search.expanded()
                        ? search.field(font, searchBounds(), text("search"), 256, v -> search.edit(v, tick))
                        : null);
        return handled;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (NetworkSetupScreen.routeKey(
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

    private void back(boolean root) {
        if (root) {
            leave(true);
            return;
        }
        if (dialogText != null) {
            dialogText = null;
            dialogAction = null;
            dialogSubmit = null;
            rebuild();
            return;
        }
        if (pending || uncertain) {
            leave(false);
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
            if (new ClientDraftExit(dirty || dialogSubmit != null && !inputValue.equals(inputOriginal), submitted)
                    .requiresConfirmation()) {
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
        if (new ClientDraftExit(dirty || dialogSubmit != null && !inputValue.equals(inputOriginal), submitted)
                .requiresConfirmation()) {
            confirm(text("discard"), () -> {
                dirty = false;
                leave(root);
            });
            return;
        }
        leaving = true;
        upload = null;
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
        upload = null;
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
