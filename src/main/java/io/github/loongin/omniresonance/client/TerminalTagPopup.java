// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Resource-anchored single-choice menu. Owns one immutable tag snapshot; scrolling never mutates inventory. */
final class TerminalTagPopup {
    private static final int ROW = 12, PAD = 2, TEXT_PAD = 4;
    private static final float TEXT_SCALE = 0.8f;
    private final List<String> tags;
    private final String prefix;
    private final TerminalLayout.Rect bounds;
    private final int rows;
    private int offset;

    TerminalTagPopup(
            List<String> tags,
            TerminalLayout.Rect anchor,
            int screenWidth,
            int screenHeight,
            ToIntFunction<String> measure) {
        this("", tags, anchor, screenWidth, screenHeight, measure);
    }

    TerminalTagPopup(
            String type,
            List<String> tags,
            TerminalLayout.Rect anchor,
            int screenWidth,
            int screenHeight,
            ToIntFunction<String> measure) {
        prefix = switch (type) {
            case "minecraft:item" -> "item:";
            case "minecraft:fluid" -> "fluid:";
            case "mekanism:chemical" -> "chemical:";
            default -> "";
        };
        this.tags = tags.stream()
                .distinct()
                .sorted(Comparator.comparingInt(TerminalTagPopup::rank).thenComparing(s -> s))
                .toList();
        rows = Math.min(5, Math.min(this.tags.size(), Math.max(1, (screenHeight - 16) / ROW)));
        int wanted = TEXT_PAD * 2 + scrollbarWidth();
        for (String tag : this.tags)
            wanted = Math.max(
                    wanted, (int) Math.ceil(measure.applyAsInt(display(tag)) * 0.8) + TEXT_PAD * 2 + scrollbarWidth());
        int width = Math.max(1, Math.min(wanted, Math.min(240, screenWidth - 8))), height = rows * ROW + PAD * 2;
        int x = anchor.right() + 2;
        if (x + width > screenWidth - 4) x = anchor.x() - width - 2;
        x = Math.clamp(x, 4, Math.max(4, screenWidth - width - 4));
        int y = Math.clamp(anchor.y(), 4, Math.max(4, screenHeight - height - 4));
        bounds = new TerminalLayout.Rect(x, y, width, height);
    }

    String display(String tag) {
        return prefix + "#" + tag;
    }

    private int scrollbarWidth() {
        return tags.size() > rows ? 6 : 0;
    }

    private static int rank(String tag) {
        return tag.startsWith("c:") ? 0 : tag.startsWith("minecraft:") ? 1 : 2;
    }

    TerminalLayout.Rect bounds() {
        return bounds;
    }

    int rows() {
        return rows;
    }

    boolean contains(double x, double y) {
        return x >= bounds.x() && x < bounds.right() && y >= bounds.y() && y < bounds.bottom();
    }

    @Nullable
    String tagAt(double x, double y) {
        if (x < bounds.x() + 2
                || x >= bounds.right() - 2 - scrollbarWidth()
                || y < bounds.y() + PAD
                || y >= bounds.y() + PAD + rows * ROW) return null;
        int index = offset + (int) (y - bounds.y() - PAD) / ROW;
        return index < tags.size() ? tags.get(index) : null;
    }

    record Click(boolean dismiss, @Nullable String tag) {}

    Click click(double x, double y, int button) {
        if (!contains(x, y)) return new Click(true, null);
        String tag = button == 0 ? tagAt(x, y) : null;
        return new Click(tag != null, tag);
    }

    void scroll(double amount) {
        offset = Math.clamp(offset + (amount > 0 ? -1 : amount < 0 ? 1 : 0), 0, Math.max(0, tags.size() - rows));
    }

    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 600);
        try {
            graphics.fill(bounds.x() + 2, bounds.y() + 2, bounds.right() + 2, bounds.bottom() + 2, 0x50000000);
            graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), 0xFF34454D);
            graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1, 0xFF1B252C);
            String hovered = tagAt(mouseX, mouseY);
            for (int row = 0; row < rows; row++) {
                String tag = tags.get(offset + row);
                int y = bounds.y() + PAD + row * ROW;
                if (tag.equals(hovered))
                    graphics.fill(bounds.x() + 2, y, bounds.right() - 2 - scrollbarWidth(), y + ROW, 0xFF2B454F);
                String label = TerminalText.ellipsize(
                        font, display(tag), (int) ((bounds.width() - TEXT_PAD * 2 - scrollbarWidth()) / TEXT_SCALE));
                int separator = label.lastIndexOf(':') + 1;
                Component styled = Component.literal(label.substring(0, separator))
                        .withStyle(style -> style.withColor(0x899BA3))
                        .append(Component.literal(label.substring(separator))
                                .withStyle(style -> style.withColor(0xDCE6E9)));
                graphics.pose().pushPose();
                graphics.pose()
                        .translate(
                                bounds.x() + TEXT_PAD,
                                y + Math.max(0, (ROW - (int) Math.ceil(font.lineHeight * TEXT_SCALE)) / 2),
                                0);
                graphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1);
                graphics.drawString(font, TerminalText.body(styled), 0, 0, TerminalTheme.TEXT, false);
                graphics.pose().popPose();
            }
            if (tags.size() > rows)
                TerminalTheme.renderScrollbar(
                        graphics, bounds.right() - 7, bounds.y() + PAD, rows * ROW, tags.size(), rows, offset);
            graphics.flush();
        } finally {
            graphics.pose().popPose();
        }
    }
}
