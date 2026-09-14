// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeFacePreview;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Six simultaneously visible direction cards; native icons contain block identity only. */
final class NodeFaceSelectorView {
    private static final Direction[] ORDER = {
        Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH, Direction.UP, Direction.DOWN
    };
    private static final int ROW_HEIGHT = 44;

    private NodeFaceSelectorView() {}

    record Layout(TerminalLayout.Rect cards, TerminalLayout.Rect actions, int firstRow, int visibleRows) {
        TerminalLayout.Rect card(int row, int column) {
            int half = (cards.width() - 12) / 3;
            return new TerminalLayout.Rect(
                    cards.x() + column * (half + 6), cards.y() + (row - firstRow) * ROW_HEIGHT, half, ROW_HEIGHT - 4);
        }
    }

    static Layout layout(TerminalLayout.Rect body, int scroll, boolean panel) {
        TerminalLayout.Rect actions = new TerminalLayout.Rect(body.right() - 146, body.y() + 4, 134, 20);
        TerminalLayout.Rect cards = new TerminalLayout.Rect(body.x() + 12, body.y() + 32, body.width() - 24, 88);
        return new Layout(cards, actions, 0, panel ? 1 : 2);
    }

    static void build(
            Layout layout,
            NodeWorkingFacesDraft draft,
            Direction facing,
            List<NodeFacePreview> previews,
            boolean active,
            Consumer<AbstractWidget> add,
            Runnable rebuild,
            Runnable back) {
        for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
            for (int column = 0; column < (draft.panel() ? 1 : 3); column++) {
                Direction direction = draft.panel() ? facing : ORDER[row * 3 + column];
                NodeFacePreview preview = previews.stream()
                        .filter(value -> value.direction() == direction)
                        .findFirst()
                        .orElse(new NodeFacePreview(direction, NodeFacePreview.Status.UNLOADED, null));
                Card card = new Card(
                        draft.panel()
                                ? new TerminalLayout.Rect(
                                        layout.cards().x(),
                                        layout.cards().y(),
                                        layout.cards().width(),
                                        ROW_HEIGHT - 4)
                                : layout.card(row, column),
                        preview,
                        draft.selected(direction),
                        draft.panel(),
                        () -> {
                            draft.toggle(direction);
                            rebuild.run();
                        });
                card.active = active && !draft.panel();
                add.accept(card);
            }
        }
        TerminalButton backButton = new TerminalButton(
                layout.actions().x(),
                layout.actions().y(),
                64,
                20,
                Component.translatable("omniresonance.terminal.back"),
                ignored -> back.run(),
                false);
        backButton.active = active;
        add.accept(backButton);
    }

    static Component text(String key) {
        return Component.translatable("omniresonance.working_faces." + key);
    }

    static Component name(NodeFacePreview preview) {
        if (preview.status() != NodeFacePreview.Status.BLOCK)
            return text(preview.status() == NodeFacePreview.Status.AIR ? "air" : "unloaded");
        return BuiltInRegistries.BLOCK.containsKey(preview.blockId())
                ? BuiltInRegistries.BLOCK.get(preview.blockId()).getName()
                : Component.literal(preview.blockId().toString());
    }

    private static final class Card extends TerminalClickButton {
        private final NodeFacePreview preview;
        private final boolean selected;
        private final boolean fixed;

        Card(TerminalLayout.Rect bounds, NodeFacePreview preview, boolean selected, boolean fixed, Runnable toggle) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), name(preview), ignored -> toggle.run());
            this.preview = preview;
            this.selected = selected;
            this.fixed = fixed;
            setTooltip(Tooltip.create(Component.translatable("omniresonance.working_faces.direction."
                            + preview.direction().getName())
                    .append(" / ")
                    .append(name(preview))));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = selected ? TerminalTheme.ACCENT : TerminalTheme.LINE;
            TerminalTheme.fillRounded(graphics, getX(), getY(), getWidth(), getHeight(), 3, border);
            TerminalTheme.fillRounded(
                    graphics, getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2, 2, TerminalTheme.RAISED);
            var font = TerminalText.font(Minecraft.getInstance());
            Component direction = text("direction." + preview.direction().getName())
                    .copy()
                    .append(" · ")
                    .append(text(fixed ? "fixed" : selected ? "selected" : "unselected"));
            graphics.drawString(
                    font,
                    font.plainSubstrByWidth(direction.getString(), Math.max(0, getWidth() - 8)),
                    getX() + 4,
                    getY() + 4,
                    selected ? TerminalTheme.ACCENT : TerminalTheme.MUTED,
                    false);
            ItemStack icon = ItemStack.EMPTY;
            if (preview.blockId() != null && BuiltInRegistries.BLOCK.containsKey(preview.blockId())) {
                var item = BuiltInRegistries.BLOCK.get(preview.blockId()).asItem();
                if (item != Items.AIR) icon = new ItemStack(item);
            }
            if (!icon.isEmpty()) graphics.renderFakeItem(icon, getX() + 4, getY() + 18);
            else {
                graphics.fill(getX() + 5, getY() + 19, getX() + 19, getY() + 33, TerminalTheme.LINE);
                graphics.drawString(font, "—", getX() + 8, getY() + 22, TerminalTheme.MUTED, false);
            }
            graphics.drawString(
                    font,
                    font.plainSubstrByWidth(name(preview).getString(), Math.max(0, getWidth() - 28)),
                    getX() + 24,
                    getY() + 23,
                    TerminalTheme.TEXT,
                    false);
        }
    }
}
