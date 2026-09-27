// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

/** Client-only coordinator with at most two optional providers and one current-screen tag popup. */
@EventBusSubscriber(modid = "omniresonance", value = Dist.CLIENT)
public final class RecipeTagCopy {
    public enum Source {
        JEI,
        EMI
    }

    public record Selection(boolean complete, List<RecipeTagCandidates.Candidate> candidates) {
        public Selection {
            candidates = List.copyOf(candidates);
            if (candidates.size() > RecipeTagCandidates.MAX_CANDIDATES)
                throw new IllegalArgumentException("Too many hover candidates");
        }

        public static Selection empty() {
            return new Selection(true, List.of());
        }

        public static Selection unsupported() {
            return new Selection(false, List.of());
        }
    }

    public interface Provider {
        boolean textInputActive();

        Selection hovered(Screen screen, int x, int y);
    }

    private static final EnumMap<Source, Provider> PROVIDERS = new EnumMap<>(Source.class);
    private static @Nullable Screen owner;
    private static @Nullable TerminalTagPopup popup;
    private static String resourceType = "";

    private RecipeTagCopy() {}

    /** Client-thread registration; adapters own their external API lifecycle and never expose it to the server. */
    public static void install(Source source, Provider provider) {
        PROVIDERS.put(source, java.util.Objects.requireNonNull(provider));
    }

    public static void remove(Source source) {
        PROVIDERS.remove(source);
        clear();
    }

    private static void clear() {
        owner = null;
        popup = null;
        resourceType = "";
    }

    static boolean textFocused(@Nullable GuiEventListener value) {
        for (int depth = 0; value != null && depth < 16; depth++) {
            if (value instanceof EditBox field && field.canConsumeInput()) return true;
            if (!(value instanceof ContainerEventHandler container)) return false;
            var next = container.getFocused();
            if (next == value) return true;
            value = next;
        }
        return value != null;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (popup != null && owner == event.getScreen() && event.getKeyCode() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            clear();
            event.setCanceled(true);
            return;
        }
        if (PROVIDERS.isEmpty()
                || !Screen.isCopy(event.getKeyCode())
                || textFocused(event.getScreen().getFocused())) return;
        var screen = event.getScreen();
        if (screen instanceof net.minecraft.client.gui.screens.inventory.BookEditScreen
                || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        int x = (int) (minecraft.mouseHandler.xpos()
                * screen.width
                / minecraft.getWindow().getScreenWidth());
        int y = (int) (minecraft.mouseHandler.ypos()
                * screen.height
                / minecraft.getWindow().getScreenHeight());
        var candidates = new ArrayList<RecipeTagCandidates.Candidate>();
        for (var source : Source.values()) {
            var provider = PROVIDERS.get(source);
            if (provider == null) continue;
            try {
                if (provider.textInputActive()) return;
                var value = provider.hovered(screen, x, y);
                if (!value.complete()) return;
                candidates.addAll(value.candidates());
            } catch (RuntimeException | LinkageError failure) {
                remove(source);
                org.slf4j.LoggerFactory.getLogger(RecipeTagCopy.class)
                        .warn("Disabled optional {} tag bridge after an API failure", source, failure);
                return;
            }
        }
        var common = RecipeTagCandidates.common(candidates).orElse(null);
        if (common == null) return;
        event.setCanceled(true);
        clear();
        owner = screen;
        resourceType = common.type();
        var font = TerminalText.font(minecraft);
        popup = new TerminalTagPopup(
                common.type(),
                common.tags(),
                new TerminalLayout.Rect(x - 8, y - 8, 16, 16),
                screen.width,
                screen.height,
                text -> font.width(TerminalText.body(net.minecraft.network.chat.Component.literal(text))));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if (popup == null || owner != event.getScreen()) return;
        var choice = popup.click(event.getMouseX(), event.getMouseY(), event.getButton());
        if (choice.tag() != null) TerminalTagClipboard.copy(resourceType, List.of(choice.tag()));
        if (choice.dismiss()) clear();
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        if (popup == null || owner != event.getScreen()) return;
        if (popup.contains(event.getMouseX(), event.getMouseY())) popup.scroll(event.getScrollDeltaY());
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(ScreenEvent.Render.Post event) {
        if (popup != null && owner == event.getScreen())
            popup.render(
                    event.getGuiGraphics(),
                    TerminalText.font(Minecraft.getInstance()),
                    event.getMouseX(),
                    event.getMouseY());
    }

    @SubscribeEvent
    public static void tagsChanged(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED)
            Minecraft.getInstance().execute(() -> {
                clear();
                TerminalTagClipboard.clear();
            });
    }

    @SubscribeEvent
    public static void closing(ScreenEvent.Closing event) {
        if (owner == event.getScreen()) clear();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        TerminalTagClipboard.clear();
    }
}
