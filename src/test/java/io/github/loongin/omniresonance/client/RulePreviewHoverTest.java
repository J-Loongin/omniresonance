// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.loongin.omniresonance.networking.FilterRulePreview;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

class RulePreviewHoverTest {
    private static final ResourceLocation FIRST = ResourceLocation.parse("test:hydrogen"),
            SECOND = ResourceLocation.parse("test:oxygen");

    private static TerminalRowButton row(
            FilterRulePreview.Kind kind, int y, List<ResourceLocation> ids, AtomicInteger clicks) {
        var source = new RulePreviewSource() {
            public Iterator<ResourceLocation> open(FilterRulePreview preview) {
                return ids.iterator();
            }

            public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                return new RulePreviewImage(
                        ItemStack.EMPTY,
                        net.neoforged.neoforge.fluids.FluidStack.EMPTY,
                        EnergyDisplay.TEXTURE,
                        EnergyDisplay.TINT,
                        Component.literal(id.getPath()));
            }
        };
        String selector =
                switch (kind) {
                    case EXACT -> FIRST.toString();
                    case TAG -> "test:gases";
                    case GLOB -> "test:*";
                    default -> "";
                };
        var preview = new RuleIconPreview(new FilterRulePreview(kind, ResourceTypes.CHEMICAL, selector), source);
        preview.prepare(2048);
        var row = new TerminalRowButton(
                new TerminalLayout.Rect(20, y, 300, 20),
                Component.literal(selector),
                ignored -> clicks.incrementAndGet());
        row.icon(preview);
        return row;
    }

    @Test
    void inlineShowsOnlyThePointedResourceAndNeverNamesGapsOrText() {
        var row = row(FilterRulePreview.Kind.TAG, 20, List.of(FIRST, SECOND), new AtomicInteger());
        var hover = new RulePreviewHover();
        var rows = List.of(row);
        int right = row.iconRight();
        hover.update(rows, 640, 360, right - 8, 30);
        assertNull(hover.grid());
        assertSame(((RuleIconPreview) row.icon()).inlineImage(0).name(), hover.name());
        hover.update(rows, 640, 360, right - 26, 30);
        assertSame(((RuleIconPreview) row.icon()).inlineImage(1).name(), hover.name());
        hover.update(rows, 640, 360, right - 17, 30);
        assertNull(hover.name());
        assertNull(hover.grid());
        hover.update(rows, 640, 360, 40, 30);
        assertNull(hover.name());
        assertNotNull(hover.grid());
    }

    @Test
    void popupFollowsPointerWithinItsOwnerRowThenStaysFixedDuringGridInspection() {
        var row = row(FilterRulePreview.Kind.TAG, 20, List.of(FIRST, SECOND), new AtomicInteger());
        var rows = List.of(row);
        var hover = new RulePreviewHover();
        hover.update(rows, 640, 360, 40, 30);
        var firstBounds = hover.bounds();
        var grid = hover.grid();
        hover.update(rows, 640, 360, 200, 30);
        assertEquals(212, hover.bounds().x());
        assertEquals(42, hover.bounds().y());
        assertTrue(hover.bounds().x() > firstBounds.x());
        assertSame(grid, hover.grid());
        var followed = hover.bounds();
        hover.update(rows, 640, 360, followed.x() + 8, 41);
        assertSame(followed, hover.bounds());
        hover.update(rows, 640, 360, followed.x() + 8, followed.y() + 8);
        assertSame(followed, hover.bounds());
        assertSame(grid.images().getFirst().name(), hover.name());
        hover.update(rows, 640, 360, followed.x() + 26, followed.y() + 8);
        assertSame(followed, hover.bounds());
        assertSame(grid.images().get(1).name(), hover.name());
    }

    @Test
    void overlappingPreviewDoesNotCaptureThePointerBeforeItLeavesTheOwnerRow() {
        var row = row(FilterRulePreview.Kind.TAG, 20, List.of(FIRST, SECOND), new AtomicInteger());
        var hover = new RulePreviewHover();
        hover.update(List.of(row), 640, 360, 40, 21);
        hover.update(List.of(row), 640, 360, 60, 35);
        assertEquals(72, hover.bounds().x());
        assertEquals(47, hover.bounds().y());
        assertNull(hover.name());
    }

    @Test
    void enteringTheFixedGridKeepsItOpenAndOverridesAnUnderlyingRule() {
        var clicks = new AtomicInteger();
        var first = row(FilterRulePreview.Kind.TAG, 20, List.of(FIRST, SECOND), clicks);
        var underneath = row(FilterRulePreview.Kind.EXACT, 44, List.of(SECOND), clicks);
        var rows = List.of(first, underneath);
        var hover = new RulePreviewHover();
        hover.update(rows, 640, 360, 40, 30);
        var bounds = hover.bounds();
        var grid = hover.grid();
        hover.update(rows, 640, 360, bounds.x() + 8, 41);
        assertSame(grid, hover.grid());
        hover.update(rows, 640, 360, bounds.x() + 8, bounds.y() + 8);
        assertSame(bounds, hover.bounds());
        assertSame(grid.images().getFirst().name(), hover.name());
        hover.update(rows, 640, 360, bounds.x() + 26, bounds.y() + 8);
        assertSame(grid.images().get(1).name(), hover.name());
        hover.update(rows, 640, 360, bounds.right() - 2, bounds.y() + 8);
        assertNull(hover.name(), "Grid padding unexpectedly produced a resource name");
        assertSame(grid, hover.grid());
        hover.update(rows, 640, 360, bounds.x() + 4, bounds.y() + 4);
        assertNull(hover.name(), "Slot border must not select a resource");
        hover.update(rows, 640, 360, 0, 200);
        assertNull(hover.grid());
        assertNull(hover.name());
        assertEquals(0, clicks.get());
        first.onPress();
        assertEquals(1, clicks.get());
    }

    @Test
    void inlineAndGridAlwaysShowOnlyTheResourceNameForEveryRuleKind() {
        for (var kind :
                List.of(FilterRulePreview.Kind.TAG, FilterRulePreview.Kind.GLOB, FilterRulePreview.Kind.EXACT)) {
            var row = row(kind, 20, List.of(FIRST), new AtomicInteger());
            var hover = new RulePreviewHover();
            hover.update(List.of(row), 640, 360, row.iconRight() - 8, 30);
            var name = hover.name();
            assertNotNull(name);
            assertEquals("hydrogen", name.getString());
            if (kind == FilterRulePreview.Kind.EXACT) {
                hover.update(List.of(row), 640, 360, 40, 30);
                assertNull(hover.grid());
                assertNull(hover.name());
            } else {
                hover.update(List.of(row), 640, 360, 40, 30);
                var bounds = hover.bounds();
                hover.update(List.of(row), 640, 360, bounds.x() + 8, bounds.y() + 8);
                assertSame(name, hover.name());
            }
        }
    }

    @Test
    void placeholderIconsNeverPretendToBeClockChainOrBarrierResources() {
        var source = new RulePreviewSource() {
            public Iterator<ResourceLocation> open(FilterRulePreview preview) {
                throw new AssertionError("No registry access");
            }

            public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                throw new AssertionError("No resource lookup");
            }
        };
        for (var metadata : List.of(FilterRulePreview.NONE, FilterRulePreview.REFERENCE)) {
            var row = new TerminalRowButton(new TerminalLayout.Rect(20, 20, 300, 20), Component.empty(), ignored -> {});
            row.icon(new RuleIconPreview(metadata, source));
            var hover = new RulePreviewHover();
            hover.update(List.of(row), 640, 360, row.iconRight() - 8, 30);
            assertNull(hover.name());
            assertNull(hover.grid());
        }
    }

    @Test
    void retiringTheOwnerOrOpeningAnOverlayClearsBothTheGridAndName() {
        var row = row(FilterRulePreview.Kind.TAG, 20, List.of(FIRST), new AtomicInteger());
        var hover = new RulePreviewHover();
        hover.update(List.of(row), 640, 360, 40, 30);
        var bounds = hover.bounds();
        hover.update(List.of(), 640, 360, bounds.x() + 8, bounds.y() + 8);
        assertNull(hover.grid());
        assertNull(hover.name());
        hover.update(List.of(row), 640, 360, row.iconRight() - 8, 30);
        assertNotNull(hover.name());
        hover.clear();
        assertNull(hover.name());
    }
}
