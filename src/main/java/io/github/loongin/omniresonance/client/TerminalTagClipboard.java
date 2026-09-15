// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/** One client-world structured candidate, replaced only after explicit copy and cleared on disconnect. */
final class TerminalTagClipboard {
    record Candidate(String resourceType, List<String> tags, String text) {
        Candidate {
            tags = List.copyOf(tags);
        }
    }

    private static @Nullable Candidate recent;

    private TerminalTagClipboard() {}

    static void copy(String type, List<String> tags) {
        copy(type, tags, text -> Minecraft.getInstance().keyboardHandler.setClipboard(text));
    }

    static void copy(String type, List<String> tags, java.util.function.Consumer<String> writer) {
        if (tags.isEmpty()) return;
        String text = String.join("\n", tags);
        writer.accept(text);
        recent = new Candidate(type, tags, text);
    }

    static @Nullable Candidate recent() {
        return recent;
    }

    static void clear() {
        recent = null;
    }
}
