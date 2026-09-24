// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import com.mojang.brigadier.CommandDispatcher;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/** Two operator-only read paths. Resolves the current server runtime on each execution, never grants network roles. */
public final class NetworkDiagnosticCommands {
    private NetworkDiagnosticCommands() {}

    /** Registers public Brigadier commands; provider is evaluated only after level-four authorization at execution. */
    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Function<MinecraftServer, @Nullable NetworkDiagnosticsService> provider) {
        var list = Commands.literal("list")
                .executes(context -> list(context.getSource(), provider, null))
                .then(Commands.argument("owner_uuid", UuidArgument.uuid())
                        .executes(context ->
                                list(context.getSource(), provider, UuidArgument.getUuid(context, "owner_uuid"))));
        var show = Commands.literal("show")
                .then(Commands.argument("network_uuid", UuidArgument.uuid())
                        .executes(context ->
                                show(context.getSource(), provider, UuidArgument.getUuid(context, "network_uuid"))));
        dispatcher.register(Commands.literal("omniresonance")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("network").then(list).then(show)));
    }

    private static @Nullable NetworkDiagnosticsService service(
            CommandSourceStack source, Function<MinecraftServer, @Nullable NetworkDiagnosticsService> provider) {
        if (!source.hasPermission(4)) {
            source.sendFailure(text("denied"));
            return null;
        }
        var service = provider.apply(source.getServer());
        if (service == null) source.sendFailure(text("unavailable"));
        return service;
    }

    private static int list(
            CommandSourceStack source,
            Function<MinecraftServer, @Nullable NetworkDiagnosticsService> provider,
            @Nullable UUID owner) {
        var service = service(source, provider);
        if (service == null) return 0;
        var page = service.list(owner);
        source.sendSuccess(() -> text("list", page.entries().size(), page.total()), false);
        for (var network : page.entries())
            source.sendSuccess(
                    () -> text(
                            "entry",
                            network.name().value(),
                            network.id().toString(),
                            network.ownerId().toString()),
                    false);
        if (page.entries().size() < page.total()) source.sendSuccess(() -> text("truncated"), false);
        return page.entries().size();
    }

    private static int show(
            CommandSourceStack source,
            Function<MinecraftServer, @Nullable NetworkDiagnosticsService> provider,
            UUID network) {
        var service = service(source, provider);
        if (service == null) return 0;
        var value = service.inspect(network).orElse(null);
        if (value == null) {
            source.sendFailure(text("missing", network.toString()));
            return 0;
        }
        source.sendSuccess(
                () -> text("identity", value.network().toString(), value.owner().toString()), false);
        for (var line : NetworkDiagnosticsText.details(value)) source.sendSuccess(() -> line, false);
        return 1;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("omniresonance.diagnostics." + key, args);
    }
}
