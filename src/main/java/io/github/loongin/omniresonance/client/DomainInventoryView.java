// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.DomainInventoryFrame;
import io.github.loongin.omniresonance.storage.DomainLedger;
import io.github.loongin.omniresonance.transfer.FluidVariant;
import io.github.loongin.omniresonance.transfer.ItemVariant;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

/**
 * One client-owned inventory surface with server-confirmed mutations and no item prediction. Metadata is cached only for live IDs in this view; removals and
 * close evict it. Query construction is bounded to 128 units and a soft 2 ms per client tick, with immutable
 * result replacement. Only visible grid buttons exist; quantities are always read from the authoritative mirror.
 */
final class DomainInventoryView implements AutoCloseable {
    private static final int CELL = DomainInventoryLayout.CELL;
    private final DomainInventoryReceiver receiver = new DomainInventoryReceiver();
    private final ResourceAdapterDirectory adapters =
            io.github.loongin.omniresonance.bootstrap.ResourceAdapters.create();
    private final Map<Long, Metadata> metadata = new HashMap<>();
    private final List<AbstractWidget> cells = new ArrayList<>();
    private final DomainInventorySearch search;
    private final Runnable retry;
    private @Nullable Font font;
    private TerminalLayout.Rect body = new TerminalLayout.Rect(0, 0, 0, 0);
    private @Nullable Consumer<AbstractWidget> add;
    private @Nullable Consumer<GuiEventListener> remove;
    private @Nullable TerminalSearchBox field;
    private @Nullable TerminalButton sortButton;
    private @Nullable TerminalButton retryButton;
    private String query = "";
    private int scroll, pendingTicks, tooltipScroll, tooltipMaximum, statusAgeTicks, loadingTicks;
    private long tooltipId = -1, tooltipAmount = -1;
    private @Nullable TerminalTagPopup tagPopup;
    private String popupResourceType = "";
    private @Nullable Component localNotice;
    private int noticeTicks;
    private int tooltipWidth;
    private List<net.minecraft.util.FormattedCharSequence> tooltipRows = List.of();
    private long shownVersion = -1;
    private boolean cellsDirty = true;
    private @Nullable Cell hovered;

    private @Nullable UUID viewId, sessionId;
    private long generation, operationSequence, pendingOperation;
    private boolean writable, showInventory;
    private final TerminalQuickMoveGesture quickMove = new TerminalQuickMoveGesture();
    private final TerminalClickQueue clicks = new TerminalClickQueue();
    private final TerminalMenuRevision menuRevision = new TerminalMenuRevision();
    private @Nullable Consumer<io.github.loongin.omniresonance.networking.TerminalStorageRequest> storageSender;
    private @Nullable io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status operationStatus;

    @Nullable
    RecipeGhostTarget.Hover recipeHover(double x, double y, net.minecraft.core.HolderLookup.Provider provider) {
        if (tagPopup != null || !receiver.mirror().ready()) return null;
        for (var widget : cells) {
            if (!(widget instanceof Cell cell) || !cell.isMouseOver(x, y)) continue;
            var row = receiver.mirror().entries().get(cell.id);
            if (row == null || row.amount() <= 0) return null;
            var decoded = adapters.decode(row.key(), provider).orElse(null);
            Object value = decoded instanceof ItemVariant item
                    ? item.stack(1)
                    : decoded instanceof FluidVariant fluid
                            ? fluid.stack(1000)
                            : decoded == io.github.loongin.omniresonance.transfer.EnergyVariant.INSTANCE
                                    ? decoded
                                    : decoded
                                                    instanceof
                                                    io.github.loongin.omniresonance.transfer.RegisteredResourceVariant
                                                            registered
                                            ? registered.recipeIngredient()
                                            : null;
            return value == null
                    ? null
                    : new RecipeGhostTarget.Hover(
                            value,
                            new RecipeGhostTarget.Area(cell.getX(), cell.getY(), cell.getWidth(), cell.getHeight()));
        }
        var minecraft = Minecraft.getInstance();
        int slot = geometry().inventorySlot(x, y);
        if (slot >= 0 && minecraft != null && minecraft.player != null) {
            var stack = minecraft.player.getInventory().getItem(slot);
            if (!stack.isEmpty())
                return new RecipeGhostTarget.Hover(
                        stack.copyWithCount(1),
                        new RecipeGhostTarget.Area(
                                geometry().slotX(slot), geometry().slotY(slot), CELL, CELL));
        }
        return null;
    }

    boolean popupOpen() {
        return tagPopup != null;
    }

    boolean dismissPopup(int key) {
        if (tagPopup != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            tagPopup = null;
            return true;
        }
        return false;
    }

    boolean resourceShortcut(int key, int scan, int modifiers) {
        if (!Screen.isCopy(key) || hovered == null || !receiver.mirror().ready()) return false;
        var info = hovered.info;
        if (info.tags.isEmpty()) {
            notice(text("no_tags"));
            return true;
        }
        var minecraft = Minecraft.getInstance();
        popupResourceType = info.type;
        tagPopup = new TerminalTagPopup(
                info.type,
                info.tags,
                new TerminalLayout.Rect(hovered.getX(), hovered.getY(), hovered.getWidth(), hovered.getHeight()),
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                value -> font.width(TerminalText.body(Component.literal(value))));
        return true;
    }

    private void notice(Component message) {
        localNotice = message;
        noticeTicks = 60;
    }

    void bindStorage(UUID view, Consumer<io.github.loongin.omniresonance.networking.TerminalStorageRequest> sender) {
        viewId = view;
        storageSender = sender;
    }

    void accept(io.github.loongin.omniresonance.networking.TerminalStorageResponse response) {
        if (!response.session().equals(sessionId) || response.generation() != generation) return;
        if (response.sequence() != 0 && response.sequence() != pendingOperation) return;
        var player = Minecraft.getInstance().player;
        if (player != null) menuRevision.acknowledge(response.menuState(), player.inventoryMenu.getStateId());
        writable = response.writable();
        if (writable && !showInventory) {
            showInventory = true;
            cellsDirty = true;
        }
        if (response.sequence() != 0) {
            operationStatus = response.status();
            statusAgeTicks = 0;
            if (response.status()
                            != io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.WAITING_BUDGET
                    && response.status()
                            != io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.PROGRESS) {
                pendingOperation = 0;
                if (response.status()
                        != io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.COMPLETE)
                    quickMove.clear();
                clicks.finish(response.status()
                        == io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.COMPLETE);
            }
        }
        if (!writable) clicks.clear();
        dispatchClick();
    }

    private DomainInventoryLayout geometry() {
        return new DomainInventoryLayout(body, showInventory);
    }

    private void click(long id, int slot, int button) {
        if (!writable || !receiver.mirror().ready()) return;
        boolean shift = Screen.hasShiftDown();
        boolean bulk = quickMove.click(slot, button, shift, net.minecraft.Util.getMillis());
        if (!clicks.offer(new TerminalClickQueue.Click(id, slot, button, shift, bulk))) return;
        Minecraft.getInstance()
                .getSoundManager()
                .play(TerminalClickButton.clickFeedback().createSound());
        dispatchClick();
    }

    private void dispatchClick() {
        var player = Minecraft.getInstance().player;
        if (!writable
                || !receiver.mirror().ready()
                || pendingOperation != 0
                || player == null
                || storageSender == null
                || sessionId == null
                || viewId == null) return;
        var click = clicks.start();
        if (click == null) return;
        pendingOperation = Math.incrementExact(operationSequence);
        operationSequence = pendingOperation;
        operationStatus = null;
        pendingTicks = 0;
        storageSender.accept(new io.github.loongin.omniresonance.networking.TerminalStorageRequest(
                viewId,
                sessionId,
                generation,
                pendingOperation,
                click.resourceId(),
                menuRevision.current(player.inventoryMenu.getStateId()),
                click.slot(),
                click.button(),
                click.shift(),
                click.bulk()));
    }

    private int inventoryX() {
        return geometry().gridX();
    }

    private int inventoryY() {
        return geometry().inventoryY();
    }

    private int gridBottom() {
        return geometry().gridBottom();
    }

    boolean mouseClicked(double x, double y, int button) {
        if (tagPopup != null) {
            var choice = tagPopup.click(x, y, button);
            if (choice.tag() != null) {
                TerminalTagClipboard.copy(popupResourceType, List.of(choice.tag()));
                notice(text("tags_copied", 1));
                Minecraft.getInstance()
                        .getSoundManager()
                        .play(TerminalClickButton.clickFeedback().createSound());
            }
            if (choice.dismiss()) tagPopup = null;
            return true;
        }
        if (button < 0 || button > 1) return false;
        int slot = geometry().inventorySlot(x, y);
        if (slot >= 0) {
            click(0, slot, button);
            return true;
        }
        quickMove.clear();
        if (!geometry().inGrid(x, y)) return false;
        // Consume read-only grid clicks so the screen cannot focus a resource button.
        if (!writable) return true;
        for (var widget : cells)
            if (widget instanceof Cell cell && cell.isMouseOver(x, y)) {
                if (!cell.info.opaque) click(cell.id, -1, button);
                return true;
            }
        click(0, -1, button);
        return true;
    }

    void renderCarried(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (tagPopup != null) return;
        var player = Minecraft.getInstance().player;
        if (player == null
                || !showInventory
                || player.inventoryMenu.getCarried().isEmpty()) return;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);
        graphics.renderItem(player.inventoryMenu.getCarried(), mouseX - 8, mouseY - 8);
        graphics.renderItemDecorations(font, player.inventoryMenu.getCarried(), mouseX - 8, mouseY - 8);
        graphics.pose().popPose();
    }

    DomainInventoryView(Runnable retry) {
        this.retry = retry;
        search = new DomainInventorySearch(
                new DomainInventorySearch.Source() {
                    public DomainLedger.Cursor after(long cursor, long ceiling) {
                        return receiver.mirror().after(cursor, ceiling);
                    }

                    public long maximumId() {
                        return receiver.mirror().maximumId();
                    }

                    public long version() {
                        return receiver.version();
                    }
                },
                entry -> describe(entry).document());
    }

    void invalidateMetadata() {
        metadata.clear();
        tooltipId = -1;
        search.refresh();
        cellsDirty = true;
    }

    void request(UUID session, long generation) {
        receiver.request(session, generation);
        menuRevision.clear();
        quickMove.clear();
        clicks.clear();
        sessionId = session;
        this.generation = generation;
        writable = false;
        showInventory = true;
        loadingTicks = 0;
        operationSequence = pendingOperation = 0;
        operationStatus = null;
        metadata.clear();
        search.close();
        cellsDirty = true;
    }

    void accept(DomainInventoryFrame frame) {
        boolean accepted = receiver.accept(frame);
        if (accepted && receiver.lastChange() != null && receiver.lastChange().amount() == 0) {
            metadata.remove(receiver.lastChange().sequence());
            cellsDirty = true;
        }
        if (receiver.mirror().failed()) {
            clicks.clear();
            pendingOperation = 0;
            metadata.clear();
            search.close();
            cellsDirty = true;
        }
    }

    TerminalSearchBox build(
            Font font, TerminalLayout.Rect body, Consumer<AbstractWidget> add, Consumer<GuiEventListener> remove) {
        this.font = font;
        this.body = body;
        tagPopup = null;
        this.add = add;
        this.remove = remove;
        cells.clear();
        retryButton = null;
        var geometry = geometry();
        field = new TerminalSearchBox(
                font, geometry.gridX(), body.y() + 3, geometry.columns() * CELL, 20, text("search"));
        field.setMaxLength(256);
        field.setHint(TerminalText.body(text("search")));
        field.setValue(query);
        field.setResponder(value -> {
            query = value;
            search.query(value);
            scroll = 0;
        });
        field.active = !receiver.mirror().failed();
        field.setEditable(field.active);
        add.accept(field);
        var help = new TerminalRowButton(
                body.x() + 2, geometry.gridY() + 24, 20, 20, Component.literal("?"), ignored -> {});
        help.setReadOnly();
        help.setTooltip(Tooltip.create(TerminalText.body(text("search_help"))));
        add.accept(help);
        sortButton = new TerminalButton(
                body.x() + 2,
                geometry.gridY(),
                20,
                20,
                text("sort_icon"),
                ignored -> {
                    search.sort(
                            DomainInventorySearch.Sort.values()[
                                    (search.sort().ordinal() + 1) % DomainInventorySearch.Sort.values().length]);
                    sortButton.setTooltip(Tooltip.create(
                            TerminalText.body(sortLabel().copy().append("\n").append(text("sort_help")))));
                    scroll = 0;
                },
                false);
        sortButton.active = field.active;
        sortButton.setTooltip(
                Tooltip.create(TerminalText.body(sortLabel().copy().append("\n").append(text("sort_help")))));
        add.accept(sortButton);
        cellsDirty = true;
        refreshCells();
        return field;
    }

    void tick() {
        tick(Screen.hasShiftDown(), System::nanoTime);
    }

    void tick(boolean frozen, java.util.function.LongSupplier clock) {
        if (!receiver.mirror().ready() && !TerminalInteractionPolicy.loadingVisible(loadingTicks)) loadingTicks++;
        if (noticeTicks > 0 && --noticeTicks == 0) localNotice = null;
        if (pendingOperation == 0
                && operationStatus != null
                && operationStatus != io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.UNKNOWN
                && operationStatus != io.github.loongin.omniresonance.networking.TerminalStorageResponse.Status.FAILED
                && ++statusAgeTicks >= 60) operationStatus = null;
        if (pendingOperation != 0 && pendingTicks < 6) pendingTicks++;
        if (font == null || add == null) return;
        boolean ready = receiver.mirror().ready();
        field.active = !receiver.mirror().failed();
        field.setEditable(ready);
        sortButton.active = field.active;
        if (ready) {
            long started = clock.getAsLong();
            search.tick(128, () -> clock.getAsLong() - started < 2_000_000L, frozen);
            if (shownVersion != search.displayVersion()) {
                shownVersion = search.displayVersion();
                cellsDirty = true;
            }
        }
        if (receiver.mirror().failed() && retryButton == null) {
            retryButton = new TerminalButton(
                    body.x() + 8,
                    body.y() + 66,
                    80,
                    20,
                    Component.translatable("omniresonance.terminal.retry"),
                    ignored -> retry.run(),
                    true);
            add.accept(retryButton);
        } else if (!receiver.mirror().failed() && retryButton != null) {
            remove.accept(retryButton);
            retryButton = null;
        }
        if (cellsDirty) refreshCells();
    }

    private int columns() {
        return geometry().columns();
    }

    private int visibleRows() {
        return geometry().rows();
    }

    private int totalRows() {
        int size = search.ids().size();
        return size / columns() + (size % columns() == 0 ? 0 : 1);
    }

    boolean wheel(double x, double y, double amount) {
        if (tagPopup != null) {
            if (tagPopup.contains(x, y)) tagPopup.scroll(amount);
            return true;
        }
        if (!geometry().inGrid(x, y)) return false;
        scroll = Math.clamp(
                scroll + (amount > 0 ? -1 : amount < 0 ? 1 : 0), 0, Math.max(0, totalRows() - visibleRows()));
        cellsDirty = true;
        refreshCells();
        return true;
    }

    private void refreshCells() {
        if (add == null || remove == null) return;
        for (var widget : cells) remove.accept(widget);
        cells.clear();
        cellsDirty = false;
        hovered = null;
        if (!receiver.mirror().ready()) return;
        scroll = Math.clamp(scroll, 0, Math.max(0, totalRows() - visibleRows()));
        int first = scroll * columns();
        int count = Math.min(columns() * visibleRows(), search.ids().size() - first);
        for (int i = 0; i < count; i++) {
            long id = search.ids().get(first + i);
            var entry = receiver.mirror().entries().get(id);
            var info = metadata.get(id);
            if (entry == null || info == null) continue;
            var cell = new Cell(
                    geometry().gridX() + (i % columns()) * CELL, geometry().gridY() + (i / columns()) * CELL, id, info);
            cells.add(cell);
            add.accept(cell);
        }
    }

    void renderBody(GuiGraphics graphics, Font font) {
        var geometry = geometry();
        for (int row = 0; row < geometry.rows(); row++)
            for (int col = 0; col < geometry.columns(); col++)
                renderSlot(graphics, geometry.gridX() + col * CELL, geometry.gridY() + row * CELL, false);
        if (showInventory && Minecraft.getInstance().player != null) {
            var inventory = Minecraft.getInstance().player.getInventory();
            for (int i = 0; i < 36; i++) {
                int x = geometry.slotX(i), y = geometry.slotY(i);
                renderSlot(graphics, x, y, false);
                var stack = inventory.getItem(i);
                graphics.renderItem(stack, x + 1, y + 1);
                graphics.renderItemDecorations(font, stack, x + 1, y + 1);
            }
        }
        if (receiver.mirror().failed()) {
            String reason = receiver.failureReason() == DomainInventoryFrame.Reason.CHANGING_TOO_FAST
                    ? "changing_fast"
                    : "failed";
            graphics.drawWordWrap(
                    font,
                    TerminalText.body(text(reason)),
                    body.x() + 8,
                    body.y() + 36,
                    body.width() - 16,
                    TerminalTheme.ERROR);
        } else if (!receiver.mirror().ready()) {
            if (!TerminalInteractionPolicy.loadingVisible(loadingTicks)) return;
            graphics.drawWordWrap(
                    font,
                    TerminalText.body(
                            receiver.started()
                                    ? text("loading", receiver.receivedBaseCount(), receiver.initialCount())
                                    : text("waiting")),
                    body.x() + 8,
                    body.y() + 36,
                    body.width() - 16,
                    TerminalTheme.MUTED);
        } else {
            Component status = localNotice != null
                    ? localNotice
                    : search.invalid()
                            ? text("invalid_query")
                            : search.working()
                                    ? text("searching")
                                    : operationStatus != null
                                                    && operationStatus
                                                            != io.github.loongin.omniresonance.networking
                                                                    .TerminalStorageResponse.Status.COMPLETE
                                            ? text("access_"
                                                    + operationStatus.name().toLowerCase(Locale.ROOT))
                                            : pendingOperation != 0 && pendingTicks >= 6
                                                    ? text("access_pending")
                                                    : writable ? Component.empty() : text("read_only");
            graphics.drawString(
                    font,
                    TerminalText.body(Component.literal(
                            TerminalText.ellipsize(font, status.getString(), geometry.columns() * CELL))),
                    geometry.gridX(),
                    geometry.statusY(),
                    search.invalid() ? TerminalTheme.ERROR : TerminalTheme.MUTED,
                    false);
            TerminalTheme.renderScrollbar(
                    graphics,
                    geometry.gridRight() + 3,
                    geometry.gridY(),
                    geometry.rows() * CELL,
                    totalRows(),
                    visibleRows(),
                    scroll);
        }
    }

    void renderTooltip(GuiGraphics graphics, Font font, TerminalLayout.Rect window, int mouseX, int mouseY) {
        if (tagPopup != null) {
            tagPopup.render(graphics, font, mouseX, mouseY);
            return;
        }
        hovered = null;
        var player = Minecraft.getInstance().player;
        int slot = geometry().inventorySlot(mouseX, mouseY);
        if (slot >= 0 && player != null && player.inventoryMenu.getCarried().isEmpty()) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) graphics.renderTooltip(font, stack, mouseX, mouseY);
            return;
        }
        if (showInventory
                && player != null
                && !player.inventoryMenu.getCarried().isEmpty()) return;
        Cell target = null;
        for (var widget : cells)
            if (widget instanceof Cell cell && (cell.isMouseOver(mouseX, mouseY) || cell.isFocused())) {
                target = cell;
                break;
            }
        hovered = target;
        if (target == null) return;
        var entry = receiver.mirror().entries().get(target.id);
        if (entry == null) return;
        int width =
                Math.max(1, Math.min(320, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 24));
        int maximum = Math.max(1, (Minecraft.getInstance().getWindow().getGuiScaledHeight() - 24) / 10);
        if (tooltipId != target.id || tooltipAmount != entry.amount() || tooltipWidth != width) {
            if (tooltipId != target.id) tooltipScroll = 0;
            tooltipId = target.id;
            tooltipAmount = entry.amount();
            tooltipWidth = width;
            var details = DomainInventoryTooltip.lines(
                    target.info.name,
                    target.info.opaque ? List.of() : target.info.briefTooltipLines(),
                    target.info.type.equals(io.github.loongin.omniresonance.transfer.ResourceTypes.ITEM.toString()),
                    target.info.icon.isBarVisible(),
                    target.info.bucketUnits,
                    target.info.opaque,
                    entry.amount(),
                    target.info.unit);
            var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
            for (Component detail : details) {
                for (var line : font.split(TerminalText.body(detail), width)) {
                    if (wrapped.size() >= 4096) break;
                    wrapped.add(line);
                }
                if (wrapped.size() >= 4096) break;
            }
            tooltipRows = List.copyOf(wrapped);
        }
        tooltipMaximum = Math.max(0, tooltipRows.size() - maximum);
        tooltipScroll = Math.clamp(tooltipScroll, 0, tooltipMaximum);
        var lines = tooltipRows.subList(tooltipScroll, Math.min(tooltipRows.size(), tooltipScroll + maximum));
        graphics.renderTooltip(
                font,
                lines,
                (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) -> new org.joml.Vector2i(
                        Math.clamp(x + 12, 6, Math.max(6, screenWidth - tooltipWidth - 6)),
                        Math.clamp(y + 12, 6, Math.max(6, screenHeight - tooltipHeight - 6))),
                mouseX,
                mouseY);
    }

    private Metadata describe(DomainLedger.Cursor entry) {
        var cached = metadata.get(entry.sequence());
        if (cached != null) return cached;
        var value = new Metadata(entry);
        metadata.put(entry.sequence(), value);
        return value;
    }

    private Component sortLabel() {
        return text("sort." + search.sort().name().toLowerCase(Locale.ROOT));
    }

    static Component text(String key, Object... args) {
        return Component.translatable("omniresonance.inventory." + key, args);
    }

    private final class Metadata {
        final String name, id, type, mod, unit;
        final ItemStack icon;
        final net.neoforged.neoforge.fluids.FluidStack fluid;
        final @Nullable net.minecraft.resources.ResourceLocation texture;
        final int tint;
        final boolean bucketUnits;
        final boolean opaque;
        final List<String> tags;
        final DataComponentMap components;
        private @Nullable List<String> tooltip;
        private @Nullable List<Component> briefTooltip;
        final @Nullable io.github.loongin.omniresonance.transfer.RegisteredResourceVariant registeredVariant;
        final String sourceName;
        private @Nullable String tooltipSearch;
        private final DomainInventoryQuery.Document document;

        Metadata(DomainLedger.Cursor entry) {
            var minecraft = Minecraft.getInstance();
            String type = entry.key().typeId().toString(), id = type, name = type, unit = "";
            ItemStack icon = new ItemStack(Items.BARRIER);
            var fluidIcon = net.neoforged.neoforge.fluids.FluidStack.EMPTY;
            net.minecraft.resources.ResourceLocation texture = null;
            int tint = 0xFFFFFF;
            boolean opaque = true;
            io.github.loongin.omniresonance.transfer.RegisteredResourceVariant registeredVariant = null;
            DataComponentMap components = DataComponentMap.EMPTY;
            var tags = new ArrayList<String>();
            try {
                if (minecraft != null && minecraft.level != null) {
                    var decoded = adapters.decode(entry.key(), minecraft.level.registryAccess())
                            .orElse(null);
                    if (decoded instanceof ItemVariant item) {
                        icon = item.stack(1);
                        name = icon.getHoverName().getString();
                        id = item.itemId().toString();
                        unit = NodeResourcePolicyView.text("unit.item").getString();
                        var iterator = icon.getTags().iterator();
                        while (iterator.hasNext())
                            tags.add(iterator.next().location().toString());
                        components = icon.getComponents();
                        opaque = false;
                    } else if (decoded instanceof FluidVariant fluid) {
                        var stack = fluid.stack(1);
                        fluidIcon = stack;
                        name = stack.getHoverName().getString();
                        id = fluid.fluidId().toString();
                        unit = "B";
                        var iterator = stack.getTags().iterator();
                        while (iterator.hasNext())
                            tags.add(iterator.next().location().toString());
                        components = stack.getComponents();
                        opaque = false;
                    } else if (decoded
                            instanceof io.github.loongin.omniresonance.transfer.RegisteredResourceVariant registered) {
                        registeredVariant = registered;
                        id = registered.resourceId().toString();
                        name = registered.displayName().getString();
                        texture = registered.texture();
                        tint = registered.tint();
                        unit = "B";
                        for (var tag : registered.tags()) tags.add(tag.toString());
                        icon = ItemStack.EMPTY;
                        opaque = false;
                    } else if (decoded
                            instanceof io.github.loongin.omniresonance.transfer.ScalarResourceVariant scalar) {
                        texture = scalar.texture();
                        tint = scalar.tint();
                        icon = texture == null
                                ? new ItemStack(BuiltInRegistries.ITEM.get(scalar.iconItem()))
                                : ItemStack.EMPTY;
                        name = scalar.displayName().getString();
                        id = type;
                        unit = scalar.unit();
                        opaque = false;
                    } else if (decoded != null && entry.key().typeId().equals(ResourceTypes.ENERGY)) {
                        icon = ItemStack.EMPTY;
                        texture = EnergyDisplay.TEXTURE;
                        tint = EnergyDisplay.TINT;
                        name = NodeResourcePolicyView.text("type.energy").getString();
                        unit = "FE";
                        opaque = false;
                    }
                }
            } catch (RuntimeException | LinkageError invalid) {
                texture = null;
                fluidIcon = net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                icon = new ItemStack(Items.BARRIER);
                name = type;
                id = type;
                unit = "";
                opaque = true;
                components = DataComponentMap.EMPTY;
                tags.clear();
            }
            this.registeredVariant = registeredVariant;
            this.name = name;
            this.id = id;
            this.type = type;
            this.unit = unit;
            this.icon = icon;
            this.fluid = fluidIcon;
            this.texture = texture;
            this.tint = tint;
            this.bucketUnits = !fluidIcon.isEmpty() || registeredVariant != null;
            this.opaque = opaque;
            this.tags = List.copyOf(tags);
            this.components = components;
            String namespace = id.substring(0, id.indexOf(':'));
            sourceName = net.neoforged.fml.ModList.get()
                    .getModContainerById(namespace)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(namespace);
            mod = namespace + " " + sourceName;
            document = new DomainInventoryQuery.Document(
                    name.toLowerCase(Locale.ROOT),
                    mod.toLowerCase(Locale.ROOT),
                    id.toLowerCase(Locale.ROOT),
                    type,
                    tags,
                    this::tooltipText,
                    unit,
                    () -> {
                        var current = receiver.mirror().entries().get(entry.sequence());
                        return current == null ? -1 : current.amount();
                    });
        }

        DomainInventoryQuery.Document document() {
            return document;
        }

        List<Component> briefTooltipLines() {
            if (briefTooltip == null) {
                var normal = new ArrayList<Component>();
                try {
                    if (!opaque && type.equals(ResourceTypes.ITEM.toString())) {
                        var minecraft = Minecraft.getInstance();
                        for (var line : icon.getTooltipLines(
                                Item.TooltipContext.of(minecraft.level),
                                minecraft.player,
                                TooltipFlag.Default.NORMAL)) {
                            if (!line.getString().equals(name)) normal.add(line.copy());
                            if (normal.size() >= 4) break;
                        }
                    } else if (!opaque && registeredVariant != null) {
                        for (var line :
                                registeredVariant.tooltipLines(Item.TooltipContext.of(Minecraft.getInstance().level))) {
                            if (!line.getString().equals(name)) normal.add(line.copy());
                            if (normal.size() >= 4) break;
                        }
                    }
                } catch (RuntimeException | LinkageError failure) {
                    normal.add(text("tooltip_failed"));
                }
                if (!opaque
                        && !type.equals(ResourceTypes.ITEM.toString())
                        && !type.equals(ResourceTypes.ENERGY.toString())) {
                    normal.add(Component.literal(sourceName)
                            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC));
                }
                briefTooltip = List.copyOf(normal);
            }
            return briefTooltip;
        }

        List<String> tooltipLines() {
            if (tooltip == null) {
                var result = new ArrayList<String>();
                int remaining = 65536;
                var minecraft = Minecraft.getInstance();
                try {
                    if (!opaque && type.equals(ResourceTypes.ITEM.toString())) {
                        for (var line : icon.getTooltipLines(
                                Item.TooltipContext.of(minecraft.level),
                                minecraft.player,
                                TooltipFlag.Default.ADVANCED)) {
                            if (remaining == 0 || result.size() >= 1024) break;
                            remaining = appendLine(result, line.getString(), remaining);
                        }
                    }
                    for (var key : components.keySet()) {
                        if (remaining == 0 || result.size() >= 1024) break;
                        remaining = appendLine(
                                result,
                                BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(key) + ": "
                                        + String.valueOf(components.get(key)),
                                remaining);
                    }
                } catch (RuntimeException | LinkageError invalid) {
                    if (remaining > 0) appendLine(result, text("tooltip_failed").getString(), remaining);
                }
                tooltip = List.copyOf(result);
            }
            return tooltip;
        }

        private int appendLine(List<String> lines, String value, int remaining) {
            int length = Math.min(value.length(), Math.min(4096, remaining));
            if (length > 0 && length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))) length--;
            lines.add(value.substring(0, length));
            return remaining - length;
        }

        String tooltipText() {
            if (tooltipSearch == null) tooltipSearch = String.join("\n", tooltipLines());
            return tooltipSearch;
        }
    }

    private final class Cell extends TerminalClickButton {
        final long id;
        final Metadata info;
        long previousAmount = -1;
        Component count = Component.empty();
        int countWidth;

        Cell(int x, int y, long id, Metadata info) {
            super(x, y, CELL, CELL, Component.literal(info.name), ignored -> {
                if (!info.opaque) click(id, -1, 0);
            });
            this.id = id;
            this.info = info;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            var entry = receiver.mirror().entries().get(id);
            if (entry == null) return;
            renderSlot(graphics, getX(), getY(), isHoveredOrFocused());
            if (!info.fluid.isEmpty()) DomainFluidDisplay.render(graphics, info.fluid, getX() + 1, getY() + 1);
            else if (info.texture != null)
                DomainFluidDisplay.render(graphics, info.texture, info.tint, getX() + 1, getY() + 1);
            else graphics.renderItem(info.icon, getX() + 1, getY() + 1);
            if (entry.amount() != previousAmount) {
                previousAmount = entry.amount();
                count = slotCount(previousAmount, info.bucketUnits);
                countWidth = font.width(count);
            }
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 220);
            float scale = 0.5f;
            graphics.pose().translate(getRight() - 1, getBottom() - 1, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.drawString(font, count, -countWidth, -font.lineHeight, TerminalTheme.TEXT, true);
            graphics.pose().popPose();
        }
    }

    private static void renderSlot(GuiGraphics graphics, int x, int y, boolean highlight) {
        graphics.fill(x, y, x + CELL, y + CELL, 0xFF10191F);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, highlight ? 0xFF426772 : 0xFF35434B);
    }

    static Component slotCount(long amount, boolean buckets) {
        return Component.literal(buckets ? DomainFluidDisplay.slotQuantity(amount) : compact(amount))
                .withStyle(style -> style.withFont(net.minecraft.network.chat.Style.DEFAULT_FONT));
    }

    static String compact(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative inventory quantity");
        if (amount < 1000) return Long.toString(amount);
        long unit = 1000;
        String[] suffix = {"K", "M", "G", "T", "P", "E"};
        int index = 0;
        while (index < suffix.length - 1 && amount / unit >= 1000) {
            unit *= 1000;
            index++;
        }
        long whole = amount / unit, tenth = (amount % unit) / (unit / 10);
        return Long.toString(whole) + (whole < 10 && tenth != 0 ? "." + tenth : "") + suffix[index];
    }

    @Override
    public void close() {
        receiver.close();
        tagPopup = null;
        localNotice = null;
        quickMove.clear();
        tooltipRows = List.of();
        clicks.clear();
        storageSender = null;
        writable = showInventory = false;
        pendingOperation = 0;
        metadata.clear();
        search.close();
        cells.clear();
        hovered = null;
        field = null;
        sortButton = retryButton = null;
        add = null;
        remove = null;
        font = null;
    }
}
