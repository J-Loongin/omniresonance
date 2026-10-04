// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.NetworkSettingsSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** Fixed-window network-settings presentation; actions carry no connection or authority state. */
final class TerminalNetworkSettingsView {
    private static final int CONTROL_HEIGHT = 20;
    private static final int EDIT_BOX_MAXIMUM_UTF16_UNITS = 256;

    enum Action {
        BEGIN_RENAME,
        SET_DEFAULT,
        REQUEST_DELETE,
        SAVE_RENAME,
        CANCEL_RENAME,
        CONFIRM_DELETE,
        CANCEL_DELETE
    }

    private TerminalNetworkSettingsView() {}

    record Layout(TerminalLayout.Rect information, TerminalLayout.Rect actions, boolean compact) {}

    record Model(
            boolean renameEnabled, boolean showDefaultAction, boolean setDefaultEnabled, boolean showDeleteAction) {}

    static boolean supports(@Nullable NetworkTerminalState state) {
        return state instanceof NetworkTerminalState.NetworkSettings
                || state instanceof NetworkTerminalState.NetworkRename
                || state instanceof NetworkTerminalState.NetworkDelete;
    }

    static Layout calculate(TerminalLayout terminal) {
        Objects.requireNonNull(terminal, "terminal");
        TerminalLayout.Rect content = terminal.content();
        if (terminal.compact()) {
            int usableHeight = Math.max(0, content.height() - TerminalLayout.GAP);
            int informationHeight = Math.min(98, Math.max(0, usableHeight - 68));
            int actionsHeight = Math.max(0, content.height() - informationHeight - TerminalLayout.GAP);
            return new Layout(
                    new TerminalLayout.Rect(content.x(), content.y(), content.width(), informationHeight),
                    new TerminalLayout.Rect(
                            content.x(),
                            content.y() + informationHeight + TerminalLayout.GAP,
                            content.width(),
                            actionsHeight),
                    true);
        }
        int usableWidth = Math.max(0, content.width() - TerminalLayout.GAP);
        int informationWidth = usableWidth * 35 / 100;
        return new Layout(
                new TerminalLayout.Rect(content.x(), content.y(), informationWidth, content.height()),
                new TerminalLayout.Rect(
                        content.x() + informationWidth + TerminalLayout.GAP,
                        content.y(),
                        Math.max(0, content.width() - informationWidth - TerminalLayout.GAP),
                        content.height()),
                false);
    }

    static Model model(NetworkSettingsSummary settings) {
        Objects.requireNonNull(settings, "settings");
        return new Model(
                true,
                settings.ownerActions(),
                settings.ownerActions() && !settings.defaultNetwork(),
                settings.ownerActions());
    }

    /** Builds controls for one supported immutable state and returns its optional name field. */
    static @Nullable EditBox build(
            Font font,
            TerminalLayout terminal,
            NetworkTerminalState state,
            String renameDraft,
            boolean pending,
            Consumer<AbstractWidget> addWidget,
            Consumer<String> updateDraft,
            Consumer<Action> actions) {
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(renameDraft, "renameDraft");
        Objects.requireNonNull(addWidget, "addWidget");
        Objects.requireNonNull(updateDraft, "updateDraft");
        Objects.requireNonNull(actions, "actions");
        if (state instanceof NetworkTerminalState.NetworkSettings settings) {
            buildSettings(font, terminal, settings, pending, addWidget, actions);
            return null;
        }
        if (state instanceof NetworkTerminalState.NetworkRename) {
            return buildRename(font, terminal, renameDraft, pending, addWidget, updateDraft, actions);
        }
        if (state instanceof NetworkTerminalState.NetworkDelete delete) {
            buildDeletion(font, terminal, delete, pending, addWidget, actions);
            return null;
        }
        throw new IllegalArgumentException("Unsupported network settings state");
    }

    /** Draws the supported state's panels and text; the owning screen draws shared window/error layers. */
    static void render(GuiGraphics graphics, Font font, TerminalLayout terminal, NetworkTerminalState state) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(state, "state");
        if (state instanceof NetworkTerminalState.NetworkSettings settings) {
            renderSettings(graphics, font, terminal, settings);
        } else if (state instanceof NetworkTerminalState.NetworkRename) {
            renderRename(graphics, font, terminal);
        } else if (state instanceof NetworkTerminalState.NetworkDelete delete) {
            renderDeletion(graphics, font, terminal, delete);
        } else {
            throw new IllegalArgumentException("Unsupported network settings state");
        }
    }

    private static void buildSettings(
            Font font,
            TerminalLayout terminal,
            NetworkTerminalState.NetworkSettings state,
            boolean pending,
            Consumer<AbstractWidget> addWidget,
            Consumer<Action> actions) {
        Layout settingsLayout = calculate(terminal);
        Model model = model(state.settings());
        TerminalLayout.Rect information = settingsLayout.information();
        TerminalLayout.Rect actionPanel = settingsLayout.actions();
        TerminalButton rename = button(
                information.x() + 8,
                information.y() + 27,
                information.width() - 16,
                Component.literal(TerminalText.ellipsize(
                        font, state.settings().network().name(), Math.max(0, information.width() - 26))),
                false,
                !pending && model.renameEnabled(),
                Action.BEGIN_RENAME,
                actions);
        rename.setTooltip(Tooltip.create(TerminalText.body(Component.translatable(
                "omniresonance.terminal.settings.rename",
                state.settings().network().name()))));
        addWidget.accept(rename);

        TerminalRowButton owner = new TerminalRowButton(
                information.x() + 8,
                information.y() + 52,
                Math.max(0, information.width() - 16),
                CONTROL_HEIGHT,
                Component.translatable(
                        "omniresonance.terminal.settings.owner_name",
                        state.settings().ownerName()),
                ignored -> {});
        owner.setReadOnly();
        owner.setTooltip(Tooltip.create(TerminalText.body(Component.translatable(
                "omniresonance.terminal.settings.owner_id",
                state.settings().network().ownerId().toString()))));
        addWidget.accept(owner);

        TerminalRowButton networkId = new TerminalRowButton(
                information.x() + 8,
                information.y() + 76,
                Math.max(0, information.width() - 16),
                CONTROL_HEIGHT,
                Component.translatable(
                        "omniresonance.terminal.settings.network_id",
                        state.settings().network().id().toString()),
                ignored -> {});
        networkId.setReadOnly();
        networkId.setTooltip(Tooltip.create(TerminalText.body(
                Component.literal(state.settings().network().id().toString()))));
        addWidget.accept(networkId);

        if (model.showDefaultAction()) {
            int firstActionY = actionPanel.y() + (settingsLayout.compact() ? 20 : 30);
            addWidget.accept(button(
                    actionPanel.x() + 8,
                    firstActionY,
                    actionPanel.width() - 16,
                    Component.translatable(
                            state.settings().defaultNetwork()
                                    ? "omniresonance.terminal.settings.default_set"
                                    : "omniresonance.terminal.settings.set_default"),
                    true,
                    !pending && model.setDefaultEnabled(),
                    Action.SET_DEFAULT,
                    actions));
            addWidget.accept(button(
                    actionPanel.x() + 8,
                    firstActionY + CONTROL_HEIGHT + TerminalLayout.GAP,
                    actionPanel.width() - 16,
                    Component.translatable("omniresonance.terminal.settings.delete"),
                    false,
                    !pending && model.showDeleteAction(),
                    Action.REQUEST_DELETE,
                    actions));
        }
    }

    private static EditBox buildRename(
            Font font,
            TerminalLayout terminal,
            String draft,
            boolean pending,
            Consumer<AbstractWidget> addWidget,
            Consumer<String> updateDraft,
            Consumer<Action> actions) {
        TerminalLayout.Rect content = TerminalDialogLayout.editor(terminal.content());
        int width = Math.min(420, Math.max(0, content.width() - 24));
        int x = content.x() + (content.width() - width) / 2;
        int y = content.y() + 52;
        EditBox field = new TerminalEditBox(
                font,
                x,
                y,
                width,
                CONTROL_HEIGHT,
                Component.translatable("omniresonance.terminal.settings.network_name"));
        field.setMaxLength(EDIT_BOX_MAXIMUM_UTF16_UNITS);
        field.setValue(draft);
        field.setResponder(updateDraft);
        field.active = !pending;
        addWidget.accept(field);
        var footer = TerminalActionLayout.of(content);
        int half = footer.primary().width();
        addWidget.accept(button(
                footer.secondary().x(),
                footer.secondary().y(),
                half,
                Component.translatable("omniresonance.terminal.cancel"),
                false,
                !pending,
                Action.CANCEL_RENAME,
                actions));
        addWidget.accept(button(
                footer.primary().x(),
                footer.primary().y(),
                half,
                Component.translatable("omniresonance.terminal.save"),
                true,
                !pending,
                Action.SAVE_RENAME,
                actions));
        return field;
    }

    private static void buildDeletion(
            Font font,
            TerminalLayout terminal,
            NetworkTerminalState.NetworkDelete state,
            boolean pending,
            Consumer<AbstractWidget> addWidget,
            Consumer<Action> actions) {
        TerminalLayout.Rect modal = TerminalDialogLayout.confirmation(terminal.content(), font, deletionMessage(state));
        var footer = TerminalActionLayout.of(modal);
        int half = footer.primary().width();
        int y = modal.bottom() - CONTROL_HEIGHT - 8;
        addWidget.accept(button(
                footer.secondary().x(),
                y,
                half,
                Component.translatable("omniresonance.terminal.cancel"),
                false,
                !pending,
                Action.CANCEL_DELETE,
                actions));
        addWidget.accept(button(
                footer.primary().x(),
                y,
                half,
                Component.translatable("omniresonance.terminal.delete.confirm"),
                true,
                !pending,
                Action.CONFIRM_DELETE,
                actions));
    }

    private static TerminalButton button(
            int x,
            int y,
            int width,
            Component label,
            boolean primary,
            boolean active,
            Action action,
            Consumer<Action> actions) {
        TerminalButton button = new TerminalButton(
                x, y, Math.max(0, width), CONTROL_HEIGHT, label, ignored -> actions.accept(action), primary);
        button.active = active;
        return button;
    }

    private static void renderSettings(
            GuiGraphics graphics, Font font, TerminalLayout terminal, NetworkTerminalState.NetworkSettings state) {
        Layout layout = calculate(terminal);
        TerminalTheme.renderPanel(graphics, layout.information());
        TerminalTheme.renderPanel(graphics, layout.actions());
        graphics.drawString(
                font,
                TerminalText.title(Component.translatable("omniresonance.terminal.settings.information")),
                layout.information().x() + 8,
                layout.information().y() + 8,
                TerminalTheme.TEXT,
                false);
        graphics.drawString(
                font,
                TerminalText.title(Component.translatable("omniresonance.terminal.settings.actions")),
                layout.actions().x() + 8,
                layout.actions().y() + 8,
                TerminalTheme.TEXT,
                false);
        if (!state.settings().ownerActions()) {
            drawWrapped(
                    graphics,
                    font,
                    Component.translatable("omniresonance.terminal.settings.administrator_read_only"),
                    layout.actions().x() + 8,
                    layout.actions().y() + 32,
                    Math.max(0, layout.actions().width() - 16),
                    TerminalTheme.MUTED,
                    3);
        }
    }

    private static void renderRename(GuiGraphics graphics, Font font, TerminalLayout terminal) {
        TerminalLayout.Rect content = TerminalDialogLayout.editor(terminal.content());
        TerminalDialogLayout.render(graphics, terminal.content(), content);
        TerminalText.drawDialogTitle(
                graphics, font, Component.translatable("omniresonance.terminal.settings.rename_title"), content);
        graphics.drawString(
                font,
                Component.translatable("omniresonance.terminal.settings.network_name"),
                content.x() + 12,
                content.y() + 40,
                TerminalTheme.MUTED,
                false);
    }

    private static void renderDeletion(
            GuiGraphics graphics, Font font, TerminalLayout terminal, NetworkTerminalState.NetworkDelete state) {
        TerminalLayout.Rect content = terminal.content();
        TerminalLayout.Rect modal = TerminalDialogLayout.confirmation(terminal.content(), font, deletionMessage(state));
        graphics.fill(content.x(), content.y(), content.right(), content.bottom(), TerminalTheme.MODAL_DIM);
        TerminalTheme.renderDialogPanel(graphics, modal);
        TerminalText.drawDialogTitle(
                graphics,
                font,
                Component.translatable(
                        "omniresonance.terminal.settings.delete.title",
                        state.deletion().name()),
                modal);
        graphics.drawWordWrap(
                font,
                TerminalText.body(deletionMessage(state)),
                modal.x() + 10,
                modal.y() + 34,
                modal.width() - 20,
                TerminalTheme.MUTED);
    }

    private static Component deletionMessage(NetworkTerminalState.NetworkDelete state) {
        return Component.translatable("omniresonance.terminal.settings.delete.message")
                .append("\n")
                .append(Component.translatable(
                        "omniresonance.terminal.settings.delete.counts",
                        state.deletion().administratorCount(),
                        state.deletion().tunnelCount(),
                        state.deletion().channelCount()));
    }

    private static void drawWrapped(
            GuiGraphics graphics, Font font, Component text, int x, int y, int width, int color, int maximumLines) {
        int line = 0;
        for (FormattedCharSequence sequence : font.split(TerminalText.body(text), Math.max(1, width))) {
            if (line >= maximumLines) {
                break;
            }
            graphics.drawString(font, sequence, x, y + line * 10, color, false);
            line++;
        }
    }
}
