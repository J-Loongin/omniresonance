// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Glass field surface and insets; all text editing, selection and scrolling remain vanilla EditBox behavior. */
class TerminalEditBox extends EditBox {
    private static final int INSET = 4;

    TerminalEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, TerminalText.body(message));
        setBordered(false);
        setTextShadow(false);
        setTextColor(TerminalTheme.TEXT);
        setTextColorUneditable(TerminalTheme.MUTED);
    }

    boolean ownsKey(int keyCode) {
        return isVisible() && canConsumeInput() && keyCode != GLFW.GLFW_KEY_ESCAPE && keyCode != GLFW.GLFW_KEY_TAB;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isVisible()) {
            return false;
        }
        // EditBox inserts ordinary text later through charTyped. Consume its key event too so
        // AbstractContainerScreen cannot interpret the same press as inventory-close or slot input.
        return super.keyPressed(keyCode, scanCode, modifiers) || ownsKey(keyCode);
    }

    @Override
    public int getInnerWidth() {
        return Math.max(0, getWidth() - INSET * 2);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        super.onClick(mouseX - INSET, mouseY);
    }

    @Override
    public int getScreenX(int charNum) {
        return super.getScreenX(charNum) + INSET;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        TerminalTheme.fillRounded(
                graphics,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                TerminalTheme.BUTTON_RADIUS,
                isFocused() ? TerminalTheme.FRAME_LINE : TerminalTheme.LINE);
        TerminalTheme.fillRounded(
                graphics,
                getX() + 1,
                getY() + 1,
                Math.max(0, getWidth() - 2),
                Math.max(0, getHeight() - 2),
                TerminalTheme.BUTTON_RADIUS - 1,
                TerminalTheme.WINDOW_BOTTOM);
        graphics.enableScissor(
                getX() + INSET, getY() + 1, Math.max(getX() + INSET, getRight() - INSET), getBottom() - 1);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(INSET, Math.max(0, (getHeight() - 8) / 2), 0);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        } finally {
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }
}
