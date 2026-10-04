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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class UiClickSoundResourceTest {
    private static final ResourceLocation EVENT = ResourceLocation.fromNamespaceAndPath("omniresonance", "ui_click");
    private static final int SAMPLE_RATE = 48000;
    private static final int SAMPLE_COUNT = 4320;
    private static final double PEAK = Math.pow(10, -11.0 / 20.0);

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
        double[] expected = approvedSamples();
        double dot = 0;
        double expectedEnergy = 0;
        double decodedEnergy = 0;
        for (int index = 0; index < count[0]; index++) {
            dot += expected[index] * decoded[index];
            expectedEnergy += expected[index] * expected[index];
            decodedEnergy += decoded[index] * decoded[index];
        }
        assertTrue(decodedEnergy > 0.01, "The resource must not be silent");
        assertTrue(
                dot / Math.sqrt(expectedEnergy * decodedEnergy) > 0.99,
                "Vorbis encoding must preserve the approved waveform, pitch and alignment");
        double gainDb = 10 * Math.log10(decodedEnergy / expectedEnergy);
        assertTrue(Math.abs(gainDb) < 1.0, "Preserve the approved level within codec tolerance");
    }

    private static double[] approvedSamples() {
        double[] samples = new double[SAMPLE_COUNT];
        double maximum = 0;
        for (int index = 0; index < samples.length; index++) {
            double time = index / (double) SAMPLE_RATE;
            double onset = square(Math.sin(Math.PI / 2 * Math.min(1.0, time / 0.00065)));
            double release =
                    square(Math.sin(Math.PI / 2 * Math.min(1.0, (samples.length - 1 - index) / (SAMPLE_RATE * 0.010))));
            double electronic = 0.48 * Math.sin(2 * Math.PI * 1200 * time) * Math.exp(-time / 0.008);
            electronic += 0.045 * Math.sin(2 * Math.PI * 3150 * time) * Math.exp(-time / 0.003);
            double articulation = 0.30 * Math.sin(2 * Math.PI * 1320 * time) * Math.exp(-time / 0.0022);
            articulation += 0.075 * Math.sin(2 * Math.PI * 2640 * time) * Math.exp(-time / 0.00085);
            articulation += 0.035 * Math.sin(2 * Math.PI * 330 * time) * Math.exp(-time / 0.002);
            double halo = resonance(time) + 0.14 * resonance(time - 0.007) + 0.09 * resonance(time - 0.013);
            samples[index] = (electronic + articulation + halo) * onset * release;
            maximum = Math.max(maximum, Math.abs(samples[index]));
        }
        double gain = PEAK / maximum;
        for (int index = 0; index < samples.length; index++) {
            samples[index] *= gain;
        }
        assertTrue(samples[0] == 0 && samples[samples.length - 1] == 0, "Approved waveform must start and end silent");
        return samples;
    }

    private static double resonance(double time) {
        if (time <= 0) {
            return 0;
        }
        double rise = 1 - Math.exp(-time / 0.0025);
        return rise
                * (0.13 * Math.sin(2 * Math.PI * 2310 * time) * Math.exp(-time / 0.019)
                        + 0.025 * Math.sin(2 * Math.PI * 3465 * time) * Math.exp(-time / 0.015));
    }

    private static double square(double value) {
        return value * value;
    }

    private static JsonObject json(String path) throws IOException {
        // Trusted bundled metadata can grow with translations; the 64 KiB binary bound belongs to the click audio.
        try (InputStream stream = UiClickSoundResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, () -> "Missing metadata resource " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
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
