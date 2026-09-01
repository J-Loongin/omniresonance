// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** Behavior contracts for generated M1-A material resources. */
final class MaterialDataContractTest {
    @Test
    void generatedRecipesPreserveExactShapeIngredientCountsAndYields() throws IOException {
        JsonObject dust = generatedJson("data/omniresonance/recipe/omni_dust_multiplication.json");
        JsonObject substrate = generatedJson("data/omniresonance/recipe/resonance_substrate.json");
        JsonObject core = generatedJson("data/omniresonance/recipe/resonance_core.json");

        assertEquals(
                List.of("RRR", "RDR", "RRR"),
                dust.getAsJsonArray("pattern").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList());
        assertEquals(9, dust.getAsJsonObject("result").get("count").getAsInt());
        assertEquals(9, substrate.getAsJsonArray("ingredients").size());
        assertEquals(4, substrate.getAsJsonObject("result").get("count").getAsInt());
        assertEquals(9, core.getAsJsonArray("ingredients").size());
        assertEquals(64, core.getAsJsonObject("result").get("count").getAsInt());
    }

    @Test
    void generatedModelsUseDocumentedVanillaTemporaryTextures() throws IOException {
        assertItemTexture("omni_dust", "minecraft:item/sugar");
        assertItemTexture("resonance_substrate", "minecraft:item/paper");
        assertItemTexture("resonance_core", "minecraft:item/nether_star");

        for (String name : List.of("omni_dust", "resonance_substrate", "resonance_core")) {
            assertNull(
                    resource("assets/omniresonance/textures/item/" + name + ".png"),
                    () -> name + " unexpectedly uses custom art");
        }
    }

    @Test
    void internalResonatingBlockUsesVanillaAppearanceWithoutItemOrLootData() throws IOException {
        JsonObject state = generatedJson("assets/omniresonance/blockstates/resonating_amethyst.json");
        assertEquals(
                "omniresonance:block/resonating_amethyst",
                state.getAsJsonObject("variants")
                        .getAsJsonObject("")
                        .get("model")
                        .getAsString());
        JsonObject model = generatedJson("assets/omniresonance/models/block/resonating_amethyst.json");
        assertEquals("minecraft:block/cube_all", model.get("parent").getAsString());
        assertEquals(
                "minecraft:block/amethyst_block",
                model.getAsJsonObject("textures").get("all").getAsString());
        assertNull(resource("assets/omniresonance/models/item/resonating_amethyst.json"));
        assertNull(resource("data/omniresonance/loot_table/blocks/resonating_amethyst.json"));
    }

    @Test
    void physicalNodeRecipesModelsAndLootMatchBlankFormContracts() throws IOException {
        assertShapelessConversion(
                "resonance_transfer_node",
                List.of("omniresonance:resonance_substrate", "omniresonance:resonance_core"),
                "omniresonance:resonance_transfer_node");
        assertShapelessConversion(
                "resonance_transfer_panel",
                List.of("omniresonance:resonance_transfer_node"),
                "omniresonance:resonance_transfer_panel");
        assertShapelessConversion(
                "resonance_transfer_node_from_panel",
                List.of("omniresonance:resonance_transfer_panel"),
                "omniresonance:resonance_transfer_node");

        JsonObject nodeState = generatedJson("assets/omniresonance/blockstates/resonance_transfer_node.json");
        assertEquals(
                "omniresonance:block/resonance_transfer_node",
                nodeState
                        .getAsJsonObject("variants")
                        .getAsJsonObject("")
                        .get("model")
                        .getAsString());
        JsonObject nodeModel = generatedJson("assets/omniresonance/models/block/resonance_transfer_node.json");
        assertEquals("minecraft:block/cube_column", nodeModel.get("parent").getAsString());
        assertEquals(
                "minecraft:block/quartz_block_side",
                nodeModel.getAsJsonObject("textures").get("side").getAsString());
        assertEquals(
                "minecraft:block/quartz_block_top",
                nodeModel.getAsJsonObject("textures").get("end").getAsString());
        assertEquals(
                "omniresonance:block/resonance_transfer_node",
                generatedJson("assets/omniresonance/models/item/resonance_transfer_node.json")
                        .get("parent")
                        .getAsString());

        Map<Direction, List<Integer>> bounds = Map.of(
                Direction.DOWN, List.of(0, 0, 0, 16, 2, 16),
                Direction.UP, List.of(0, 14, 0, 16, 16, 16),
                Direction.NORTH, List.of(0, 0, 0, 16, 16, 2),
                Direction.SOUTH, List.of(0, 0, 14, 16, 16, 16),
                Direction.WEST, List.of(0, 0, 0, 2, 16, 16),
                Direction.EAST, List.of(14, 0, 0, 16, 16, 16));
        JsonObject panelState = generatedJson("assets/omniresonance/blockstates/resonance_transfer_panel.json");
        for (Direction direction : Direction.values()) {
            String modelId = "omniresonance:block/resonance_transfer_panel_" + direction.getSerializedName();
            assertEquals(
                    modelId,
                    panelState
                            .getAsJsonObject("variants")
                            .getAsJsonObject("facing=" + direction.getSerializedName())
                            .get("model")
                            .getAsString());
            JsonObject model = generatedJson("assets/omniresonance/models/block/resonance_transfer_panel_"
                    + direction.getSerializedName()
                    + ".json");
            JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
            assertEquals(bounds.get(direction).subList(0, 3), integerArray(element.getAsJsonArray("from")));
            assertEquals(bounds.get(direction).subList(3, 6), integerArray(element.getAsJsonArray("to")));
            assertEquals(
                    "minecraft:block/quartz_block_side",
                    model.getAsJsonObject("textures").get("all").getAsString());
        }
        assertEquals(
                "omniresonance:block/resonance_transfer_panel_south",
                generatedJson("assets/omniresonance/models/item/resonance_transfer_panel.json")
                        .get("parent")
                        .getAsString());

        assertPlainSelfLoot("resonance_transfer_node");
        assertPlainSelfLoot("resonance_transfer_panel");
        assertNull(resource("assets/omniresonance/textures/block/resonance_transfer_node.png"));
        assertNull(resource("assets/omniresonance/textures/block/resonance_transfer_panel.png"));
    }

    private static void assertItemTexture(String item, String expectedTexture) throws IOException {
        JsonObject model = generatedJson("assets/omniresonance/models/item/" + item + ".json");
        assertEquals("minecraft:item/generated", model.get("parent").getAsString());
        assertEquals(
                expectedTexture, model.getAsJsonObject("textures").get("layer0").getAsString());
    }

    private static void assertShapelessConversion(String recipe, List<String> expectedInputs, String output)
            throws IOException {
        JsonObject json = generatedJson("data/omniresonance/recipe/" + recipe + ".json");
        assertEquals("minecraft:crafting_shapeless", json.get("type").getAsString());
        assertEquals(
                expectedInputs,
                json.getAsJsonArray("ingredients").asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .map(ingredient -> ingredient.get("item").getAsString())
                        .toList());
        assertEquals(output, json.getAsJsonObject("result").get("id").getAsString());
        assertEquals(1, json.getAsJsonObject("result").get("count").getAsInt());
    }

    private static List<Integer> integerArray(com.google.gson.JsonArray array) {
        return array.asList().stream().map(JsonElement::getAsInt).toList();
    }

    private static void assertPlainSelfLoot(String block) throws IOException {
        JsonObject loot = generatedJson("data/omniresonance/loot_table/blocks/" + block + ".json");
        String encoded = loot.toString();
        assertTrue(encoded.contains("omniresonance:" + block));
        assertFalse(encoded.contains("copy_components"));
        assertFalse(encoded.contains("block_entity"));
    }

    private static JsonObject generatedJson(String relativePath) throws IOException {
        try (InputStream stream = resource(relativePath)) {
            assertTrue(stream != null, () -> "Missing generated resource " + relativePath);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static InputStream resource(String relativePath) {
        return MaterialDataContractTest.class.getResourceAsStream("/" + relativePath);
    }
}
