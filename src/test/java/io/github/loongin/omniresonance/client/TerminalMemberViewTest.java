// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.NetworkMemberPage;
import io.github.loongin.omniresonance.networking.NetworkMemberSummary;
import io.github.loongin.omniresonance.networking.NetworkSummary;
import io.github.loongin.omniresonance.networking.NetworkTerminalState;
import io.github.loongin.omniresonance.networking.OnlinePlayerPage;
import io.github.loongin.omniresonance.networking.OnlinePlayerSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class TerminalMemberViewTest {
    private static final UUID OWNER = new UUID(850, 1);
    private static final UUID ADMIN = new UUID(850, 2);
    private static final NetworkSummary NETWORK = new NetworkSummary(new UUID(851, 1), OWNER, "Network");
    private static final NetworkMemberSummary MEMBER =
            new NetworkMemberSummary(ADMIN, "Admin", NetworkMemberSummary.Role.ADMINISTRATOR, false);

    @Test
    void onlyOwnersGetMemberMutationControlsAndOwnersCannotBeRemoved() {
        NetworkMemberPage page = new NetworkMemberPage(
                List.of(new NetworkMemberSummary(OWNER, "Owner", NetworkMemberSummary.Role.OWNER, true), MEMBER),
                2,
                false,
                false);
        NetworkTerminalState.Members state = new NetworkTerminalState.Members(NETWORK, page, 128, ADMIN);
        Host host = new Host();
        host.open(state, OWNER);
        assertEquals(TerminalHeaderLayout.Action.CREATE, TerminalInteractionPolicy.topBarAction(true, state, OWNER));
        TerminalButton remove = host.children().stream()
                .filter(TerminalButton.class::isInstance)
                .map(TerminalButton.class::cast)
                .findFirst()
                .orElseThrow();
        remove.onPress();
        assertEquals(List.of(new TerminalMemberView.Action.Remove(ADMIN)), host.actions);
        host.open(state, ADMIN);
        assertEquals(TerminalHeaderLayout.Action.NONE, TerminalInteractionPolicy.topBarAction(true, state, ADMIN));
        assertTrue(host.children().stream().noneMatch(TerminalButton.class::isInstance));
        host.open(new NetworkTerminalState.Members(NETWORK, page, 128, OWNER), OWNER);
        assertTrue(host.children().stream().noneMatch(TerminalButton.class::isInstance));
    }

    @Test
    void candidateSearchUsesEnterAndKeepsEditingStateWhenReplacingRows() {
        ClientTextSearch.usePlain();
        Host host = new Host();
        host.open(
                new NetworkTerminalState.AdministratorCandidates(
                        NETWORK,
                        new OnlinePlayerPage(
                                new UUID(852, 1),
                                List.of(
                                        new OnlinePlayerSummary(ADMIN, "Alpha"),
                                        new OnlinePlayerSummary(new UUID(850, 3), "Beta")),
                                0,
                                2,
                                false)),
                OWNER);
        assertTrue(host.view.keyPressed(GLFW.GLFW_KEY_ENTER, 0));
        TerminalSearchBox field = host.view.searchField();
        assertSame(field, host.getFocused());
        field.setValue("be");
        field.setCursorPosition(1);
        field.setHighlightPos(0);
        host.view.tick(1);
        assertSame(field, host.view.searchField());
        assertSame(field, host.getFocused());
        assertEquals(1, field.getCursorPosition());
        assertEquals("b", field.getHighlighted());
        List<TerminalRowButton> rows = host.children().stream()
                .filter(TerminalRowButton.class::isInstance)
                .map(TerminalRowButton.class::cast)
                .toList();
        assertEquals(1, rows.size());
        rows.getFirst().onPress();
        assertEquals(List.of(new TerminalMemberView.Action.Add(new UUID(850, 3))), host.actions);
        assertFalse(host.view.keyPressed(GLFW.GLFW_KEY_ENTER, 0));
        assertTrue(host.view.closeLocalLayer());
        assertFalse(host.view.expanded());
        assertFalse(host.view.closeLocalLayer());
    }

    @Test
    void removalConfirmationEmitsOnlyExplicitCancelOrConfirmIntents() {
        Host host = new Host();
        host.open(new NetworkTerminalState.RemoveAdministrator(NETWORK, MEMBER), OWNER);
        var buttons = host.children().stream()
                .filter(TerminalButton.class::isInstance)
                .map(TerminalButton.class::cast)
                .toList();
        assertEquals(2, buttons.size());
        buttons.getFirst().onPress();
        assertEquals(new TerminalMemberView.Action.Back(), host.actions.getFirst());
        host.open(new NetworkTerminalState.RemoveAdministrator(NETWORK, MEMBER), OWNER);
        buttons = host.children().stream()
                .filter(TerminalButton.class::isInstance)
                .map(TerminalButton.class::cast)
                .toList();
        buttons.getLast().onPress();
        assertEquals(new TerminalMemberView.Action.ConfirmRemove(), host.actions.getLast());
    }

    private static final class Host extends Screen {
        private final TerminalLayout layout = TerminalLayout.calculate(640, 360);
        private final TerminalMemberView view = new TerminalMemberView(this, this::rebuildView);
        private final List<TerminalMemberView.Action> actions = new ArrayList<>();
        private UUID actor;

        private Host() {
            super(Component.empty());
            font =
                    new Font(
                            id -> {
                                throw new AssertionError("Widget tests do not render glyphs");
                            },
                            false) {
                        @Override
                        public java.util.List<net.minecraft.util.FormattedCharSequence> split(
                                net.minecraft.network.chat.FormattedText text, int width) {
                            var result = new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
                            String value = text.getString();
                            for (int i = 0; i < value.length(); i += Math.max(1, width))
                                result.add(net.minecraft.util.FormattedCharSequence.forward(
                                        value.substring(i, Math.min(value.length(), i + Math.max(1, width))),
                                        net.minecraft.network.chat.Style.EMPTY));
                            return result;
                        }

                        @Override
                        public int width(String text) {
                            return text.length();
                        }

                        @Override
                        public String plainSubstrByWidth(String text, int width) {
                            return text.substring(0, Math.clamp(width, 0, text.length()));
                        }

                        @Override
                        public String plainSubstrByWidth(String text, int width, boolean tail) {
                            int length = Math.clamp(width, 0, text.length());
                            return tail ? text.substring(text.length() - length) : text.substring(0, length);
                        }
                    };
        }

        private void open(NetworkTerminalState state, UUID actor) {
            this.actor = actor;
            view.apply(state);
            rebuildView();
        }

        private void rebuildView() {
            clearWidgets();
            clearFocus();
            view.build(font, layout, actor, false, this::addRenderableWidget, this::removeWidget, actions::add);
            if (view.searchField() != null) setFocused(view.searchField());
        }
    }
}
