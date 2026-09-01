// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.bootstrap;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;

/** Build-time conversion of readable test structures using Minecraft's standard NBT codec. */
public final class TestStructureGenerator {
    private TestStructureGenerator() {}

    /**
     * Reads an SNBT file and writes compressed NBT to the second argument.
     * Runs in an isolated build JVM without live world state; parse and I/O failures propagate.
     */
    public static void main(String[] args) throws IOException, CommandSyntaxException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected input SNBT path and output NBT path");
        }
        CompoundTag structure = TagParser.parseTag(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        NbtIo.writeCompressed(structure, output);
    }
}
