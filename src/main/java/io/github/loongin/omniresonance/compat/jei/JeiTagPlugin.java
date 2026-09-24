// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.jei;

import io.github.loongin.omniresonance.client.RecipeTagCandidates;
import io.github.loongin.omniresonance.client.RecipeTagCopy;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Discovered only by installed JEI; shared/bootstrap classes never reference the optional API. */
@JeiPlugin
public final class JeiTagPlugin implements IModPlugin {
    private volatile @Nullable IJeiRuntime runtime;

    @Override
    public void registerGuiHandlers(mezz.jei.api.registration.IGuiHandlerRegistration registration) {
        registerGhost(registration, io.github.loongin.omniresonance.client.RecipeGhostTarget.screenType());
    }

    private static <T extends Screen> void registerGhost(
            mezz.jei.api.registration.IGuiHandlerRegistration registration, Class<T> screenType) {
        registration.addGuiScreenHandler(screenType, screen -> {
            var bridge = (io.github.loongin.omniresonance.client.RecipeGhostTarget) screen;
            var bounds = bridge.recipeGuiBounds();
            return new mezz.jei.api.gui.handlers.IGuiProperties() {
                public Class<? extends Screen> screenClass() {
                    return screenType;
                }

                public int guiLeft() {
                    return bounds.x();
                }

                public int guiTop() {
                    return bounds.y();
                }

                public int guiXSize() {
                    return bounds.width();
                }

                public int guiYSize() {
                    return bounds.height();
                }

                public int screenWidth() {
                    return screen.width;
                }

                public int screenHeight() {
                    return screen.height;
                }
            };
        });
        registration.addGhostIngredientHandler(screenType, new mezz.jei.api.gui.handlers.IGhostIngredientHandler<T>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(
                    T screen, mezz.jei.api.ingredients.ITypedIngredient<I> typed, boolean doStart) {
                var ingredient =
                        io.github.loongin.omniresonance.client.RecipeGhostTarget.ingredient(typed.getIngredient());
                var bridge = (io.github.loongin.omniresonance.client.RecipeGhostTarget) screen;
                var target = bridge.ghostTarget();
                if (ingredient == null || target == null) return List.of();
                return List.of(new Target<I>() {
                    public net.minecraft.client.renderer.Rect2i getArea() {
                        var area = target.area();
                        return new net.minecraft.client.renderer.Rect2i(
                                area.x(), area.y(), area.width(), area.height());
                    }

                    public void accept(I value) {
                        if (Minecraft.getInstance().screen == screen
                                && ingredient.equals(
                                        io.github.loongin.omniresonance.client.RecipeGhostTarget.ingredient(value)))
                            bridge.acceptGhost(target, ingredient);
                    }
                });
            }

            @Override
            public void onComplete() {
                // Acceptance owns the draft request; ending a drag has no further state to release.
            }
        });
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.parse("omniresonance:tag_copy");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime value) {
        runtime = value;
        Minecraft.getInstance().execute(() -> {
            if (runtime != value) return;
            RecipeTagCopy.install(RecipeTagCopy.Source.JEI, new RecipeTagCopy.Provider() {
                public boolean textInputActive() {
                    return runtime == value && value.getIngredientListOverlay().hasKeyboardFocus();
                }

                public RecipeTagCopy.Selection hovered(Screen screen, int x, int y) {
                    if (runtime != value) return RecipeTagCopy.Selection.empty();
                    var overlay = value.getIngredientListOverlay();
                    var selected = overlay.isListDisplayed()
                            ? overlay.getIngredientUnderMouse()
                            : java.util.Optional.<mezz.jei.api.ingredients.ITypedIngredient<?>>empty();
                    if (selected.isEmpty())
                        selected = value.getBookmarkOverlay().getIngredientUnderMouse();
                    if (selected.isPresent()) return single(selected.get().getIngredient());
                    var item = value.getRecipesGui().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
                    if (item.isPresent()) return single(item.get());
                    var fluid = value.getRecipesGui()
                            .getIngredientUnderMouse(value.getJeiHelpers()
                                    .getPlatformFluidHelper()
                                    .getFluidIngredientType());
                    if (fluid.isPresent()) return single(fluid.get());
                    var result = new ArrayList<RecipeTagCandidates.Candidate>();
                    try (var hovered = value.getScreenHelper().getClickableIngredientUnderMouse(screen, x, y)) {
                        var iterator = hovered.iterator();
                        while (iterator.hasNext()) {
                            if (result.size() == RecipeTagCandidates.MAX_CANDIDATES)
                                return RecipeTagCopy.Selection.unsupported();
                            var candidate = RecipeTagCandidates.from(
                                    iterator.next().getTypedIngredient().getIngredient());
                            if (candidate == null) return RecipeTagCopy.Selection.unsupported();
                            result.add(candidate);
                        }
                    }
                    return new RecipeTagCopy.Selection(true, result);
                }
            });
        });
    }

    private static RecipeTagCopy.Selection single(Object ingredient) {
        var candidate = RecipeTagCandidates.from(ingredient);
        return candidate == null
                ? RecipeTagCopy.Selection.unsupported()
                : new RecipeTagCopy.Selection(true, List.of(candidate));
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        Minecraft.getInstance().execute(() -> {
            if (runtime == null) RecipeTagCopy.remove(RecipeTagCopy.Source.JEI);
        });
    }
}
