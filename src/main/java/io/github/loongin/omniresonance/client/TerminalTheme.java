// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/** Fixed code-rendered colors and geometry shared by terminal screens and controls. */
final class TerminalTheme {
    static final int WORLD_DIM = 0x94000000;
    static final int MODAL_DIM = 0x88000000;
    static final int WINDOW_TOP = 0xF50B1424;
    static final int WINDOW_BOTTOM = WINDOW_TOP;
    static final int TITLE = 0xFF101B30;
    static final int PANEL = 0xFF0D1728;
    static final int RAISED = 0xFF111C2E;
    static final int RAISED_HOVERED = 0xFF12233B;
    static final int RAISED_DISABLED = 0xFF111722;
    static final int ROW = RAISED;
    static final int ACCENT_SOFT = 0xFF142C49;
    static final int ACCENT = 0xFF6C9FD7;
    static final int LINE = 0xA6687890;
    static final int DETAIL_LINE = 0x50687890;
    static final int FRAME_LINE = 0xC08993A6;
    static final int HOVER_LINE = 0xE08BA7C8;
    static final int TEXT = 0xFFE5EAF2;
    static final int MUTED = 0xFFA9B3C4;
    static final int DISABLED_TEXT = 0xFF7D8BA3;
    static final int ERROR = 0xFFE78D9B;
    static final int INPUT = 0xFF0B1321;
    static final int DANGER = 0xFF3A1D28;
    static final int DANGER_LINE = 0xFFC76B7A;
    static final int FLOW_RED = 0xD94D63;
    static final int OUTER_RADIUS = 6;
    static final int PANEL_RADIUS = 4;
    static final int BUTTON_RADIUS = 3;
    static final int SLOT_RADIUS = 2;
    private static final ResourceLocation DECORATION =
            ResourceLocation.fromNamespaceAndPath("omniresonance", "textures/gui/star_fissure/nebula_edges.png");

    private TerminalTheme() {}

    record ControlStyle(int surface, int border, int text) {}

    interface RoundedBatch extends TerminalIconButton.PixelFill {
        void begin();

        void end();
    }

    private static final ControlStyle NORMAL_STYLE = new ControlStyle(RAISED, LINE, TEXT);
    private static final ControlStyle HOVER_STYLE = new ControlStyle(RAISED_HOVERED, HOVER_LINE, TEXT);
    private static final ControlStyle FOCUS_STYLE = new ControlStyle(RAISED, ACCENT, TEXT);
    private static final ControlStyle SELECTED_STYLE = new ControlStyle(ACCENT_SOFT, ACCENT, TEXT);
    private static final ControlStyle PRIMARY_STYLE = new ControlStyle(ACCENT_SOFT, HOVER_LINE, TEXT);
    private static final ControlStyle DISABLED_STYLE = new ControlStyle(RAISED_DISABLED, LINE, DISABLED_TEXT);
    private static final ControlStyle DANGER_STYLE = new ControlStyle(DANGER, DANGER_LINE, TEXT);
    private static final ControlStyle DANGER_FOCUS_STYLE = new ControlStyle(DANGER, ACCENT, TEXT);
    private static final ControlStyle PREVIEW_STYLE = new ControlStyle(INPUT, LINE, TEXT);
    private static final ControlStyle FOCUS_PREVIEW_STYLE = new ControlStyle(INPUT, ACCENT, TEXT);
    private static final ControlStyle SELECTED_PREVIEW_STYLE = new ControlStyle(ACCENT_SOFT, ACCENT, TEXT);

    static ControlStyle previewStyle(
            boolean active, boolean hovered, boolean focused, boolean selected, boolean fixed) {
        if (fixed) return FOCUS_PREVIEW_STYLE;
        if (!active) return DISABLED_STYLE;
        if (selected) return SELECTED_PREVIEW_STYLE;
        if (focused) return FOCUS_PREVIEW_STYLE;
        return hovered ? HOVER_STYLE : PREVIEW_STYLE;
    }

    static void renderControl(GuiGraphics graphics, TerminalLayout.Rect bounds, ControlStyle style) {
        renderSurface(graphics, bounds, BUTTON_RADIUS, style.surface(), style.border());
    }

    static void renderSurface(GuiGraphics graphics, TerminalLayout.Rect bounds, int radius, int surface, int border) {
        fillRounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius, border);
        fillRounded(
                graphics,
                bounds.x() + 1,
                bounds.y() + 1,
                Math.max(0, bounds.width() - 2),
                Math.max(0, bounds.height() - 2),
                Math.max(0, radius - 1),
                surface);
    }

    static ControlStyle controlStyle(
            boolean active, boolean hovered, boolean focused, boolean selected, boolean primary, boolean danger) {
        if (!active) return DISABLED_STYLE;
        if (danger) return focused ? DANGER_FOCUS_STYLE : DANGER_STYLE;
        if (selected) return SELECTED_STYLE;
        if (primary) return focused ? SELECTED_STYLE : PRIMARY_STYLE;
        if (focused) return FOCUS_STYLE;
        return hovered ? HOVER_STYLE : NORMAL_STYLE;
    }

    static void renderSelectionMark(GuiGraphics graphics, int x, int y, int color) {
        TerminalGlyph.CHECK.render(graphics, x, y, color);
    }

    static void renderWindow(GuiGraphics graphics, TerminalLayout layout) {
        TerminalLayout.Rect window = layout.window();
        fillChamfered(
                graphics,
                window.x() - 1,
                window.y() - 1,
                window.width() + 2,
                window.height() + 2,
                OUTER_RADIUS + 1,
                FRAME_LINE);
        fillChamfered(graphics, window.x(), window.y(), window.width(), window.height(), OUTER_RADIUS, WINDOW_BOTTOM);
        TerminalLayout.Rect title = layout.titleBar();
        graphics.fill(title.x() + OUTER_RADIUS, title.y() + 1, title.right() - OUTER_RADIUS, title.bottom(), TITLE);
        graphics.fill(title.x() + 8, title.bottom() - 1, title.right() - 8, title.bottom(), LINE);
        renderDecoration(graphics, window);
        renderTitleMark(graphics, window.x() + 11, title.y() + title.height() / 2);
        renderFlow(graphics, window);
    }

    static void renderDialogPanel(GuiGraphics graphics, TerminalLayout.Rect bounds) {
        fillChamfered(graphics, bounds.x() - 1, bounds.y() - 1, bounds.width() + 2, bounds.height() + 2, 5, FRAME_LINE);
        fillChamfered(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 4, PANEL);
        graphics.fill(bounds.x() + 10, bounds.y() + 25, bounds.right() - 10, bounds.y() + 26, LINE);
    }

    private static void renderDecoration(GuiGraphics graphics, TerminalLayout.Rect rect) {
        int edge = Math.min(TerminalDecoration.EDGE, Math.min(rect.width(), rect.height()) / 2);
        if (edge < 1) return;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                if (row == 1 && column == 1) continue;
                int x = column == 0 ? rect.x() : column == 2 ? rect.right() - edge : rect.x() + edge;
                int y = row == 0 ? rect.y() : row == 2 ? rect.bottom() - edge : rect.y() + edge;
                int width = column == 1 ? rect.width() - edge * 2 : edge;
                int height = row == 1 ? rect.height() - edge * 2 : edge;
                var source = TerminalDecoration.source(row, column);
                graphics.blit(
                        DECORATION,
                        x,
                        y,
                        width,
                        height,
                        source.x(),
                        source.y(),
                        source.width(),
                        source.height(),
                        TerminalDecoration.WIDTH,
                        TerminalDecoration.HEIGHT);
            }
        }
        RenderSystem.disableBlend();
    }

    static void renderTitleMark(GuiGraphics graphics, int x, int y) {
        TerminalGlyph.TITLE.render(graphics, x - 5, y - 5, 0xFFFFFFFF);
    }

    static void renderSlot(GuiGraphics graphics, int x, int y, int size, boolean hovered) {
        renderSurface(
                graphics,
                new TerminalLayout.Rect(x, y, size, size),
                SLOT_RADIUS,
                hovered ? RAISED_HOVERED : INPUT,
                hovered ? HOVER_LINE : LINE);
    }

    private static void renderFlow(GuiGraphics graphics, TerminalLayout.Rect rect) {
        double perimeter = TerminalWindowOutline.length(rect, OUTER_RADIUS);
        double head = (Util.getMillis() % 10_000L) / 10_000.0 * perimeter;
        // A faint red fringe improves recognition of the horizontal stroke without widening its one-pixel core.
        // Only the open main window owns this drawing; widgets, dialogs and ordinary world nodes stay static.
        for (int step = 0; step < 48; step++) {
            double alpha = step < 12 ? 0.85 : 0.4 * Math.pow(1.0 - (step - 12) / 36.0, 2);
            var point = TerminalWindowOutline.point(rect, OUTER_RADIUS, head - step);
            int x = (int) point.x(), y = (int) point.y();
            int tint = ((int) (alpha * 0.16 * 255) << 24) | FLOW_RED;
            if (y == rect.y() - 1 || y == rect.bottom()) {
                graphics.fill(x, y - 1, x + 1, y, tint);
                graphics.fill(x, y + 1, x + 1, y + 2, tint);
            } else if (x == rect.x() - 1 || x == rect.right()) {
                graphics.fill(x - 1, y, x, y + 1, tint);
                graphics.fill(x + 1, y, x + 2, y + 1, tint);
            }
        }
        for (int step = 0; step < 48; step++) {
            double alpha = step < 12 ? 0.85 : 0.4 * Math.pow(1.0 - (step - 12) / 36.0, 2);
            var point = TerminalWindowOutline.point(rect, OUTER_RADIUS, head - step);
            int x = (int) Math.round(point.x()), y = (int) Math.round(point.y());
            graphics.fill(x, y, x + 1, y + 1, ((int) (alpha * 255) << 24) | FLOW_RED);
        }
    }

    static void fillChamfered(GuiGraphics graphics, int x, int y, int width, int height, int cut, int color) {
        if (width <= 0 || height <= 0) return;
        int c = Math.max(0, Math.min(cut, Math.min(width, height) / 2));
        graphics.fill(x, y + c, x + width, y + height - c, color);
        for (int row = 0; row < c; row++) {
            int inset = c - row;
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
            graphics.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
        }
    }

    static void renderPanel(GuiGraphics graphics, TerminalLayout.Rect bounds) {
        fillRounded(
                graphics, bounds.x() - 1, bounds.y() - 1, bounds.width() + 2, bounds.height() + 2, PANEL_RADIUS, LINE);
        fillRounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), PANEL_RADIUS, PANEL);
    }

    static void renderPaneDivider(GuiGraphics graphics, TerminalLayout.Rect left, TerminalLayout.Rect right) {
        int x = (left.right() + right.x()) / 2;
        int top = Math.max(left.y(), right.y()), bottom = Math.min(left.bottom(), right.bottom());
        if (bottom > top) graphics.fill(x, top, x + 1, bottom, LINE);
    }

    static void renderScrollbar(
            GuiGraphics graphics, int x, int y, int height, int totalRows, int visibleRows, int firstRow) {
        if (height <= 0 || totalRows <= visibleRows) {
            return;
        }
        graphics.fill(x, y, x + TerminalLayout.SCROLLBAR_WIDTH, y + height, 0x7010192B);
        int thumbHeight = Math.max(10, height * visibleRows / totalRows);
        int range = height - thumbHeight;
        int maximumFirst = Math.max(1, totalRows - visibleRows);
        int thumbY = y + range * Mth.clamp(firstRow, 0, maximumFirst) / maximumFirst;
        fillRounded(graphics, x + 1, thumbY, TerminalLayout.SCROLLBAR_WIDTH - 2, thumbHeight, 2, ACCENT_SOFT);
    }

    static void fillRounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        fillRounded(x * 4, y * 4, width * 4, height * 4, radius * 4, color, new GuiRoundedBatch(graphics));
    }

    /** One short-lived batch per filled layer; no graphics/world references survive the call. */
    private static final class GuiRoundedBatch implements RoundedBatch {
        private final GuiGraphics graphics;
        private VertexConsumer vertices;
        private Matrix4f pose;

        private GuiRoundedBatch(GuiGraphics graphics) {
            this.graphics = graphics;
        }

        @Override
        public void begin() {
            // Preserve ordering with earlier text, items, clipping and foreground layers.
            graphics.flush();
            graphics.pose().pushPose();
            try {
                graphics.pose().scale(0.25f, 0.25f, 1);
                pose = graphics.pose().last().pose();
                vertices = graphics.bufferSource().getBuffer(RenderType.gui());
            } catch (RuntimeException | Error failure) {
                graphics.pose().popPose();
                throw failure;
            }
        }

        @Override
        public void draw(int left, int top, int right, int bottom, int color) {
            // Match GuiGraphics.fill winding; append all spans without its per-span flush.
            vertices.addVertex(pose, right, bottom, 0).setColor(color);
            vertices.addVertex(pose, right, top, 0).setColor(color);
            vertices.addVertex(pose, left, top, 0).setColor(color);
            vertices.addVertex(pose, left, bottom, 0).setColor(color);
        }

        @Override
        public void end() {
            try {
                graphics.flush();
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    static void fillRounded(
            int x, int y, int width, int height, int radius, int color, TerminalIconButton.PixelFill fill) {
        if (width <= 0 || height <= 0) {
            return;
        }
        RoundedBatch batch = fill instanceof RoundedBatch value ? value : null;
        if (batch != null) batch.begin();
        try {
            int corner = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
            if (corner == 0) {
                fill.draw(x, y, x + width, y + height, color);
                return;
            }
            fill.draw(x, y + corner, x + width, y + height - corner, color);
            int square = corner * corner;
            for (int row = 0; row < corner; row++) {
                int dy = corner - row - 1;
                int inset = corner - (int) Math.floor(Math.sqrt(Math.max(0, square - dy * dy)));
                fill.draw(x + inset, y + row, x + width - inset, y + row + 1, color);
                fill.draw(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
            }
        } finally {
            if (batch != null) batch.end();
        }
    }
}
