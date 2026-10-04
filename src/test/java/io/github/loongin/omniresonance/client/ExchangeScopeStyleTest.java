// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class ExchangeScopeStyleTest {
    @Test
    void allModeShowsAllSupportedResourceRowsAsSelectedLikeTheNodePicker() {
        var picker = ExchangeResourceSelection.scope(
                this, List.of(ResourceTypes.ITEM, ResourceTypes.FLUID), true, Set.of(), Set.of());
        assertTrue(picker.scope().selected(ResourceTypes.ITEM));
        assertTrue(picker.scope().selected(ResourceTypes.FLUID));
        picker.choose(ResourceTypes.ITEM);
        assertFalse(picker.scope().selected(ResourceTypes.ITEM));
        assertTrue(picker.scope().selected(ResourceTypes.FLUID));
    }

    @Test
    void exchangeUsesTheRealSharedWidgetBuilderAndKeepsMissingIdsReadable() {
        var missing = ResourceLocation.parse("missing:steam");
        var picker = ExchangeResourceSelection.scope(
                this,
                List.of(ResourceTypes.ITEM, ResourceTypes.FLUID),
                false,
                Set.of(ResourceTypes.ITEM, missing),
                Set.of());
        var rows = new ArrayList<TerminalRowButton>();
        var body = TerminalLayout.terminal(640, 360).content();
        ResourceTypeSelectionView.buildRows(
                ResourceTypeSelectionView.layout(body, picker), picker, true, rows::add, () -> {});
        assertEquals(3, rows.size());
        assertTrue(picker.unavailable(missing));
        assertTrue(picker.scope().selected(missing));
        for (var row : rows) assertFalse(row.getMessage().getString().startsWith("["));
    }

    @Test
    void bothOwnersHaveTheSameFourScopeActionsAndCancelDoesNotApplyChanges() {
        var parent = new java.util.LinkedHashSet<>(Set.of(ResourceTypes.ITEM));
        var picker = ExchangeResourceSelection.scope(
                this, List.of(ResourceTypes.ITEM, ResourceTypes.FLUID), false, parent, Set.of());
        picker.choose(ResourceTypes.FLUID);
        assertEquals(Set.of(ResourceTypes.ITEM), parent);
        var widgets = new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        int[] calls = {0, 0};
        ResourceTypeSelectionView.buildScopeActions(
                ResourceTypeSelectionView.layout(
                        TerminalLayout.terminal(640, 360).content(), picker),
                picker,
                true,
                widgets::add,
                () -> {},
                () -> calls[0]++,
                () -> calls[1]++);
        assertEquals(4, widgets.size());
        ((TerminalButton) widgets.get(2)).onPress();
        assertEquals(0, calls[0]);
        assertEquals(1, calls[1]);
        assertEquals(Set.of(ResourceTypes.ITEM), parent);
    }
}
