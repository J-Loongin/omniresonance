// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TerminalLanguageTest {
    private static final Set<String> REQUIRED_UI_KEYS = Set.of(
            "key.omniresonance.network_terminal",
            "key.categories.omniresonance",
            "omniresonance.terminal.title",
            "omniresonance.terminal.back",
            "omniresonance.terminal.create",
            "omniresonance.terminal.select_network",
            "omniresonance.terminal.escape_hint",
            "omniresonance.terminal.loading",
            "omniresonance.terminal.retry",
            "omniresonance.terminal.first_open.title",
            "omniresonance.terminal.first_open.message",
            "omniresonance.terminal.first_open.suggested_name",
            "omniresonance.terminal.first_open.skip",
            "omniresonance.terminal.empty.title",
            "omniresonance.terminal.empty.message",
            "omniresonance.terminal.networks",
            "omniresonance.terminal.details",
            "omniresonance.terminal.owner",
            "omniresonance.terminal.network_id",
            "omniresonance.terminal.previous",
            "omniresonance.terminal.next",
            "omniresonance.terminal.name",
            "omniresonance.terminal.create.submit",
            "omniresonance.terminal.create.pending",
            "omniresonance.terminal.no_selection",
            "omniresonance.terminal.role.owner",
            "omniresonance.terminal.role.managed",
            "omniresonance.terminal.confirm.title",
            "omniresonance.terminal.confirm.message",
            "omniresonance.terminal.confirm.create",
            "omniresonance.terminal.confirm.discard",
            "omniresonance.terminal.confirm.continue",
            "itemGroup.omniresonance.main",
            "block.omniresonance.resonating_amethyst",
            "block.omniresonance.resonance_transfer_node",
            "block.omniresonance.resonance_transfer_panel",
            "item.omniresonance.omni_dust",
            "item.omniresonance.resonance_substrate",
            "item.omniresonance.resonance_core");

    @Test
    void languageFilesHaveIdenticalNonEmptyKeySets() throws IOException {
        JsonObject english = loadLanguage("en_us");
        JsonObject chinese = loadLanguage("zh_cn");

        assertFalse(english.keySet().isEmpty());
        assertEquals(english.keySet(), chinese.keySet());
        assertAllValuesPresent(english);
        assertAllValuesPresent(chinese);
    }

    @Test
    void bothLanguagesCoverProtocolReasonsAndFixedTerminalMessages() throws IOException {
        Set<String> required = new HashSet<>(REQUIRED_UI_KEYS);
        for (NetworkTerminalResponse.Reason reason : NetworkTerminalResponse.Reason.values()) {
            required.add(reason.translationKey());
        }

        for (String language : Set.of("en_us", "zh_cn")) {
            JsonObject translations = loadLanguage(language);
            for (String key : required) {
                JsonElement value = translations.get(key);
                assertNotNull(value, () -> language + " is missing " + key);
                assertFalse(value.getAsString().isBlank(), () -> language + " has a blank value for " + key);
            }
        }
    }

    private static JsonObject loadLanguage(String language) throws IOException {
        String resource = "/assets/omniresonance/lang/" + language + ".json";
        try (InputStream stream = TerminalLanguageTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, () -> "Missing language resource " + resource);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static void assertAllValuesPresent(JsonObject translations) {
        for (String key : translations.keySet()) {
            JsonElement value = translations.get(key);
            assertFalse(value.getAsString().isBlank(), () -> "Blank translation for " + key);
        }
    }
}
