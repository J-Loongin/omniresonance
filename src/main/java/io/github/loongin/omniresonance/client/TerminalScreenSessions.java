// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/** Client-thread ownership for temporary recipe/confirmation screens. At most eight detached pages/links are
 * retained until return, unrelated navigation, menu invalidation or disconnect. Cleanup is deferred until the
 * actual destination is known, so canceled Opening events cannot turn a permanent close into a suspension. */
public final class TerminalScreenSessions {
    static final TerminalScreenSessions INSTANCE = new TerminalScreenSessions();
    private static final int LIMIT = 8;
    private final Map<Screen, Runnable> detached = new LinkedHashMap<>();
    private final Map<Screen, Screen> confirmations = new IdentityHashMap<>();
    private Function<Screen, @Nullable Screen> recipeParent = screen -> null;

    interface Participant {
        default boolean sessionValid() {
            return true;
        }
    }

    /** Optional recipe integration installs a public-API parent resolver; it must not retain world/runtime state. */
    public static void recipeParent(Function<Screen, @Nullable Screen> resolver) {
        INSTANCE.recipeParent = java.util.Objects.requireNonNull(resolver);
    }

    static void removed(Screen screen, Runnable cleanup) {
        INSTANCE.defer(screen, cleanup);
    }

    void defer(Screen screen, Runnable cleanup) {
        if (!detached.containsKey(screen) && detached.size() >= LIMIT) {
            var oldest = detached.entrySet().iterator().next();
            detached.remove(oldest.getKey());
            oldest.getValue().run();
        }
        detached.put(screen, cleanup);
    }

    void opening(@Nullable Screen old, @Nullable Screen next) {
        if (old != null && next instanceof ConfirmScreen && old != next) {
            if (confirmations.size() >= LIMIT) confirmations.clear();
            confirmations.put(next, old);
        }
    }

    static @Nullable Screen current() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : INSTANCE.target(mc.screen);
    }

    @Nullable
    Screen target(@Nullable Screen current) {
        Screen candidate = current;
        for (int depth = 0; depth < LIMIT && candidate != null; depth++) {
            if (candidate instanceof Participant participant) {
                return (candidate == current || detached.containsKey(candidate)) && participant.sessionValid()
                        ? candidate
                        : null;
            }
            Screen parent = confirmations.get(candidate);
            if (parent == null) parent = recipeParent.apply(candidate);
            if (parent == candidate) return null;
            candidate = parent;
        }
        return null;
    }

    void tick(@Nullable Screen current, boolean connected) {
        tick(current, connected, true);
    }

    void tick(@Nullable Screen current, boolean connected, boolean advance) {
        if (detached.isEmpty()) {
            confirmations.clear();
            return;
        }
        Screen target = connected ? target(current) : null;
        for (var entry : new ArrayList<>(detached.entrySet())) {
            Screen owner = entry.getKey();
            if (connected && owner == current && owner == target) {
                detached.remove(owner);
            } else if (owner == target) {
                if (advance) owner.tick();
            } else {
                detached.remove(owner);
                entry.getValue().run();
            }
        }
        if (detached.isEmpty()) confirmations.clear();
    }

    void clear() {
        var cleanup = new ArrayList<>(detached.values());
        detached.clear();
        confirmations.clear();
        for (Runnable dispose : cleanup) dispose.run();
    }
}
