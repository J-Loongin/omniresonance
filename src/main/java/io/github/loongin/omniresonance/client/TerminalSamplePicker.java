// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Client-thread picker. Previews belong to this editor and are cleared on close; all sampling remains server verified. */
final class TerminalSamplePicker {
    static final int MAX_TANKS = 128;

    record Tank(int index, FluidStack fluid) {}

    private boolean open;
    private List<Tank> tanks = List.of();
    private int inventorySlot;
    private int scroll;
    private int maximumScroll;
    private String failure = "";
    private TerminalLayout.Rect bounds = new TerminalLayout.Rect(0, 0, 0, 0);

    boolean isOpen() {
        return open;
    }

    void open() {
        clear();
        open = true;
    }

    boolean close() {
        if (!open) return false;
        clear();
        return true;
    }

    boolean back() {
        if (!open) return false;
        if (!tanks.isEmpty()) {
            tanks = List.of();
            scroll = 0;
        } else close();
        return true;
    }

    private void clear() {
        open = false;
        tanks = List.of();
        inventorySlot = 0;
        scroll = 0;
        maximumScroll = 0;
        failure = "";
    }

    static List<Tank> preview(IFluidHandler handler) {
        int count = handler.getTanks();
        if (count < 0 || count > MAX_TANKS) throw new IllegalArgumentException("Invalid preview tank count");
        var result = new ArrayList<Tank>(count);
        for (int index = 0; index < count; index++) {
            FluidStack fluid = handler.getFluidInTank(index);
            if (!fluid.isEmpty()) result.add(new Tank(index, fluid.copy()));
        }
        return List.copyOf(result);
    }

    void select(int slot, ItemStack stack, ResourceLocation type, Consumer<TerminalFilterView.Action> actions) {
        if (!open || stack.isEmpty()) return;
        inventorySlot = slot;
        failure = "";
        if (type.equals(ResourceTypes.ITEM)) {
            close();
            actions.accept(new TerminalFilterView.Action.Sample(type, slot, 0));
            return;
        }
        try {
            // Only inspect an isolated client copy. Never drain, fill, extract or retain the live inventory stack.
            var handler = stack.copy().getCapability(Capabilities.FluidHandler.ITEM);
            offerTanks(handler == null ? List.of() : preview(handler), type, actions);
        } catch (RuntimeException unavailable) {
            tanks = List.of();
            failure = "sample_unavailable";
        }
    }

    void offerTanks(List<Tank> candidates, ResourceLocation type, Consumer<TerminalFilterView.Action> actions) {
        if (!open) return;
        tanks = List.copyOf(candidates);
        if (tanks.isEmpty()) failure = "sample_empty";
        else if (tanks.size() == 1) chooseTank(tanks.getFirst(), type, actions);
    }

    static int inventorySlot(int displayIndex) {
        return displayIndex < 27 ? displayIndex + 9 : displayIndex < 36 ? displayIndex - 27 : displayIndex;
    }

    private void chooseTank(Tank tank, ResourceLocation type, Consumer<TerminalFilterView.Action> actions) {
        int slot = inventorySlot;
        int index = tank.index();
        close();
        actions.accept(new TerminalFilterView.Action.Sample(type, slot, index));
    }

    void build(
            Font font,
            TerminalLayout.Rect body,
            ResourceLocation type,
            boolean pending,
            Consumer<AbstractWidget> add,
            Consumer<TerminalFilterView.Action> actions,
            Runnable rebuild) {
        var footer = TerminalActionLayout.of(body);
        bounds = new TerminalLayout.Rect(
                body.x() + 4,
                body.y() + 24,
                Math.max(0, body.width() - 8),
                Math.max(0, footer.content().height() - 40));
        var back = footer.primary();
        add.accept(new TerminalButton(
                back.x(),
                back.y(),
                back.width(),
                back.height(),
                label("sample_back"),
                ignored -> {
                    back();
                    rebuild.run();
                },
                false));
        if (!tanks.isEmpty()) {
            var rows = RoutingListLayout.calculateRows(bounds, tanks.size(), scroll);
            scroll = rows.scroll();
            maximumScroll = Math.max(0, tanks.size() - rows.visibleRows());
            for (int row = 0; row < rows.visibleRows() && row + scroll < tanks.size(); row++) {
                Tank tank = tanks.get(row + scroll);
                var button = new FluidButton(rows.row(row), tank.fluid(), () -> {
                    chooseTank(tank, type, actions);
                    rebuild.run();
                });
                button.active = !pending;
                add.accept(button);
            }
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int columns = Math.max(1, Math.min(9, Math.max(0, bounds.width() - TerminalLayout.SCROLLBAR_WIDTH) / 22));
        int total = player.getInventory().getContainerSize();
        int visible = Math.max(1, bounds.height() / 22);
        maximumScroll = Math.max(0, (total + columns - 1) / columns - visible);
        scroll = Math.min(scroll, maximumScroll);
        for (int index = scroll * columns; index < total && index < (scroll + visible) * columns; index++) {
            int slot = inventorySlot(index);
            int local = index - scroll * columns;
            var rect = new TerminalLayout.Rect(
                    bounds.x() + local % columns * 22, bounds.y() + local / columns * 22, 20, 20);
            ItemStack stack = player.getInventory().getItem(slot);
            var button = new TerminalSampleSlot(
                    rect,
                    stack.getHoverName(),
                    ignored -> {
                        select(slot, player.getInventory().getItem(slot), type, actions);
                        rebuild.run();
                    },
                    () -> player.getInventory().getItem(slot));
            button.active = !pending && !stack.isEmpty();
            add.accept(button);
        }
    }

    boolean scroll(double x, double y, double delta) {
        if (!open || x < bounds.x() || y < bounds.y() || x >= bounds.right() || y >= bounds.bottom()) return false;
        scroll = Math.max(0, Math.min(maximumScroll, scroll + (delta < 0 ? 1 : -1)));
        return true;
    }

    void render(GuiGraphics graphics, Font font) {
        graphics.drawString(
                font,
                TerminalText.body(label(tanks.isEmpty() ? "sample_choose_item" : "sample_choose_fluid")),
                bounds.x(),
                bounds.y() - 20,
                TerminalTheme.TEXT,
                false);
        TerminalTheme.renderScrollbar(
                graphics,
                bounds.right() - TerminalLayout.SCROLLBAR_WIDTH,
                bounds.y(),
                bounds.height(),
                maximumScroll + 1,
                1,
                scroll);
        if (!failure.isEmpty())
            graphics.drawString(
                    font,
                    TerminalText.body(label(failure)),
                    bounds.x(),
                    bounds.bottom() + 4,
                    TerminalTheme.ERROR,
                    false);
    }

    private static Component label(String key) {
        return Component.translatable("omniresonance.terminal.filters." + key);
    }

    private static final class FluidButton extends TerminalClickButton {
        private final FluidStack fluid;

        FluidButton(TerminalLayout.Rect bounds, FluidStack fluid, Runnable click) {
            super(bounds.x(), bounds.y(), bounds.width(), 20, fluid.getHoverName(), ignored -> click.run());
            this.fluid = fluid;
            setTooltip(Tooltip.create(fluid.getHoverName()));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            TerminalTheme.fillRounded(
                    graphics,
                    getX(),
                    getY(),
                    getWidth(),
                    getHeight(),
                    3,
                    isHoveredOrFocused() ? TerminalTheme.LINE : TerminalTheme.RAISED);
            DomainFluidDisplay.render(graphics, fluid, getX() + 2, getY() + 2);
            var font = TerminalText.font(Minecraft.getInstance());
            String text = fluid.getHoverName().getString() + " · " + DomainFluidDisplay.compact(fluid.getAmount());
            graphics.drawString(
                    font,
                    TerminalText.body(
                            Component.literal(TerminalText.ellipsize(font, text, Math.max(0, getWidth() - 26)))),
                    getX() + 24,
                    getY() + 5,
                    TerminalTheme.TEXT,
                    false);
        }
    }
}
