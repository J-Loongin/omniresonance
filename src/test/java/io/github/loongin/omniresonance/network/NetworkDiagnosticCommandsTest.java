// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

class NetworkDiagnosticCommandsTest {
    @Test
    void readCommandsUseTheShortNetworkPathWithoutLegacyAliases() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        NetworkDiagnosticCommands.register(dispatcher, server -> {
            throw new AssertionError("Registration must not access a runtime");
        });
        var root = dispatcher.getRoot().getChild("omniresonance");
        assertNull(root.getChild("admin"));
        var network = root.getChild("network");
        assertNotNull(network);
        assertNotNull(network.getChild("list"));
        assertNotNull(network.getChild("show"));
        assertNull(network.getChild("inspect"));
    }
}
