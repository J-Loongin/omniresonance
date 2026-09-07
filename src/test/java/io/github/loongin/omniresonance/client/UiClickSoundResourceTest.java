// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class UiClickSoundResourceTest {
    private static final ResourceLocation EVENT = ResourceLocation.fromNamespaceAndPath("omniresonance", "ui_click");

    @Test
    void clickDefinitionPreloadsOneLocalAssetWithoutStreamingOrLayeringVanillaSounds() throws IOException {
        JsonObject definitions = json("/assets/omniresonance/sounds.json");
        JsonObject definition = definitions.getAsJsonObject("ui_click");
        assertNotNull(definition);
        assertEquals(1, definition.getAsJsonArray("sounds").size());
        JsonObject sound = definition.getAsJsonArray("sounds").get(0).getAsJsonObject();
        assertEquals("omniresonance:ui_click", sound.get("name").getAsString());
        assertTrue(sound.get("preload").getAsBoolean());
        assertFalse(sound.has("stream") && sound.get("stream").getAsBoolean());
        String subtitle = definition.get("subtitle").getAsString();
        for (String locale : new String[] {"en_us", "zh_cn"}) {
            JsonObject translations = json("/assets/omniresonance/lang/" + locale + ".json");
            assertNotNull(translations.get(subtitle));
            assertFalse(translations.get(subtitle).getAsString().isBlank());
        }
        assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(EVENT));
    }

    @Test
    void minecraftDecoderReadsTheApprovedSingleClickAtItsOriginalRateAndDuration() throws Exception {
        byte[] ogg = resource("/assets/omniresonance/sounds/ui_click.ogg");
        float[] decoded = new float[6000];
        int[] count = {0};
        try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(ogg))) {
            assertEquals(48000.0F, stream.getFormat().getSampleRate());
            assertEquals(1, stream.getFormat().getChannels());
            while (stream.readChunk(sample -> {
                assertTrue(Float.isFinite(sample));
                assertTrue(Math.abs(sample) < 0.4F, "No clipping or unexpected gain");
                assertTrue(count[0] < decoded.length, "Do not package the repeated audition clip");
                decoded[count[0]++] = sample;
            })) {
                // Drain the same decoder used by the client, without starting an audio device.
            }
        }
        assertEquals(4320, count[0]);
        try (AudioInputStream reference =
                AudioSystem.getAudioInputStream(new ByteArrayInputStream(resource("/audio/ui_click_v4_2.wav")))) {
            assertEquals(48000.0F, reference.getFormat().getSampleRate());
            assertEquals(1, reference.getFormat().getChannels());
            assertFalse(reference.getFormat().isBigEndian());
            ByteBuffer pcm = ByteBuffer.wrap(reference.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(count[0] * Short.BYTES, pcm.remaining());
            double dot = 0;
            double expectedEnergy = 0;
            double decodedEnergy = 0;
            for (int index = 0; index < count[0]; index++) {
                double expected = pcm.getShort() / 32768.0;
                dot += expected * decoded[index];
                expectedEnergy += expected * expected;
                decodedEnergy += decoded[index] * decoded[index];
            }
            assertTrue(decodedEnergy > 0.01, "The resource must not be silent");
            assertTrue(
                    dot / Math.sqrt(expectedEnergy * decodedEnergy) > 0.99,
                    "Vorbis encoding must preserve the approved waveform, pitch and alignment");
            double gainDb = 10 * Math.log10(decodedEnergy / expectedEnergy);
            assertTrue(Math.abs(gainDb) < 1.0, "Preserve the audition level within codec tolerance");
        }
    }

    private static JsonObject json(String path) throws IOException {
        return JsonParser.parseString(new String(resource(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = UiClickSoundResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, () -> "Missing sound resource " + path);
            byte[] bytes = stream.readNBytes(65537);
            assertTrue(bytes.length <= 65536);
            return bytes;
        }
    }
}
