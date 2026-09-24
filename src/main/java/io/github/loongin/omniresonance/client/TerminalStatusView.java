// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkStatusFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** One read-only view. Layout is cached per snapshot/mode/resize; updates retain scroll and revocation discards data. */
final class TerminalStatusView {
    private record Line(
            TerminalStatusPresentation.Row row,
            int top,
            int height,
            List<FormattedCharSequence> labels,
            List<FormattedCharSequence> values,
            boolean card) {}

    private final UUID session, network;
    private final long generation;
    private final Consumer<String> copy;
    private @Nullable NetworkStatusFrame frame;
    private @Nullable TerminalButton copyButton, detailButton;
    private Font font;
    private TerminalLayout.Rect body;
    private List<Line> lines = List.of();
    private List<Component> countLabels = List.of(), countValues = List.of();
    private int scroll, loadingTicks, contentHeight, labelWidth;
    private boolean detailed;

    TerminalStatusView(UUID session, UUID network, long generation, Consumer<String> copy) {
        this.session = session;
        this.network = network;
        this.generation = generation;
        this.copy = copy;
    }

    void accept(NetworkStatusFrame value) {
        if (!value.session().equals(session)
                || value.generation() != generation
                || frame != null && value.sequence() <= frame.sequence()
                || value.snapshot() != null && !value.snapshot().network().equals(network)) return;
        frame = value;
        if (value.snapshot() == null) {
            detailed = false;
            scroll = 0;
        }
        reflow();
        updateButtons();
    }

    void build(Font font, TerminalLayout.Rect body, Consumer<AbstractWidget> add) {
        this.font = font;
        this.body = body;
        var toolbar = TerminalActionLayout.of(new TerminalLayout.Rect(body.x(), body.y(), body.width(), 36));
        var bounds = toolbar.primary();
        copyButton = new TerminalButton(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                text("copy"),
                ignored -> {
                    if (frame != null && frame.snapshot() != null)
                        copy.accept(frame.snapshot().export(frame.version()));
                },
                false);
        add.accept(copyButton);
        var detailBounds = toolbar.secondary();
        detailButton = new TerminalButton(
                detailBounds.x(),
                detailBounds.y(),
                detailBounds.width(),
                detailBounds.height(),
                text("details"),
                ignored -> {
                    if (frame == null || frame.snapshot() == null) return;
                    if (!back()) {
                        detailed = true;
                        scroll = 0;
                        reflow();
                        updateButtons();
                    }
                },
                false);
        add.accept(detailButton);
        updateButtons();
        reflow();
    }

    boolean back() {
        if (!detailed) return false;
        detailed = false;
        scroll = 0;
        reflow();
        updateButtons();
        return true;
    }

    private void updateButtons() {
        boolean ready = frame != null && frame.snapshot() != null;
        if (copyButton != null) copyButton.active = ready;
        if (detailButton != null) {
            detailButton.active = ready;
            detailButton.setMessage(text(detailed ? "back" : "details"));
        }
    }

    private boolean hasOverview() {
        return !detailed && frame != null && frame.snapshot() != null;
    }

    private int visibleHeight() {
        return body == null ? 0 : Math.max(0, body.height() - 44);
    }

    private int maximumScroll() {
        return Math.max(0, contentHeight - visibleHeight());
    }

    private void reflow() {
        if (font == null || body == null) return;
        List<TerminalStatusPresentation.Row> rows;
        if (frame == null)
            rows = TerminalInteractionPolicy.loadingVisible(loadingTicks) ? List.of(message("loading")) : List.of();
        else if (frame.snapshot() == null) rows = List.of(message("unavailable"));
        else
            rows = detailed
                    ? TerminalStatusPresentation.details(frame.snapshot())
                    : TerminalStatusPresentation.overview(frame.snapshot());
        if (hasOverview()) {
            var snapshot = frame.snapshot();
            countLabels = List.of(
                    TerminalText.body(text("nodes")),
                    TerminalText.body(text("tunnels")),
                    TerminalText.body(text("channels")));
            countValues = List.of(
                    TerminalText.title(Component.literal(Integer.toString(snapshot.nodes()))),
                    TerminalText.title(Component.literal(Integer.toString(snapshot.tunnels()))),
                    TerminalText.title(Component.literal(Integer.toString(snapshot.channels()))));
        } else {
            countLabels = List.of();
            countValues = List.of();
        }
        labelWidth = Math.min(104, Math.max(1, (body.width() - 24) / 3));
        int valueWidth = Math.max(1, body.width() - 24 - labelWidth - 10);
        int top = hasOverview() ? 44 : 0;
        var result = new ArrayList<Line>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            boolean card = hasOverview() && index < 2;
            int width =
                    card ? Math.max(1, body.width() - 48) : row.section() ? Math.max(1, body.width() - 24) : labelWidth;
            var labels = font.split(TerminalText.body(row.label()), width);
            var values = font.split(TerminalText.body(row.value()), card ? width : valueWidth);
            int height = card
                    ? 14 + (labels.size() + values.size()) * 12
                    : Math.max(labels.size(), values.size()) * 12 + (row.section() ? 10 : 6);
            result.add(new Line(row, top, height, labels, values, card));
            top += height + (card ? 6 : 0);
        }
        lines = List.copyOf(result);
        contentHeight = top;
        scroll = Math.clamp(scroll, 0, maximumScroll());
    }

    private static TerminalStatusPresentation.Row message(String key) {
        return new TerminalStatusPresentation.Row(
                text(key), Component.empty(), TerminalStatusPresentation.Tone.MUTED, Component.empty(), false);
    }

    void tick() {
        if (frame == null && loadingTicks < TerminalInteractionPolicy.LOADING_DELAY_TICKS) {
            loadingTicks++;
            if (TerminalInteractionPolicy.loadingVisible(loadingTicks)) reflow();
        }
    }

    boolean wheel(double x, double y, double delta) {
        if (body == null || x < body.x() || x >= body.right() || y < body.y() + 36 || y >= body.bottom() || delta == 0)
            return false;
        scroll = Math.clamp(scroll + (delta < 0 ? 12 : -12), 0, maximumScroll());
        return true;
    }

    void render(GuiGraphics graphics) {
        if (body == null) return;
        TerminalTheme.renderPanel(graphics, body);
        graphics.drawString(
                font,
                TerminalText.body(text(detailed ? "diagnosis" : "overview")),
                body.x() + 8,
                body.y() + 13,
                TerminalTheme.MUTED,
                false);
        graphics.enableScissor(body.x() + 4, body.y() + 36, body.right() - 10, body.bottom() - 6);
        int start = body.y() + 36 - scroll;
        if (hasOverview()) renderCounts(graphics, start);
        for (var line : lines) {
            int y = start + line.top();
            if (y + line.height() <= body.y() + 36 || y >= body.bottom() - 6) continue;
            if (line.card()) {
                renderStatusCard(graphics, line, y);
                continue;
            }
            if (line.row().section()) {
                graphics.fill(body.x() + 8, y, body.right() - 16, y + 1, TerminalTheme.LINE);
                y += 6;
            }
            drawLines(graphics, line.labels(), body.x() + 8, y, TerminalTheme.MUTED);
            drawLines(
                    graphics,
                    line.values(),
                    body.x() + 8 + labelWidth + 10,
                    y,
                    color(line.row().tone()));
        }
        graphics.disableScissor();
        TerminalTheme.renderScrollbar(
                graphics,
                body.right() - 7,
                body.y() + 36,
                Math.max(0, body.height() - 42),
                maximumScroll() + 1,
                1,
                scroll);
    }

    private void renderStatusCard(GuiGraphics graphics, Line line, int y) {
        int x = body.x() + 8, width = body.width() - 24;
        TerminalTheme.fillRounded(graphics, x, y, width, line.height(), 4, 0x70224956);
        int accent = line.row().tone() == TerminalStatusPresentation.Tone.MUTED
                ? TerminalTheme.LINE
                : color(line.row().tone());
        graphics.fill(x, y + 7, x + 2, y + line.height() - 7, accent);
        drawLines(graphics, line.labels(), x + 12, y + 7, TerminalTheme.MUTED);
        drawLines(
                graphics,
                line.values(),
                x + 12,
                y + 7 + line.labels().size() * 12,
                color(line.row().tone()));
        if (line == lines.getFirst() && line.labels().size() == 1) {
            var range = TerminalText.body(text("recent_range"));
            int rangeWidth = font.width(range);
            int labelSize = font.width(TerminalText.body(line.row().label()));
            if (labelSize + rangeWidth + 40 <= width)
                graphics.drawString(font, range, x + width - 12 - rangeWidth, y + 7, TerminalTheme.MUTED, false);
        }
    }

    private void renderCounts(GuiGraphics graphics, int y) {
        int available = body.width() - 24;
        int column = (available - 12) / 3;
        for (int index = 0; index < 3; index++) {
            int offset = index * (column + 6);
            int x = body.x() + 8 + offset;
            TerminalTheme.fillRounded(graphics, x, y, index == 2 ? available - offset : column, 36, 4, 0x401F414E);
            graphics.drawString(font, countLabels.get(index), x + 10, y + 4, TerminalTheme.MUTED, false);
            graphics.drawString(font, countValues.get(index), x + 10, y + 19, TerminalTheme.ACCENT, false);
        }
    }

    private void drawLines(GuiGraphics graphics, List<FormattedCharSequence> values, int x, int y, int color) {
        for (var line : values) {
            graphics.drawString(font, line, x, y, color, false);
            y += 12;
        }
    }

    void renderTooltip(GuiGraphics graphics, int x, int y) {
        if (body == null || frame == null || frame.snapshot() == null || x < body.x() + 4 || x >= body.right() - 10)
            return;
        if (y >= body.y() + 8 && y < body.y() + 30 && x < body.x() + 100) {
            TerminalText.renderTooltip(
                    graphics, font, text("sampled_at", frame.snapshot().gameTick()), x, y);
            return;
        }
        if (y < body.y() + 36 || y >= body.bottom() - 6) return;
        int local = y - body.y() - 36 + scroll;
        if (hasOverview() && local < 36) {
            TerminalText.renderTooltip(graphics, font, text("counts_help"), x, y);
            return;
        }
        for (var line : lines)
            if (local >= line.top() && local < line.top() + line.height()) {
                if (!line.row().help().getString().isEmpty())
                    TerminalText.renderTooltip(graphics, font, line.row().help(), x, y);
                return;
            }
    }

    private static int color(TerminalStatusPresentation.Tone tone) {
        return switch (tone) {
            case TEXT -> TerminalTheme.TEXT;
            case MUTED -> TerminalTheme.MUTED;
            case ACCENT -> TerminalTheme.ACCENT;
            case WARNING -> 0xFFE2B85B;
            case ERROR -> TerminalTheme.ERROR;
        };
    }

    private static Component text(String key, Object... args) {
        return TerminalStatusPresentation.text(key, args);
    }
}
