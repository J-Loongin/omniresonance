// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TerminalScreenSessionsTest {
    private final TerminalScreenSessions sessions = TerminalScreenSessions.INSTANCE;

    @BeforeEach
    void setup() {
        sessions.clear();
        TerminalScreenSessions.recipeParent(screen -> screen instanceof Recipe child ? child.parent : null);
    }

    @AfterEach
    void cleanup() {
        sessions.clear();
        TerminalScreenSessions.recipeParent(screen -> null);
    }

    @Test
    void recipeRoundTripRetainsAuthorityResponsesAndOnlyTicksTheHiddenParent() {
        var parent = new Page();
        var child = new Recipe(parent);
        parent.removed();
        sessions.tick(child, true);
        assertSame(parent, sessions.target(child));
        assertEquals(1, parent.ticks);
        assertEquals(0, parent.closed);
        sessions.tick(parent, true);
        assertSame(parent, sessions.target(parent));
        assertEquals(1, parent.ticks, "Visible pages already receive the normal screen tick");
        parent.removed();
        sessions.tick(null, true);
        sessions.tick(null, true);
        assertEquals(1, parent.closed);
        assertNull(sessions.target(child), "A late child must not resurrect a closed session");
    }

    @Test
    void nestedConfirmationKeepsItsRecipeParentAndUnrelatedScreensReleaseIt() {
        var parent = new Page();
        var recipe = new Recipe(parent);
        var confirm = confirm();
        parent.removed();
        sessions.opening(recipe, confirm);
        sessions.tick(confirm, true);
        assertSame(parent, sessions.target(confirm));
        assertEquals(0, parent.closed);
        sessions.tick(new Recipe(null), true);
        assertEquals(1, parent.closed);
        assertNull(sessions.target(confirm));
    }

    @Test
    void canceledOpeningCannotSuppressALaterPermanentClose() {
        var parent = new Page();
        sessions.opening(parent, confirm());
        sessions.tick(parent, true);
        parent.removed();
        sessions.tick(null, true);
        assertEquals(1, parent.closed);
    }

    @Test
    void menuInvalidationAndDisconnectEndHiddenSessionsWithoutRetry() {
        var parent = new Page();
        var recipe = new Recipe(parent);
        parent.removed();
        parent.valid = false;
        sessions.tick(recipe, true);
        assertEquals(1, parent.closed);
        assertEquals(0, parent.ticks);
        assertNull(sessions.target(recipe));
        var second = new Page();
        second.removed();
        sessions.tick(new Recipe(second), false);
        assertEquals(1, second.closed);
        assertEquals(0, second.ticks);
    }

    private static ConfirmScreen confirm() {
        return new ConfirmScreen(ignored -> {}, Component.empty(), Component.empty());
    }

    private static final class Page extends Screen implements TerminalScreenSessions.Participant {
        int ticks, closed;
        boolean valid = true;

        Page() {
            super(Component.empty());
        }

        public boolean sessionValid() {
            return valid;
        }

        public void tick() {
            ticks++;
        }

        public void removed() {
            TerminalScreenSessions.removed(this, () -> {
                closed++;
                valid = false;
            });
        }
    }

    private static final class Recipe extends Screen {
        final Screen parent;

        Recipe(Screen parent) {
            super(Component.empty());
            this.parent = parent;
        }
    }
}
