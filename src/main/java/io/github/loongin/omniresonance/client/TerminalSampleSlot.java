// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Non-consuming visual inventory sample slot; clicks send a slot intent and never mutate a local stack. */
final class TerminalSampleSlot extends TerminalClickButton {
    private final Supplier<ItemStack> displayed;

    TerminalSampleSlot(TerminalLayout.Rect bounds, Component label, OnPress press, Supplier<ItemStack> displayed) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.body(label), press);
        this.displayed = displayed;
        setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        TerminalTheme.fillRounded(
                graphics,
                getX(),
                getY(),
                20,
                20,
                3,
                active && isHoveredOrFocused() ? TerminalTheme.FRAME_LINE : TerminalTheme.LINE);
        TerminalTheme.fillRounded(graphics, getX() + 1, getY() + 1, 18, 18, 2, TerminalTheme.RAISED);
        ItemStack stack = displayed.get();
        if (!stack.isEmpty()) graphics.renderItem(stack, getX() + 2, getY() + 2);
        var font = TerminalText.font(Minecraft.getInstance());
        graphics.drawString(
                font,
                TerminalText.body(Component.literal(
                        TerminalText.ellipsize(font, getMessage().getString(), Math.max(0, getWidth() - 24)))),
                getX() + 24,
                getY() + 5,
                active ? TerminalTheme.TEXT : TerminalTheme.MUTED,
                false);
    }
}
