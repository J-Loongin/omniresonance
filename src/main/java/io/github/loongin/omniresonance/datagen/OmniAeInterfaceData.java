// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

/** Reproducible optional interface resources without requiring AE2 in the data-generation process. */
final class OmniAeInterfaceData implements DataProvider {
    private final PackOutput output;

    OmniAeInterfaceData(PackOutput output) {
        this.output = output;
    }

    private static JsonObject object(String key, String value) {
        var o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static JsonArray vector(double x, double y, double z) {
        var a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        var tasks = new ArrayList<CompletableFuture<?>>();
        String name = "ae_domain_interface";
        var model = new JsonObject();
        var textures = object("particle", "omniresonance:block/resonance_transfer_node_top");
        textures.addProperty("case", "omniresonance:block/resonance_transfer_node_top");
        textures.addProperty("port", "minecraft:block/white_concrete");
        model.add("textures", textures);
        var elements = new JsonArray();
        var cube = new JsonObject();
        cube.add("from", vector(0, 0, 0));
        cube.add("to", vector(16, 16, 16));
        var faces = new JsonObject();
        for (String face : new String[] {"north", "south", "west", "east", "up", "down"})
            faces.add(face, object("texture", "#case"));
        cube.add("faces", faces);
        elements.add(cube);
        String[] directions = {"north", "south", "west", "east", "down", "up"};
        double[][] from = {{5, 5, -0.01}, {5, 5, 16.01}, {-0.01, 5, 5}, {16.01, 5, 5}, {5, -0.01, 5}, {5, 16.01, 5}};
        double[][] to = {
            {11, 11, -0.01}, {11, 11, 16.01}, {-0.01, 11, 11}, {16.01, 11, 11}, {11, -0.01, 11}, {11, 16.01, 11}
        };
        for (int i = 0; i < directions.length; i++) {
            var port = new JsonObject();
            port.add("from", vector(from[i][0], from[i][1], from[i][2]));
            port.add("to", vector(to[i][0], to[i][1], to[i][2]));
            var visible = new JsonObject();
            var face = object("texture", "#port");
            face.addProperty("tintindex", 0);
            visible.add(directions[i], face);
            port.add("faces", visible);
            elements.add(port);
        }
        model.add("elements", elements);
        tasks.add(save(cache, "assets/omniresonance/models/block/" + name + ".json", model));
        var variants = new JsonObject();
        variants.add("", object("model", "omniresonance:block/" + name));
        var states = new JsonObject();
        states.add("variants", variants);
        tasks.add(save(cache, "assets/omniresonance/blockstates/" + name + ".json", states));
        tasks.add(save(
                cache,
                "assets/omniresonance/models/item/" + name + ".json",
                object("parent", "omniresonance:block/" + name)));
        var loot = object("type", "minecraft:block");
        conditions(loot);
        var pools = new JsonArray();
        var pool = new JsonObject();
        pool.addProperty("rolls", 1);
        var entries = new JsonArray();
        var entry = object("type", "minecraft:item");
        entry.addProperty("name", "omniresonance:" + name);
        entries.add(entry);
        pool.add("entries", entries);
        var explosion = new JsonArray();
        explosion.add(object("condition", "minecraft:survives_explosion"));
        pool.add("conditions", explosion);
        pools.add(pool);
        loot.add("pools", pools);
        tasks.add(save(cache, "data/omniresonance/loot_table/blocks/" + name + ".json", loot));
        var recipe = object("type", "minecraft:crafting_shapeless");
        recipe.addProperty("category", "redstone");
        conditions(recipe);
        var ingredients = new JsonArray();
        ingredients.add(object("item", "omniresonance:resonance_transfer_node"));
        ingredients.add(object("item", "ae2:interface"));
        recipe.add("ingredients", ingredients);
        var result = object("id", "omniresonance:" + name);
        result.addProperty("count", 1);
        recipe.add("result", result);
        tasks.add(save(cache, "data/omniresonance/recipe/" + name + ".json", recipe));
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }

    static void conditions(JsonObject json) {
        var array = new JsonArray();
        var condition = object("type", "neoforge:mod_loaded");
        condition.addProperty("modid", "ae2");
        array.add(condition);
        json.add("neoforge:conditions", array);
    }

    private CompletableFuture<?> save(CachedOutput cache, String path, JsonObject json) {
        return DataProvider.saveStable(cache, json, output.getOutputFolder().resolve(path));
    }

    @Override
    public String getName() {
        return "Optional AE domain interface resources";
    }
}
