// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.loongin.omniresonance.networking.FilterRulePreview;
import io.github.loongin.omniresonance.transfer.ResourceTypes;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class ResourcePreviewRowsTest {
    @Test
    void hiddenRowsNeitherStartPreparationNorAdvanceFinishedAnimation() {
        var opens = new AtomicInteger();
        var rows = new ResourcePreviewRows(new RulePreviewSource() {
            public Iterator<ResourceLocation> open(FilterRulePreview preview) {
                opens.incrementAndGet();
                return List.of("test:a", "test:b", "test:c", "test:d").stream()
                        .map(ResourceLocation::parse)
                        .iterator();
            }

            public RulePreviewImage image(ResourceLocation type, ResourceLocation id) {
                return RulePreviewImage.item(new ItemStack(Items.IRON_INGOT));
            }
        });
        var row = new TerminalRowButton(new TerminalLayout.Rect(0, 0, 300, 20), Component.empty(), ignored -> {});
        rows.attach(row, new FilterRulePreview(FilterRulePreview.Kind.TAG, ResourceTypes.ITEM, "test:tag"));
        for (int tick = 0; tick < 20; tick++) rows.tick(false);
        assertEquals(0, opens.get());
        rows.tick(true);
        var preview = (ResourceIconPreview) row.icon();
        assertEquals(4, preview.imageCount());
        var first = preview.current();
        for (int tick = 0; tick < 100; tick++) rows.tick(false);
        assertSame(first, preview.current());
        for (int tick = 0; tick < 19; tick++) rows.tick(true);
        assertSame(first, preview.current());
        rows.tick(true);
        assertNotSame(first, preview.current());
        assertEquals(1, opens.get());
    }
}
