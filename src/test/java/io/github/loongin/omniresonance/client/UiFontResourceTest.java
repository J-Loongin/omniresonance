// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class UiFontResourceTest {
    private static final String FONT_FILE = "omniresonance:omniresonance_ui_subset.ttf";
    private static final int MAXIMUM_SUBSET_BYTES = 4_194_304;

    @Test
    void userNamesHaveConsistentGlyphsAcrossCommonChineseCharacters() throws Exception {
        try (var input = new ByteArrayInputStream(resource("/assets/omniresonance/font/omniresonance_ui_subset.ttf"))) {
            var font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, input);
            var charset = java.nio.charset.Charset.forName("GB2312");
            int checked = 0;
            for (int first = 0xb0; first <= 0xf7; first++) {
                for (int second = 0xa1; second <= 0xfe; second++) {
                    String value = new String(new byte[] {(byte) first, (byte) second}, charset);
                    if (value.indexOf('\ufffd') >= 0) continue;
                    int point = value.codePointAt(0);
                    assertTrue(
                            font.canDisplay(point),
                            () -> "Missing common Chinese glyph U+" + Integer.toHexString(point));
                    checked++;
                }
            }
            org.junit.jupiter.api.Assertions.assertEquals(6763, checked);
        }
    }

    @Test
    void scaledDefinitionsKeepLogicalMetricsButProvideEnoughRasterPixels() throws IOException {
        for (int sampling : new int[] {3, 4, 8, 16}) {
            assertFontDefinition("ui_scale_" + sampling, 10.0F, 0.5F, sampling);
            assertFontDefinition("ui_title_scale_" + sampling, 11.0F, 0.0F, sampling);
        }
    }

    @Test
    void uiFontsUseTheLicensedBoundedWenkaiSubsetWithMinecraftFallback() throws Exception {
        assertFontDefinition("ui", 10.0F, 0.5F);
        assertFontDefinition("ui_title", 11.0F, 0.0F);

        byte[] bytes = resource("/assets/omniresonance/font/omniresonance_ui_subset.ttf");
        assertTrue(bytes.length <= MAXIMUM_SUBSET_BYTES, () -> "UI font subset is " + bytes.length + " bytes");
        Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
        assertEquals("Omni Resonance UI", font.getFamily());
        for (int codePoint : translatedCodePoints()) {
            assertTrue(
                    font.canDisplay(codePoint),
                    () -> "UI font is missing translated glyph U+" + Integer.toHexString(codePoint));
        }

        assertTrue(text("/META-INF/licenses/LXGW-WenKai-Screen-OFL-1.1.txt")
                .contains("SIL OPEN FONT LICENSE Version 1.1"));
        String notice = text("/META-INF/third-party/LXGW-WenKai-Screen.txt");
        assertTrue(notice.contains("LXGW WenKai Screen v1.522"));
        assertTrue(notice.contains("lxgw-wenkai-screen-web@1.522.0"));
        assertTrue(notice.contains(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        assertTrue(notice.contains("glyph subset"));
    }

    private static void assertFontDefinition(String name, float size, float shiftY) throws IOException {
        assertFontDefinition(name, size, shiftY, 2.0F);
    }

    private static void assertFontDefinition(String name, float size, float shiftY, float oversample)
            throws IOException {
        JsonObject definition = json("/assets/omniresonance/font/" + name + ".json");
        JsonArray providers = definition.getAsJsonArray("providers");
        assertNotNull(providers);
        assertEquals(2, providers.size());

        JsonObject ttf = providers.get(0).getAsJsonObject();
        assertEquals("ttf", ttf.get("type").getAsString());
        assertEquals(FONT_FILE, ttf.get("file").getAsString());
        assertEquals(size, ttf.get("size").getAsFloat());
        assertEquals(oversample, ttf.get("oversample").getAsFloat());
        assertEquals(0.0F, ttf.getAsJsonArray("shift").get(0).getAsFloat());
        assertEquals(shiftY, ttf.getAsJsonArray("shift").get(1).getAsFloat());

        JsonObject fallback = providers.get(1).getAsJsonObject();
        assertEquals("reference", fallback.get("type").getAsString());
        assertEquals("minecraft:default", fallback.get("id").getAsString());
    }

    private static Set<Integer> translatedCodePoints() throws IOException {
        Set<Integer> codePoints = new HashSet<>();
        for (String language : Set.of("en_us", "zh_cn")) {
            JsonObject translations = json("/assets/omniresonance/lang/" + language + ".json");
            translations
                    .entrySet()
                    .forEach(entry -> entry.getValue()
                            .getAsString()
                            .codePoints()
                            .filter(codePoint -> !Character.isISOControl(codePoint))
                            .forEach(codePoints::add));
        }
        return codePoints;
    }

    private static JsonObject json(String path) throws IOException {
        try (InputStream stream = UiFontResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, () -> "Missing resource " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = UiFontResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, () -> "Missing resource " + path);
            return stream.readAllBytes();
        }
    }

    private static String text(String path) throws IOException {
        return new String(resource(path), StandardCharsets.UTF_8);
    }
}
