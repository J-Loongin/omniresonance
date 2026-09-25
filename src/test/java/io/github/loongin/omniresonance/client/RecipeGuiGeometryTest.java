// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RecipeGuiGeometryTest {
    @Test
    void uninitializedScreensNeverReadLayoutOrPublishZeroSizedProperties() {
        for (int[] size : new int[][] {{0, 0}, {640, 0}, {0, 360}})
            assertNull(RecipeGhostTarget.geometry(size[0], size[1], () -> {
                throw new AssertionError("Layout is not initialized");
            }));
    }

    @Test
    void publishedGeometryIsDetachedFromLaterResizeAndKeepsNativeBounds() {
        var bounds = new RecipeGhostTarget.Area[] {new RecipeGhostTarget.Area(130, 65, 380, 230)};
        var before = RecipeGhostTarget.geometry(640, 360, () -> bounds[0]);
        bounds[0] = new RecipeGhostTarget.Area(290, 155, 380, 230);
        var after = RecipeGhostTarget.geometry(960, 540, () -> bounds[0]);
        assertEquals(640, before.screenWidth());
        assertEquals(130, before.area().x());
        assertEquals(960, after.screenWidth());
        assertEquals(380, after.area().width());
    }
}
