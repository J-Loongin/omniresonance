// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.transfer.ResourceScope;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.components.AbstractWidget;
import org.junit.jupiter.api.Test;

final class ResourceTypeSelectionTest {
    @Test
    void allRestoresHiddenSupportAndDoesNotPretendMissingTypesAreAvailable() {
        var missing = net.minecraft.resources.ResourceLocation.parse("missing:steam");
        var picker = ExchangeResourceSelection.scope(
                this,
                List.of(ResourceTypes.ITEM, ResourceTypes.FLUID),
                false,
                Set.of(ResourceTypes.ITEM, missing),
                Set.of());
        picker.scope().all();
        assertEquals(ResourceScope.Kind.ALL, picker.scope().kind());
        assertTrue(picker.scope().selected(ResourceTypes.ITEM));
        assertTrue(picker.scope().selected(ResourceTypes.FLUID));
        assertFalse(picker.scope().selected(missing));
        assertTrue(picker.unavailable(missing));
        assertEquals(List.of(), picker.scope().selection().ids());
    }

    @Test
    void emptyCustomDraftCannotApplyAndOwnerIdentityIsChecked() {
        var picker = ExchangeResourceSelection.scope(this, List.of(ResourceTypes.ITEM), true, Set.of(), Set.of());
        picker.choose(ResourceTypes.ITEM);
        var widgets = new ArrayList<AbstractWidget>();
        ResourceTypeSelectionView.buildScopeActions(
                ResourceTypeSelectionView.layout(
                        TerminalLayout.terminal(640, 360).content(), picker),
                picker,
                true,
                widgets::add,
                () -> {},
                () -> {},
                () -> {});
        assertFalse(widgets.getLast().active);
        assertTrue(picker.scope().dirty());
        assertThrows(IllegalArgumentException.class, () -> picker.scope().requireOwner(new Object()));
    }

    @Test
    void singlePickerOnlyOffersUnconfiguredTypesInTheSelectedRangeAndHasNoScopeFooter() {
        var configured = new java.util.LinkedHashSet<>(Set.of(ResourceTypes.ITEM));
        var picker = ExchangeResourceSelection.types(
                List.of(ResourceTypes.ITEM, ResourceTypes.FLUID, ResourceTypes.ENERGY),
                configured,
                false,
                Set.of(ResourceTypes.ITEM, ResourceTypes.FLUID));
        assertEquals(List.of(ResourceTypes.FLUID), picker.results());
        assertNull(picker.scope());
        picker.choose(ResourceTypes.FLUID);
        assertEquals(ResourceTypes.FLUID, picker.chosen());
        assertEquals(Set.of(ResourceTypes.ITEM), configured);
        assertThrows(IllegalArgumentException.class, () -> picker.choose(ResourceTypes.ENERGY));
        var widgets = new ArrayList<AbstractWidget>();
        ResourceTypeSelectionView.buildScopeActions(
                ResourceTypeSelectionView.layout(
                        TerminalLayout.terminal(640, 360).content(), picker),
                picker,
                true,
                widgets::add,
                () -> {},
                () -> {},
                () -> {});
        assertTrue(widgets.isEmpty());
    }
}
