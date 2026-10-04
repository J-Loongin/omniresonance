// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.Objects;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** Client-owned typography and code-point-safe truncation shared by terminal controls. */
final class TerminalText {
    // One renderer for the client lifetime. Resolve current sets on every lookup so resource reloads cannot retain
    // closed FontSets; the renderer does not own glyph providers or textures.
    private static @Nullable Font uiFont;
    // Selected on the render thread from the resolved window scale, including automatic GUI scale.
    // Only an enum value is retained; reloads continue resolving fresh FontSets through FontManager.
    private static UiFontScale activeScale = UiFontScale.X2;

    private TerminalText() {}

    static Font font(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        activeScale = UiFontScale.forGuiScale(minecraft.getWindow().getGuiScale());
        if (uiFont == null) {
            uiFont = new Font(requested -> minecraft.fontManager.getFontSetRaw(resolveFontId(requested)), false);
        }
        return uiFont;
    }

    static ResourceLocation resolveFontId(ResourceLocation requested) {
        return resolveFontId(requested, activeScale);
    }

    static ResourceLocation resolveFontId(ResourceLocation requested, UiFontScale scale) {
        Objects.requireNonNull(requested, "requested");
        return UiFontScale.isTitleFont(requested) ? scale.titleFont() : scale.bodyFont();
    }

    static MutableComponent body(Component value) {
        return body(value, activeScale);
    }

    static MutableComponent body(Component value, UiFontScale scale) {
        return value.copy().withStyle(style -> style.withFont(scale.bodyFont()));
    }

    static MutableComponent title(Component value) {
        return title(value, activeScale);
    }

    static MutableComponent title(Component value, UiFontScale scale) {
        // Minecraft simulates bold by drawing another copy one GUI pixel to the right. WenKai's fine strokes
        // become doubled outlines at this size; distinguish titles through their font size and color instead.
        return value.copy().withStyle(style -> style.withFont(scale.titleFont()).withBold(false));
    }

    static MutableComponent title(Font font, String value, int maximumWidth) {
        return title(Component.literal(
                ellipsize(value, maximumWidth, candidate -> font.width(title(Component.literal(candidate))))));
    }

    static void drawHeaderTitle(GuiGraphics graphics, Font font, String text, TerminalLayout.Rect bounds) {
        graphics.drawString(
                font,
                title(font, text, bounds.width()),
                bounds.x(),
                bounds.y() + (bounds.height() - 8) / 2,
                TerminalTheme.TEXT,
                false);
    }

    static void drawDialogTitle(GuiGraphics graphics, Font font, Component title, TerminalLayout.Rect bounds) {
        int width = Math.max(0, bounds.width() - 36);
        Component shown = title(font, title.getString(), width);
        TerminalTheme.renderTitleMark(graphics, bounds.x() + 12, bounds.y() + 13);
        graphics.drawString(font, shown, bounds.x() + 24, bounds.y() + 9, TerminalTheme.TEXT, false);
        if (font.width(title(title)) <= width) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        int mouseX = (int) (minecraft.mouseHandler.xpos()
                * graphics.guiWidth()
                / minecraft.getWindow().getScreenWidth());
        int mouseY = (int) (minecraft.mouseHandler.ypos()
                * graphics.guiHeight()
                / minecraft.getWindow().getScreenHeight());
        if (mouseX >= bounds.x() + 24
                && mouseX < bounds.right() - 12
                && mouseY >= bounds.y() + 4
                && mouseY < bounds.y() + 25) renderTooltip(graphics, font, title, mouseX, mouseY);
    }

    static void drawControlText(
            Component text, int measuredWidth, TerminalLayout.Rect bounds, int color, TextDraw draw) {
        draw.draw(
                text.getVisualOrderText(),
                bounds.x() + (bounds.width() - measuredWidth) / 2,
                bounds.y() + (bounds.height() - 8) / 2,
                color,
                false);
    }

    static java.util.List<FormattedCharSequence> tooltipLines(Font font, Component text, int screenWidth) {
        return font.split(body(text), Math.max(1, Math.min(220, screenWidth - 24)));
    }

    static void renderTooltip(GuiGraphics graphics, Font font, Component text, int x, int y) {
        graphics.renderTooltip(font, tooltipLines(font, text, graphics.guiWidth()), x, y);
    }

    static String networkLabel(String value, TerminalLayout.Rect bounds, ToIntFunction<String> measure) {
        return ellipsize(value, bounds.width() - 8, measure);
    }

    static void drawNetworkLabel(
            String value, TerminalLayout.Rect bounds, ToIntFunction<String> measure, int color, TextDraw draw) {
        String label = networkLabel(value, bounds, measure);
        drawControlText(body(Component.literal(label)), measure.applyAsInt(label), bounds, color, draw);
    }

    static void drawCentered(GuiGraphics graphics, Font font, Component text, int centerX, int y, int color) {
        drawCentered(graphics, font, text.getVisualOrderText(), centerX, y, color);
    }

    static void drawCentered(
            GuiGraphics graphics, Font font, FormattedCharSequence text, int centerX, int y, int color) {
        drawCentered(
                text,
                font.width(text),
                centerX,
                y,
                color,
                (line, left, top, tint, shadow) -> graphics.drawString(font, line, left, top, tint, shadow));
    }

    static void drawCentered(
            FormattedCharSequence text, int measuredWidth, int centerX, int y, int color, TextDraw draw) {
        draw.draw(text, centerX - measuredWidth / 2, y, color, false);
    }

    @FunctionalInterface
    interface TextDraw {
        void draw(FormattedCharSequence text, int x, int y, int color, boolean shadow);
    }

    static String ellipsize(Font font, String value, int maximumWidth) {
        return ellipsize(value, maximumWidth, font::width);
    }

    record StatusText(String prefix, String status) {}

    static StatusText statusText(String prefix, String status, int maximumWidth, ToIntFunction<String> measure) {
        String shownStatus = ellipsize(status, maximumWidth, measure);
        int prefixWidth = Math.max(0, maximumWidth - measure.applyAsInt(shownStatus) - 3);
        return new StatusText(ellipsize(prefix, prefixWidth, measure), shownStatus);
    }

    static String ellipsize(String value, int maximumWidth, ToIntFunction<String> measure) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(measure, "measure");
        if (maximumWidth <= 0) {
            return "";
        }
        if (measure.applyAsInt(value) <= maximumWidth) {
            return value;
        }
        String suffix = "…";
        if (measure.applyAsInt(suffix) > maximumWidth) {
            return "";
        }
        int low = 0;
        int high = value.codePointCount(0, value.length());
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int end = value.offsetByCodePoints(0, middle);
            if (measure.applyAsInt(value.substring(0, end) + suffix) <= maximumWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return value.substring(0, value.offsetByCodePoints(0, low)) + suffix;
    }
}
