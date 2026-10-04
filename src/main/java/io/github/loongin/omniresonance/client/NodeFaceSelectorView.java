// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NodeFacePreview;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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

    private NodeFaceSelectorView() {}

    record Layout(TerminalLayout.Rect cards, TerminalLayout.Rect actions, int firstRow, int visibleRows) {
        TerminalLayout.Rect card(int row, int column) {
            boolean panel = visibleRows == 1;
            int half = panel ? cards.width() : Math.max(0, (cards.width() - 12) / 3);
            int pitch = panel ? cards.height() : (cards.height() + 6) / 2;
            return new TerminalLayout.Rect(
                    cards.x() + column * (half + 6),
                    cards.y() + (row - firstRow) * pitch,
                    half,
                    Math.max(0, pitch - (panel ? 0 : 6)));
        }
    }

    static Layout layout(TerminalLayout.Rect body, boolean panel) {
        TerminalLayout.Rect actions = new TerminalLayout.Rect(body.right() - 72, body.y(), 64, 20);
        int available = Math.max(0, body.height() - 40);
        TerminalLayout.Rect cards = new TerminalLayout.Rect(
                body.x() + 8,
                body.y() + 26,
                Math.max(0, body.width() - 16),
                panel ? Math.min(56, available) : available);
        return new Layout(cards, actions, 0, panel ? 1 : 2);
    }

    static void build(
            Layout layout,
            NodeWorkingFacesDraft draft,
            Direction facing,
            List<NodeFacePreview> previews,
            boolean active,
            Consumer<AbstractWidget> add,
            Runnable rebuild) {
        for (int row = layout.firstRow(); row < layout.firstRow() + layout.visibleRows(); row++) {
            for (int column = 0; column < (draft.panel() ? 1 : 3); column++) {
                Direction direction = draft.panel() ? facing : ORDER[row * 3 + column];
                NodeFacePreview preview = previews.stream()
                        .filter(value -> value.direction() == direction)
                        .findFirst()
                        .orElse(new NodeFacePreview(direction, NodeFacePreview.Status.UNLOADED, null));
                ItemStack icon = ItemStack.EMPTY;
                if (preview.blockId() != null && BuiltInRegistries.BLOCK.containsKey(preview.blockId())) {
                    var item = BuiltInRegistries.BLOCK.get(preview.blockId()).asItem();
                    if (item != Items.AIR) icon = new ItemStack(item);
                }
                var heading = text("direction." + direction.getName())
                        .copy()
                        .append(" · ")
                        .append(text(draft.panel() ? "fixed" : draft.selected(direction) ? "selected" : "unselected"));
                var card = new TerminalPreviewCard(
                        layout.card(row, column),
                        heading,
                        name(preview),
                        icon,
                        draft.selected(direction),
                        draft.panel(),
                        text("direction." + direction.getName())
                                .copy()
                                .append(" / ")
                                .append(name(preview)),
                        () -> {
                            draft.toggle(direction);
                            rebuild.run();
                        });
                card.active = active && !draft.panel();
                add.accept(card);
            }
        }
    }

    static Component text(String key, Object... arguments) {
        return Component.translatable("omniresonance.working_faces." + key, arguments);
    }

    static void renderPanelNotice(
            GuiGraphics graphics,
            net.minecraft.client.gui.Font font,
            TerminalLayout.Rect body,
            Direction facing,
            List<NodeFacePreview> previews) {
        var status = NodeFacePreview.Status.UNLOADED;
        for (var preview : previews)
            if (preview.direction() == facing) {
                status = preview.status();
                break;
            }
        if (status == NodeFacePreview.Status.BLOCK) return;
        var cards = layout(body, true).cards();
        graphics.drawWordWrap(
                font,
                TerminalText.body(text(status == NodeFacePreview.Status.AIR ? "attached_empty" : "attached_unloaded")),
                cards.x(),
                cards.bottom() + 8,
                cards.width(),
                TerminalTheme.MUTED);
    }

    static Component name(NodeFacePreview preview) {
        if (preview.status() != NodeFacePreview.Status.BLOCK)
            return text(preview.status() == NodeFacePreview.Status.AIR ? "air" : "unloaded");
        return BuiltInRegistries.BLOCK.containsKey(preview.blockId())
                ? BuiltInRegistries.BLOCK.get(preview.blockId()).getName()
                : Component.literal(preview.blockId().toString());
    }
}
