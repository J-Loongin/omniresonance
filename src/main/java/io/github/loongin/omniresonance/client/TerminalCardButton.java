// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Shared two-line card used by terminal modules and physical-node mode choices. */
final class TerminalCardButton extends TerminalClickButton {
    private final Component mark;
    private final Component description;
    private final net.minecraft.client.gui.components.Tooltip fullTooltip;
    private boolean tooltipVisible;
    private TerminalModuleIcon module;
    private boolean modeCard;

    TerminalCardButton(
            TerminalLayout.Rect bounds, Component mark, Component title, Component description, OnPress onPress) {
        super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), TerminalText.title(title), onPress);
        this.mark = TerminalText.body(mark);
        this.description = TerminalText.body(description);
        fullTooltip = net.minecraft.client.gui.components.Tooltip.create(
                TerminalText.body(title.copy().append("\n").append(description)));
    }

    void module(String module) {
        this.module = TerminalModuleIcon.forModule(module);
        setTooltip(fullTooltip);
    }

    void mode(boolean direct) {
        module = direct ? TerminalModuleIcon.DIRECT : TerminalModuleIcon.DOMAIN;
        modeCard = true;
        setTooltip(fullTooltip);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var style = TerminalTheme.controlStyle(active, isHovered, isFocused(), false, false, false);
        TerminalTheme.renderControl(graphics, new TerminalLayout.Rect(getX(), getY(), getWidth(), getHeight()), style);
        Font font = TerminalText.font(Minecraft.getInstance());
        if (module != null && getHeight() >= 40 && getWidth() >= 24) {
            int ink = active ? TerminalTheme.TEXT : TerminalTheme.DISABLED_TEXT;
            int iconY = getY() + Math.max(4, (getHeight() - (modeCard ? 58 : 36)) / 2);
            module.render(graphics, getX() + (getWidth() - 20) / 2, iconY, ink);
            Component title = TerminalText.title(font, getMessage().getString(), Math.max(0, getWidth() - 12));
            TerminalText.drawCentered(graphics, font, title, getX() + getWidth() / 2, iconY + 26, ink);
            if (modeCard) {
                String detail = TerminalText.ellipsize(font, description.getString(), Math.max(0, getWidth() - 16));
                graphics.fill(getX() + 8, iconY + 39, getRight() - 8, iconY + 40, TerminalTheme.LINE);
                TerminalText.drawCentered(
                        graphics,
                        font,
                        TerminalText.body(Component.literal(detail)),
                        getX() + getWidth() / 2,
                        iconY + 45,
                        TerminalTheme.MUTED);
            }
            return;
        }
        int titleY = getY() + Math.max(5, getHeight() / 3 - 5);
        int textColor = active ? TerminalTheme.TEXT : TerminalTheme.MUTED;
        if (getWidth() >= 120)
            graphics.drawString(
                    font, mark, getX() + 7, titleY, active ? TerminalTheme.ACCENT : TerminalTheme.MUTED, false);
        String title = TerminalText.ellipsize(
                getMessage().getString(),
                Math.max(0, getWidth() - (getWidth() >= 120 ? 32 : 14)),
                value -> font.width(TerminalText.title(Component.literal(value))));
        graphics.drawString(
                font,
                TerminalText.title(Component.literal(title)),
                getX() + (getWidth() >= 120 ? 25 : 7),
                titleY,
                textColor,
                false);
        String shownDescription = TerminalText.ellipsize(font, description.getString(), Math.max(0, getWidth() - 14));
        boolean needsTooltip = needsTooltip(
                getMessage().getString(), title, description.getString(), shownDescription, getHeight() >= 38);
        if (needsTooltip != tooltipVisible) {
            tooltipVisible = needsTooltip;
            setTooltip(needsTooltip ? fullTooltip : null);
        }
        if (getHeight() >= 38) {
            graphics.drawString(
                    font,
                    shownDescription,
                    getX() + 7,
                    Math.min(getY() + getHeight() - 13, titleY + 14),
                    TerminalTheme.MUTED,
                    false);
        }
    }

    static boolean needsTooltip(
            String title, String shownTitle, String description, String shownDescription, boolean descriptionVisible) {
        return !title.equals(shownTitle)
                || !description.isEmpty() && (!descriptionVisible || !description.equals(shownDescription));
    }
}
