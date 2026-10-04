// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class ResonanceParticleAssetTest {
    @Test
    void removedVaporDoesNotLeakIntoProductionResources() {
        assertNull(getClass().getClassLoader().getResource("assets/omniresonance/particles/resonance_wisp.json"));
        assertNull(
                getClass().getClassLoader().getResource("assets/omniresonance/textures/particle/resonance_wisp.png"));
    }

    @Test
    void crystalMoteDescriptionResolvesAnIndependentTransparentSprite() throws IOException {
        String name = "resonance_mote";
        String root = "assets/omniresonance/";
        try (var input = getClass().getClassLoader().getResourceAsStream(root + "particles/" + name + ".json")) {
            assertNotNull(input, "Particle description is missing: " + name);
            var description = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var textures = description.getAsJsonArray("textures");
            assertEquals(1, textures.size());
            assertEquals("omniresonance:" + name, textures.get(0).getAsString());
        }
        try (var input = getClass().getClassLoader().getResourceAsStream(root + "textures/particle/" + name + ".png")) {
            assertNotNull(input, "Particle sprite is missing: " + name);
            var sprite = ImageIO.read(input);
            assertNotNull(sprite);
            assertEquals(64, sprite.getWidth());
            assertEquals(64, sprite.getHeight());
            assertTrue(sprite.getColorModel().hasAlpha());
            int visible = 0;
            int translucent = 0;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int alpha = sprite.getRGB(x, y) >>> 24;
                    if (x < 4 || x >= 60 || y < 4 || y >= 60) {
                        assertEquals(0, alpha, "The sprite needs clear atlas margins");
                    }
                    if (alpha > 0) visible++;
                    if (alpha > 0 && alpha < 255) translucent++;
                }
            }
            assertTrue(visible > 32, "The sprite has no readable silhouette");
            assertTrue(visible < 2048, "The sprite became a large filled square");
            assertTrue(translucent > visible / 2, "The sprite lost its soft alpha falloff");
        }
    }
}
