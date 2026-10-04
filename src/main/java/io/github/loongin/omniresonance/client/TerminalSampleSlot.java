// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Non-consuming visual inventory sample slot; clicks send a slot intent and never mutate a local stack. */
final class TerminalSampleSlot extends TerminalClickButton {
    private final Supplier<ItemStack> displayed;
    private @Nullable ResourceLocation emptySprite;

    TerminalSampleSlot(TerminalLayout.Rect bounds, Component label, OnPress press, Supplier<ItemStack> displayed) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.body(label), press);
        this.displayed = displayed;
        setTooltip(Tooltip.create(TerminalText.body(label)));
    }

    void equipmentSlot(int slot) {
        emptySprite = switch (slot) {
            case 39 -> InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
            case 38 -> InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
            case 37 -> InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
            case 36 -> InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
            case 40 -> InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
            default -> null;
        };
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        TerminalTheme.renderSlot(graphics, getX(), getY(), 20, active && isHoveredOrFocused());
        ItemStack stack = displayed.get();
        if (!stack.isEmpty()) graphics.renderItem(stack, getX() + 2, getY() + 2);
        else if (emptySprite != null) {
            var sprite = Minecraft.getInstance()
                    .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(emptySprite);
            graphics.blit(getX() + 2, getY() + 2, 0, 16, 16, sprite);
        }
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
