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
    void physicalNodeRecipesAndLootMatchBlankFormContracts() throws IOException {
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

        assertPlainSelfLoot("resonance_transfer_node");
        assertPlainSelfLoot("resonance_transfer_panel");
        assertNull(resource("assets/omniresonance/textures/block/resonance_transfer_node.png"));
        assertNull(resource("assets/omniresonance/textures/block/resonance_transfer_panel.png"));
    }

    @Test
    void transferNodeUsesDistinctCanonicalFacesAndRotatesBackTowardEveryFacing() throws IOException {
        JsonObject nodeModel = generatedJson("assets/omniresonance/models/block/resonance_transfer_node.json");
        assertEquals(
                Map.of(
                        "particle", "omniresonance:block/resonance_transfer_node_front",
                        "front", "omniresonance:block/resonance_transfer_node_front",
                        "top", "omniresonance:block/resonance_transfer_node_top",
                        "left", "omniresonance:block/resonance_transfer_node_left",
                        "right", "omniresonance:block/resonance_transfer_node_right",
                        "bottom", "omniresonance:block/resonance_transfer_node_bottom",
                        "back", "omniresonance:block/resonance_transfer_node_back"),
                stringMap(nodeModel.getAsJsonObject("textures")));

        JsonObject element = nodeModel.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals(List.of(0, 0, 0), integerArray(element.getAsJsonArray("from")));
        assertEquals(List.of(16, 16, 16), integerArray(element.getAsJsonArray("to")));
        JsonObject faces = element.getAsJsonObject("faces");
        assertFaceTexture(faces, Direction.NORTH, "#front");
        assertFaceTexture(faces, Direction.SOUTH, "#back");
        assertFaceTexture(faces, Direction.UP, "#top");
        assertFaceTexture(faces, Direction.DOWN, "#bottom");
        assertFaceTexture(faces, Direction.EAST, "#left");
        assertFaceTexture(faces, Direction.WEST, "#right");

        JsonObject variants = generatedJson("assets/omniresonance/blockstates/resonance_transfer_node.json")
                .getAsJsonObject("variants");
        Map<Direction, List<Integer>> rotations = Map.of(
                Direction.DOWN, List.of(270, 0),
                Direction.UP, List.of(90, 0),
                Direction.NORTH, List.of(0, 180),
                Direction.SOUTH, List.of(0, 0),
                Direction.WEST, List.of(0, 90),
                Direction.EAST, List.of(0, 270));
        Map<Direction, Direction> expectedFrontNormals = Map.of(
                Direction.DOWN, Direction.UP,
                Direction.UP, Direction.DOWN,
                Direction.NORTH, Direction.SOUTH,
                Direction.SOUTH, Direction.NORTH,
                Direction.WEST, Direction.EAST,
                Direction.EAST, Direction.WEST);
        Map<Direction, Direction> expectedTopNormals = Map.of(
                Direction.DOWN, Direction.SOUTH,
                Direction.UP, Direction.NORTH,
                Direction.NORTH, Direction.UP,
                Direction.SOUTH, Direction.UP,
                Direction.WEST, Direction.UP,
                Direction.EAST, Direction.UP);
        for (Direction facing : Direction.values()) {
            JsonObject variant = variants.getAsJsonObject("facing=" + facing.getSerializedName());
            assertEquals(
                    "omniresonance:block/resonance_transfer_node",
                    variant.get("model").getAsString());
            int rotationX = variant.has("x") ? variant.get("x").getAsInt() : 0;
            int rotationY = variant.has("y") ? variant.get("y").getAsInt() : 0;
            assertEquals(rotations.get(facing), List.of(rotationX, rotationY));
            assertEquals(expectedFrontNormals.get(facing), rotateModelDirection(Direction.NORTH, rotationX, rotationY));
            assertEquals(facing, rotateModelDirection(Direction.SOUTH, rotationX, rotationY));
            assertEquals(expectedTopNormals.get(facing), rotateModelDirection(Direction.UP, rotationX, rotationY));
        }

        assertEquals(
                "omniresonance:block/resonance_transfer_node",
                generatedJson("assets/omniresonance/models/item/resonance_transfer_node.json")
                        .get("parent")
                        .getAsString());
    }

    @Test
    void transferPanelsKeepBoundsAndReserveFullFaceArtForExposedAndContactPlanes() throws IOException {
        Map<Direction, List<Integer>> bounds = Map.of(
                Direction.DOWN, List.of(0, 0, 0, 16, 2, 16),
                Direction.UP, List.of(0, 14, 0, 16, 16, 16),
                Direction.NORTH, List.of(0, 0, 0, 16, 16, 2),
                Direction.SOUTH, List.of(0, 0, 14, 16, 16, 16),
                Direction.WEST, List.of(0, 0, 0, 2, 16, 16),
                Direction.EAST, List.of(14, 0, 0, 16, 16, 16));
        JsonObject panelState = generatedJson("assets/omniresonance/blockstates/resonance_transfer_panel.json");
        for (Direction facing : Direction.values()) {
            String modelId = "omniresonance:block/resonance_transfer_panel_" + facing.getSerializedName();
            JsonObject variant =
                    panelState.getAsJsonObject("variants").getAsJsonObject("facing=" + facing.getSerializedName());
            assertEquals(modelId, variant.get("model").getAsString());
            assertFalse(variant.has("x"));
            assertFalse(variant.has("y"));

            JsonObject model = generatedJson("assets/omniresonance/models/block/resonance_transfer_panel_"
                    + facing.getSerializedName()
                    + ".json");
            assertEquals(
                    Map.of(
                            "particle", "omniresonance:block/resonance_transfer_panel_edge",
                            "front", "omniresonance:block/resonance_transfer_node_front",
                            "back", "omniresonance:block/resonance_transfer_node_back",
                            "edge", "omniresonance:block/resonance_transfer_panel_edge"),
                    stringMap(model.getAsJsonObject("textures")));
            JsonObject element = model.getAsJsonArray("elements").get(0).getAsJsonObject();
            List<Integer> from = integerArray(element.getAsJsonArray("from"));
            List<Integer> to = integerArray(element.getAsJsonArray("to"));
            assertEquals(bounds.get(facing).subList(0, 3), from);
            assertEquals(bounds.get(facing).subList(3, 6), to);

            JsonObject faces = element.getAsJsonObject("faces");
            Direction front = facing.getOpposite();
            assertFaceTexture(faces, front, "#front");
            assertFaceTexture(faces, facing, "#back");
            assertEquals(List.of(0, 0, 16, 16), effectiveFaceUv(faces, front, from, to));
            assertEquals(List.of(0, 0, 16, 16), effectiveFaceUv(faces, facing, from, to));
            for (Direction edge : Direction.values()) {
                if (edge != front && edge != facing) {
                    assertFaceTexture(faces, edge, "#edge");
                    assertFalse(effectiveFaceUv(faces, edge, from, to).equals(List.of(0, 0, 16, 16)));
                }
            }
        }
        assertEquals(
                "omniresonance:block/resonance_transfer_panel_south",
                generatedJson("assets/omniresonance/models/item/resonance_transfer_panel.json")
                        .get("parent")
                        .getAsString());
    }

    @Test
    void aeInterfaceInheritsVanillaHeldTransformsWithoutShrinkingThePlacedCube() throws IOException {
        var model = generatedJson("assets/omniresonance/models/block/ae_domain_interface.json");
        assertEquals("minecraft:block/block", optionalString(model, "parent"));
        var cube = model.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertEquals(List.of(0, 0, 0), integerArray(cube.getAsJsonArray("from")));
        assertEquals(List.of(16, 16, 16), integerArray(cube.getAsJsonArray("to")));
        assertEquals(
                "omniresonance:block/ae_domain_interface",
                generatedJson("assets/omniresonance/models/item/ae_domain_interface.json")
                        .get("parent")
                        .getAsString());
    }

    @Test
    void customBlockModelsInheritItemTransformsAndKeepPanelArtAligned() throws IOException {
        assertEquals(
                "minecraft:block/block",
                optionalString(
                        generatedJson("assets/omniresonance/models/block/resonance_transfer_node.json"), "parent"));
        Map<Direction, List<Integer>> expectedFrontAndBackRotations = Map.of(
                Direction.DOWN, List.of(180, 0),
                Direction.UP, List.of(180, 0),
                Direction.NORTH, List.of(0, 0),
                Direction.SOUTH, List.of(0, 0),
                Direction.WEST, List.of(0, 0),
                Direction.EAST, List.of(0, 0));
        for (Direction facing : Direction.values()) {
            JsonObject model = generatedJson("assets/omniresonance/models/block/resonance_transfer_panel_"
                    + facing.getSerializedName()
                    + ".json");
            assertEquals("minecraft:block/block", optionalString(model, "parent"));
            JsonObject faces =
                    model.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
            assertEquals(
                    expectedFrontAndBackRotations.get(facing),
                    List.of(faceRotation(faces, facing.getOpposite()), faceRotation(faces, facing)),
                    facing.getSerializedName());
        }
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

    private static Map<String, String> stringMap(JsonObject object) {
        return object.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().getAsString()));
    }

    private static String optionalString(JsonObject object, String property) {
        return object.has(property) ? object.get(property).getAsString() : null;
    }

    private static int faceRotation(JsonObject faces, Direction face) {
        JsonObject faceData = faces.getAsJsonObject(face.getSerializedName());
        return faceData.has("rotation") ? faceData.get("rotation").getAsInt() : 0;
    }

    private static void assertFaceTexture(JsonObject faces, Direction face, String expectedTexture) {
        assertEquals(
                expectedTexture,
                faces.getAsJsonObject(face.getSerializedName()).get("texture").getAsString());
    }

    private static List<Integer> effectiveFaceUv(
            JsonObject faces, Direction face, List<Integer> from, List<Integer> to) {
        JsonObject faceData = faces.getAsJsonObject(face.getSerializedName());
        if (faceData.has("uv")) {
            return integerArray(faceData.getAsJsonArray("uv"));
        }
        return switch (face) {
            case DOWN -> List.of(from.get(0), 16 - to.get(2), to.get(0), 16 - from.get(2));
            case UP -> List.of(from.get(0), from.get(2), to.get(0), to.get(2));
            case NORTH -> List.of(16 - to.get(0), 16 - to.get(1), 16 - from.get(0), 16 - from.get(1));
            case SOUTH -> List.of(from.get(0), 16 - to.get(1), to.get(0), 16 - from.get(1));
            case WEST -> List.of(from.get(2), 16 - to.get(1), to.get(2), 16 - from.get(1));
            case EAST -> List.of(16 - to.get(2), 16 - to.get(1), 16 - from.get(2), 16 - from.get(1));
        };
    }

    private static Direction rotateModelDirection(Direction direction, int rotationX, int rotationY) {
        int x = direction.getStepX();
        int y = direction.getStepY();
        int z = direction.getStepZ();
        for (int degrees = 0; degrees < rotationX; degrees += 90) {
            int previousY = y;
            y = z;
            z = -previousY;
        }
        for (int degrees = 0; degrees < rotationY; degrees += 90) {
            int previousX = x;
            x = -z;
            z = previousX;
        }
        for (Direction candidate : Direction.values()) {
            if (candidate.getStepX() == x && candidate.getStepY() == y && candidate.getStepZ() == z) {
                return candidate;
            }
        }
        throw new AssertionError("Rotated normal is not a cardinal direction");
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
