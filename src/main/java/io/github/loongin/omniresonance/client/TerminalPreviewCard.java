// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Shared readable preview card; fixed targets and selectable targets differ only in content arrangement. */
final class TerminalPreviewCard extends TerminalClickButton {
    private final Component heading;
    private final ItemStack icon;
    private final boolean selected;
    private final boolean fixed;

    TerminalPreviewCard(
            TerminalLayout.Rect bounds,
            Component heading,
            Component label,
            ItemStack icon,
            boolean selected,
            boolean fixed,
            Component tooltip,
            Runnable action) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), label, ignored -> action.run());
        this.heading = heading;
        this.icon = icon.copy();
        this.selected = selected;
        this.fixed = fixed;
        setTooltip(Tooltip.create(TerminalText.body(tooltip)));
    }

    record Content(
            TerminalLayout.Rect heading,
            TerminalLayout.Rect icon,
            TerminalLayout.Rect label,
            @org.jetbrains.annotations.Nullable TerminalLayout.Rect mark) {}

    static Content content(TerminalLayout.Rect bounds, int labelWidth, boolean selected, boolean fixed) {
        var mark = selected && !fixed ? new TerminalLayout.Rect(bounds.right() - 13, bounds.y() + 6, 7, 5) : null;
        var heading = new TerminalLayout.Rect(
                bounds.x() + 6, bounds.y() + 5, Math.max(0, bounds.width() - (mark == null ? 12 : 26)), 10);
        if (fixed) {
            int width = Math.min(Math.max(0, labelWidth), Math.max(0, bounds.width() - 48));
            int left = bounds.x() + (bounds.width() - 32 - width) / 2;
            int top = bounds.y() + Math.max(20, (bounds.height() - 12) / 2);
            return new Content(
                    heading,
                    new TerminalLayout.Rect(left, top, 24, 24),
                    new TerminalLayout.Rect(left + 32, top + 8, width, 10),
                    mark);
        }
        return new Content(
                heading,
                new TerminalLayout.Rect(
                        bounds.x() + (bounds.width() - 24) / 2,
                        bounds.y() + Math.max(18, (bounds.height() - 24) / 2),
                        24,
                        24),
                new TerminalLayout.Rect(bounds.x() + 6, bounds.bottom() - 12, Math.max(0, bounds.width() - 12), 10),
                mark);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var bounds = new TerminalLayout.Rect(getX(), getY(), getWidth(), getHeight());
        var style = TerminalTheme.previewStyle(active, isHovered, isFocused(), selected, fixed);
        TerminalTheme.renderControl(graphics, bounds, style);
        var font = TerminalText.font(Minecraft.getInstance());
        var content = content(bounds, font.width(getMessage()), selected, fixed);
        var title = content.heading();
        TerminalText.drawCentered(
                graphics,
                font,
                TerminalText.body(Component.literal(TerminalText.ellipsize(font, heading.getString(), title.width()))),
                title.x() + title.width() / 2,
                title.y(),
                style.text());
        if (content.mark() != null)
            TerminalTheme.renderSelectionMark(
                    graphics, content.mark().x(), content.mark().y(), style.text());
        var image = content.icon();
        if (icon.isEmpty())
            TerminalText.drawCentered(
                    graphics, font, Component.literal("—"), image.x() + 12, image.y() + 8, TerminalTheme.MUTED);
        else {
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(image.x(), image.y(), 0);
                graphics.pose().scale(1.5f, 1.5f, 1.5f);
                graphics.renderFakeItem(icon, 0, 0);
            } finally {
                graphics.pose().popPose();
            }
        }
        var label = content.label();
        var shown = TerminalText.body(
                Component.literal(TerminalText.ellipsize(font, getMessage().getString(), label.width())));
        if (fixed) graphics.drawString(font, shown, label.x(), label.y(), style.text(), false);
        else TerminalText.drawCentered(graphics, font, shown, label.x() + label.width() / 2, label.y(), style.text());
    }
}
