// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.chunkloading.ChunkLoadingAllocator;
import io.github.loongin.omniresonance.networking.NodeChunkLoadingInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Read-only text and tooltip. It creates no widget, focus target, click handler, or mutation intent. */
final class NodeChunkIndicator {
    private NodeChunkIndicator() {}

    private static ChunkLoadingAllocator.Status state(@Nullable NodeChunkLoadingInfo info, boolean requested) {
        return info != null
                ? info.status()
                : requested ? ChunkLoadingAllocator.Status.QUEUED : ChunkLoadingAllocator.Status.OFF;
    }

    static Component tooltip(@Nullable NodeChunkLoadingInfo info, boolean requested) {
        var result = Component.translatable(
                "omniresonance.chunk_loading." + state(info, requested).name().toLowerCase(java.util.Locale.ROOT));
        if (info != null)
            result.append("\n")
                    .append(Component.translatable(
                            "omniresonance.nodes.loading_quota",
                            info.ownerUsed(),
                            limit(info.ownerLimit()),
                            info.serverUsed(),
                            limit(info.serverLimit())));
        if (info != null
                && ((info.ownerLimit() >= 0 && info.ownerUsed() > info.ownerLimit())
                        || (info.serverLimit() >= 0 && info.serverUsed() > info.serverLimit())))
            result.append("\n").append(Component.translatable("omniresonance.nodes.loading_over_quota"));
        return result;
    }

    private static Component limit(int value) {
        return value < 0
                ? Component.translatable("omniresonance.nodes.unlimited")
                : Component.literal(Integer.toString(value));
    }

    static void render(
            GuiGraphics graphics,
            Font font,
            TerminalLayout.Rect bounds,
            @Nullable NodeChunkLoadingInfo info,
            boolean requested) {
        var status = state(info, requested);
        var label = Component.translatable("omniresonance.nodes.loading_label");
        var value = Component.translatable(
                requested ? "omniresonance.nodes.loading_on" : "omniresonance.nodes.loading_state.off");
        var full = label.copy().append(": ").append(value);
        int width = Math.max(1, bounds.width() - 4);
        if (font.width(TerminalText.body(full)) <= width)
            graphics.drawString(
                    font, TerminalText.body(full), bounds.x() + 2, bounds.y() + 6, TerminalTheme.TEXT, false);
        else {
            graphics.drawString(
                    font,
                    TerminalText.body(Component.literal(TerminalText.ellipsize(
                            font,
                            Component.translatable("omniresonance.nodes.loading_short_label")
                                    .getString(),
                            width))),
                    bounds.x() + 2,
                    bounds.y(),
                    TerminalTheme.MUTED,
                    false);
            graphics.drawString(
                    font,
                    TerminalText.body(Component.literal(TerminalText.ellipsize(font, value.getString(), width))),
                    bounds.x() + 2,
                    bounds.y() + 10,
                    status == ChunkLoadingAllocator.Status.ACTIVE ? TerminalTheme.ACCENT : TerminalTheme.TEXT,
                    false);
        }
    }
}
