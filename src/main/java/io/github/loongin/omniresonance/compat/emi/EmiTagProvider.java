// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.emi;

import dev.emi.emi.api.EmiApi;
import io.github.loongin.omniresonance.client.RecipeTagCandidates;
import io.github.loongin.omniresonance.client.RecipeTagCopy;
import java.util.ArrayList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/** Optional EMI public-API adapter; no internal screen classes or registry-wide scans. */
final class EmiTagProvider implements RecipeTagCopy.Provider {
    public boolean textInputActive() {
        return EmiApi.isSearchFocused();
    }

    public RecipeTagCopy.Selection hovered(Screen screen, int x, int y) {
        var hovered = EmiApi.getHoveredStack(x, y, true);
        if (hovered.isEmpty()) return RecipeTagCopy.Selection.empty();
        var alternatives = hovered.getStack().getEmiStacks();
        if (alternatives.size() > RecipeTagCandidates.MAX_CANDIDATES) return RecipeTagCopy.Selection.unsupported();
        var result = new ArrayList<RecipeTagCandidates.Candidate>();
        for (var stack : alternatives) {
            if (stack.isEmpty()) continue;
            Object value = stack.getKey() instanceof Item
                    ? stack.getItemStack()
                    : stack.getKey() instanceof Fluid fluid ? new FluidStack(fluid, 1) : null;
            var candidate = RecipeTagCandidates.from(value);
            if (candidate == null) return RecipeTagCopy.Selection.unsupported();
            result.add(candidate);
        }
        return new RecipeTagCopy.Selection(true, result);
    }
}
