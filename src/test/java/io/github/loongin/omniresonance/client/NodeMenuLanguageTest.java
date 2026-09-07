// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.loongin.omniresonance.networking.NodeMenuResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NodeMenuLanguageTest {
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "omniresonance.node_menu.title.unconfigured",
            "omniresonance.node_menu.loading",
            "omniresonance.node_menu.no_access.title",
            "omniresonance.node_menu.no_access.message",
            "omniresonance.node_menu.no_networks.title",
            "omniresonance.node_menu.no_networks.message",
            "omniresonance.node_menu.no_networks.key",
            "omniresonance.node_menu.networks",
            "omniresonance.node_menu.role.owner",
            "omniresonance.node_menu.role.admin",
            "omniresonance.node_menu.name",
            "omniresonance.node_menu.suggested_name",
            "omniresonance.node_menu.link",
            "omniresonance.node_menu.save",
            "omniresonance.node_menu.cancel",
            "omniresonance.node_menu.rename",
            "omniresonance.node_menu.network",
            "omniresonance.node_menu.position",
            "omniresonance.node_menu.form",
            "omniresonance.node_menu.target_face",
            "omniresonance.node_menu.mode",
            "omniresonance.node_menu.mode.unconfigured",
            "omniresonance.node_menu.mode.direct",
            "omniresonance.node_menu.mode.domain",
            "omniresonance.node_menu.mode.direct.description",
            "omniresonance.node_menu.mode.domain.description",
            "omniresonance.node_menu.enabled",
            "omniresonance.node_menu.disabled",
            "omniresonance.node_menu.enable",
            "omniresonance.node_menu.disable",
            "omniresonance.node_menu.chunk_request.on",
            "omniresonance.node_menu.chunk_request.off",
            "omniresonance.node_menu.chunk_request.note",
            "omniresonance.node_menu.disabled.message",
            "omniresonance.node_menu.confirm.discard.title",
            "omniresonance.node_menu.confirm.discard.message",
            "omniresonance.node_menu.confirm.discard",
            "omniresonance.node_menu.confirm.continue",
            "omniresonance.node_menu.confirm.disable.title",
            "omniresonance.node_menu.confirm.disable.message",
            "omniresonance.node_menu.confirm.mode.title",
            "omniresonance.node_menu.confirm.mode.message",
            "omniresonance.node_menu.network.switch",
            "omniresonance.node_menu.network.move",
            "omniresonance.node_menu.network.move.message",
            "omniresonance.node_menu.tunnels",
            "omniresonance.node_menu.tunnel",
            "omniresonance.node_menu.tunnel.search",
            "omniresonance.node_menu.tunnel.summary.disabled",
            "omniresonance.node_menu.tunnel.restricted",
            "omniresonance.node_menu.channels",
            "omniresonance.node_menu.channel.summary",
            "omniresonance.node_menu.channel.join",
            "omniresonance.node_menu.channel.configure",
            "omniresonance.node_menu.channel.manage",
            "omniresonance.node_menu.channel.create",
            "omniresonance.node_menu.channel.rename",
            "omniresonance.node_menu.channel.delete",
            "omniresonance.node_menu.channel.prefix",
            "omniresonance.node_menu.channel.delete.impact",
            "omniresonance.node_menu.tunnel.switch.title",
            "omniresonance.node_menu.tunnel.switch.message",
            "omniresonance.node_menu.tunnel.switch.confirm",
            "omniresonance.node_menu.direction",
            "omniresonance.node_menu.direction.input",
            "omniresonance.node_menu.direction.output",
            "omniresonance.node_menu.direction.none",
            "omniresonance.node_menu.domain.configure",
            "omniresonance.node_menu.binding.exit",
            "omniresonance.node_menu.domain.remove",
            "omniresonance.node_menu.confirm.remove_binding.title",
            "omniresonance.node_menu.confirm.remove_binding.message",
            "omniresonance.node_menu.confirm.remove_domain.title",
            "omniresonance.node_menu.confirm.remove_domain.message",
            "omniresonance.node_menu.pending");

    @Test
    void bothLanguagesCoverEveryNodeStateActionAndStableFailure() throws IOException {
        Set<String> required = new HashSet<>(REQUIRED_KEYS);
        for (NodeMenuResponse.Reason reason : NodeMenuResponse.Reason.values()) {
            required.add(reason.translationKey());
        }
        for (String language : Set.of("en_us", "zh_cn")) {
            JsonObject translations = load(language);
            for (String key : required) {
                JsonElement value = translations.get(key);
                assertNotNull(value, () -> language + " is missing " + key);
                assertFalse(value.getAsString().isBlank(), () -> language + " has a blank value for " + key);
            }
        }
    }

    private static JsonObject load(String language) throws IOException {
        String resource = "/assets/omniresonance/lang/" + language + ".json";
        try (InputStream stream = NodeMenuLanguageTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, () -> "Missing language resource " + resource);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
