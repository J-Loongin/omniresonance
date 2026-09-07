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
            "omniresonance.terminal.topology.manage",
            "omniresonance.terminal.network_root.message",
            "omniresonance.terminal.home",
            "omniresonance.terminal.home.unavailable",
            "omniresonance.terminal.home.nodes",
            "omniresonance.terminal.home.nodes.mark",
            "omniresonance.terminal.home.tunnels",
            "omniresonance.terminal.home.tunnels.mark",
            "omniresonance.terminal.home.tunnels.meta",
            "omniresonance.terminal.home.domain",
            "omniresonance.terminal.home.domain.mark",
            "omniresonance.terminal.home.filters",
            "omniresonance.terminal.home.filters.mark",
            "omniresonance.terminal.home.loading",
            "omniresonance.terminal.home.loading.mark",
            "omniresonance.terminal.home.admins",
            "omniresonance.terminal.home.admins.mark",
            "omniresonance.terminal.home.status",
            "omniresonance.terminal.home.status.mark",
            "omniresonance.terminal.home.settings",
            "omniresonance.terminal.home.settings.mark",
            "omniresonance.terminal.home.settings.meta",
            "omniresonance.terminal.settings.information",
            "omniresonance.terminal.settings.actions",
            "omniresonance.terminal.settings.network_name",
            "omniresonance.terminal.settings.owner_name",
            "omniresonance.terminal.settings.owner_id",
            "omniresonance.terminal.settings.network_id",
            "omniresonance.terminal.settings.administrator_read_only",
            "omniresonance.terminal.settings.set_default",
            "omniresonance.terminal.settings.default_set",
            "omniresonance.terminal.settings.rename",
            "omniresonance.terminal.settings.rename_title",
            "omniresonance.terminal.settings.delete",
            "omniresonance.terminal.settings.delete.title",
            "omniresonance.terminal.settings.delete.message",
            "omniresonance.terminal.settings.delete.counts",
            "omniresonance.terminal.settings.network_deleted",
            "omniresonance.terminal.tunnels",
            "omniresonance.terminal.tunnel.create",
            "omniresonance.terminal.tunnel.rename",
            "omniresonance.terminal.tunnel.manage",
            "omniresonance.terminal.tunnel.settings",
            "omniresonance.terminal.tunnel.enabled",
            "omniresonance.terminal.tunnel.disabled",
            "omniresonance.terminal.tunnel.disabled.message",
            "omniresonance.terminal.tunnel.suggested",
            "omniresonance.terminal.tunnel.summary",
            "omniresonance.terminal.channel.create",
            "omniresonance.terminal.channel.rename",
            "omniresonance.terminal.channel.suggested",
            "omniresonance.terminal.channel.summary",
            "omniresonance.terminal.object_name",
            "omniresonance.terminal.rename",
            "omniresonance.terminal.save",
            "omniresonance.terminal.cancel",
            "omniresonance.terminal.enable",
            "omniresonance.terminal.disable",
            "omniresonance.terminal.delete",
            "omniresonance.terminal.delete.title",
            "omniresonance.terminal.delete.impact",
            "omniresonance.terminal.delete.confirm",
            "omniresonance.terminal.topology.discard.title",
            "omniresonance.terminal.topology.discard.message",
            "omniresonance.terminal.topology.pending",
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
