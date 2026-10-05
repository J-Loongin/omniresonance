// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;

final class ModListMetadataTest {
    @Test
    void modListHasLocalizedDescriptionsFallbackAndALoadableRootLogo() throws Exception {
        var info = ModList.get()
                .getModContainerById(OmniResonanceMod.MOD_ID)
                .orElseThrow()
                .getModInfo();
        String description =
                info.getConfig().<String>getConfigElement("description").orElse("");
        assertFalse(description.isBlank(), "Mod-list fallback description is missing");
        String english = null;
        for (String language : new String[] {"en_us", "zh_cn"}) {
            try (var input = getClass().getResourceAsStream("/assets/omniresonance/lang/" + language + ".json")) {
                assertNotNull(input);
                var root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                var translated = root.get("fml.menu.mods.info.description.omniresonance");
                assertNotNull(translated, "Native mod-list translation hook is missing");
                assertFalse(translated.getAsString().isBlank());
                if (language.equals("en_us")) english = translated.getAsString();
            }
        }
        assertEquals(english, description.strip(), "Metadata fallback and English description drifted");
        String logo = info.getLogoFile().orElseThrow();
        try (var input = getClass().getResourceAsStream("/" + logo)) {
            assertNotNull(input, "Declared root logo is not packaged");
            var image = ImageIO.read(input);
            assertNotNull(image, "Declared logo is not a supported image");
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
        }
    }
}
