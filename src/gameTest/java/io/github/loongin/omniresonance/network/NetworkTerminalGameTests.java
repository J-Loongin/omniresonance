// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.NetworkRuntimeRegistry;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.networking.NetworkTerminalRequest;
import io.github.loongin.omniresonance.networking.NetworkTerminalResponse;
import io.github.loongin.omniresonance.persistence.ManagedSavedDataNames;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Exercises production runtime handling with real server players and isolated standard SavedData. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkTerminalGameTests {
    private static final UUID OWNER = new UUID(10, 1);
    private static final UUID OTHER = new UUID(10, 2);
    private static final UUID VIEW = new UUID(20, 1);
    private static final UUID NEXT_VIEW = new UUID(20, 2);

    private NetworkTerminalGameTests() {}

    /** Open and close remain read-only; a repeated sequence cannot create twice or impersonate another owner. */
    @GameTest(template = "bootstrap")
    public static void explicitCreationUsesSenderAndConsumesSequence(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer player = player(helper, OWNER);
            ServerPlayer other = player(helper, OTHER);
            NetworkTerminalService service = fixture.runtime(state(1, 1, 2));
            NetworkTerminalResponse.Success opened =
                    success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)));
            helper.assertTrue(
                    opened.page().entries().isEmpty() && opened.page().preferred() == null,
                    "Open created or selected a network");
            helper.assertTrue(fixture.repository.findOwner(OWNER).isEmpty(), "Open created owner data");
            helper.assertTrue(
                    service.handle(player, new NetworkTerminalRequest.Close(VIEW, opened.sessionId())) == null,
                    "Close responded");
            fixture.storage.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(fileCount(fixture.path) == 0, "Open/skip wrote SavedData");
            opened = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)));
            UUID session = opened.sessionId();
            failure(
                    helper,
                    service.handle(other, new NetworkTerminalRequest.Create(VIEW, session, 1, "Stolen")),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(NEXT_VIEW, session, 1, "Wrong view")),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, OTHER, 1, "Wrong nonce")),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 2, "Skipped")),
                    NetworkTerminalResponse.Reason.STALE_REQUEST);
            NetworkTerminalRequest.Create create = new NetworkTerminalRequest.Create(VIEW, session, 1, " Main ");
            NetworkTerminalResponse.Success created = success(helper, service.handle(player, create));
            helper.assertTrue(
                    created.created() != null && created.created().ownerId().equals(OWNER),
                    "Creation trusted a foreign owner");
            helper.assertTrue(created.created().name().equals("Main"), "Name was not normalized");
            helper.assertTrue(
                    created.created().equals(created.page().preferred()), "First owned network is not preferred");
            failure(helper, service.handle(player, create), NetworkTerminalResponse.Reason.STALE_REQUEST);
            helper.assertTrue(
                    fixture.directory.ownedCount(OWNER) == 1 && fixture.directory.ownedCount(OTHER) == 0,
                    "Replay or impersonation committed");
            helper.assertTrue(fileCount(fixture.path) == 0, "Create synchronously saved files");
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 2, " ")),
                    NetworkTerminalResponse.Reason.INVALID_NAME);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 2, "Second")),
                    NetworkTerminalResponse.Reason.STALE_REQUEST);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 3, "MAIN")),
                    NetworkTerminalResponse.Reason.NAME_CONFLICT);
            success(helper, service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 4, "Second")));
            service.close();
            helper.succeed();
        }
    }

    /** Reopening, reconnecting, clone, logout and stop close only the intended actual player session. */
    @GameTest(template = "bootstrap")
    public static void registryLifecycleUsesActualPlayerIdentity(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            MinecraftServer server = helper.getLevel().getServer();
            AtomicLong factories = new AtomicLong();
            NetworkRuntimeRegistry registry = new NetworkRuntimeRegistry(new ServerConfig(), (actualServer, state) -> {
                factories.incrementAndGet();
                return fixture.runtime(state);
            });
            ServerPlayer oldPlayer = player(helper, OWNER);
            failure(
                    helper,
                    registry.handle(oldPlayer, new NetworkTerminalRequest.Open(VIEW)),
                    NetworkTerminalResponse.Reason.LOADING);
            registry.onServerStarted(new ServerStartedEvent(server));
            registry.onServerStarted(new ServerStartedEvent(server));
            helper.assertTrue(factories.get() == 1, "Runtime initialized twice for the same server session");
            NetworkTerminalResponse.Success first =
                    success(helper, registry.handle(oldPlayer, new NetworkTerminalRequest.Open(VIEW)));
            helper.assertTrue(
                    success(helper, registry.handle(oldPlayer, new NetworkTerminalRequest.Open(VIEW)))
                            .sessionId()
                            .equals(first.sessionId()),
                    "Same-view Open was not idempotent");
            NetworkTerminalResponse.Success reopened =
                    success(helper, registry.handle(oldPlayer, new NetworkTerminalRequest.Open(NEXT_VIEW)));
            helper.assertTrue(!reopened.sessionId().equals(first.sessionId()), "Reopening reused nonce");
            registry.handle(oldPlayer, new NetworkTerminalRequest.Close(VIEW, first.sessionId()));
            success(
                    helper,
                    registry.handle(
                            oldPlayer,
                            new NetworkTerminalRequest.Page(NEXT_VIEW, reopened.sessionId(), 1, null, false)));
            failure(
                    helper,
                    registry.handle(
                            oldPlayer, new NetworkTerminalRequest.Page(VIEW, first.sessionId(), 1, null, false)),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            ServerPlayer replacement = player(helper, OWNER);
            registry.onPlayerClone(new PlayerEvent.Clone(replacement, oldPlayer, true));
            failure(
                    helper,
                    registry.handle(
                            oldPlayer,
                            new NetworkTerminalRequest.Page(NEXT_VIEW, reopened.sessionId(), 1, null, false)),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            NetworkTerminalResponse.Success replacementOpen =
                    success(helper, registry.handle(replacement, new NetworkTerminalRequest.Open(VIEW)));
            registry.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(oldPlayer));
            registry.onPlayerClone(new PlayerEvent.Clone(replacement, oldPlayer, false));
            success(
                    helper,
                    registry.handle(
                            replacement,
                            new NetworkTerminalRequest.Page(VIEW, replacementOpen.sessionId(), 1, null, false)));
            registry.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(replacement));
            failure(
                    helper,
                    registry.handle(
                            replacement,
                            new NetworkTerminalRequest.Page(VIEW, replacementOpen.sessionId(), 2, null, false)),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            NetworkTerminalResponse.Success beforeStop =
                    success(helper, registry.handle(replacement, new NetworkTerminalRequest.Open(VIEW)));
            registry.onServerStopped(new ServerStoppedEvent(server));
            failure(
                    helper,
                    registry.handle(replacement, new NetworkTerminalRequest.Open(VIEW)),
                    NetworkTerminalResponse.Reason.LOADING);
            registry.onServerStarted(new ServerStartedEvent(server));
            failure(
                    helper,
                    registry.handle(
                            replacement, new NetworkTerminalRequest.Page(VIEW, beforeStop.sessionId(), 1, null, false)),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            registry.onServerStopped(new ServerStoppedEvent(server));
            helper.succeed();
        }
    }

    /** Revision/epoch checks preserve existing networks on quota reduction and isolate later worlds. */
    @GameTest(template = "bootstrap")
    public static void configurationOnlyAppliesNewerSameWorldSnapshots(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer player = player(helper, OWNER);
            NetworkTerminalService service = fixture.runtime(state(7, 1, 2));
            UUID session = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            success(helper, service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 1, "First")));
            service.applyConfiguration(state(7, 2, 0));
            service.applyConfiguration(state(7, 1, -1));
            service.applyConfiguration(state(8, 100, -1));
            service.applyConfiguration(new ServerConfig.State(7, 100, false, new ServerSettings(-1)));
            NetworkTerminalResponse.Success page = success(
                    helper, service.handle(player, new NetworkTerminalRequest.Page(VIEW, session, 2, null, false)));
            helper.assertTrue(
                    page.page().networksPerOwner() == 0 && page.page().ownedCount() == 1,
                    "Reload deleted records or accepted stale settings");
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 3, "Second")),
                    NetworkTerminalResponse.Reason.QUOTA_REACHED);
            service.close();
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Open(VIEW)),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            NetworkTerminalService nextWorld =
                    fixture.runtime(new ServerConfig.State(8, 0, true, ServerSettings.defaults()));
            nextWorld.applyConfiguration(state(7, 200, 0));
            NetworkTerminalResponse.Success fresh =
                    success(helper, nextWorld.handle(player, new NetworkTerminalRequest.Open(VIEW)));
            helper.assertTrue(
                    fresh.page().networksPerOwner() == ServerSettings.defaults().networksPerOwner(),
                    "Old world quota leaked");
            nextWorld.close();
            helper.succeed();
        }
    }

    /** Managed-only access never auto-selects another owner's network, and payloads stay bounded. */
    @GameTest(template = "bootstrap")
    public static void managedNetworksRequireSelectionAndUseBoundedPages(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            for (int i = 0; i < 130; i++) {
                NetworkMetadata metadata =
                        new NetworkMetadata(new UUID(0, i), OTHER, new ManagedName("Managed " + i), i, Set.of(OWNER));
                fixture.repository.createNetwork(metadata);
                fixture.directory.add(metadata);
            }
            ServerPlayer player = player(helper, OWNER);
            NetworkTerminalService service = fixture.runtime(state(1, 0, 2));
            NetworkTerminalResponse.Success first =
                    success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)));
            helper.assertTrue(
                    first.page().preferred() == null && first.page().ownedCount() == 0,
                    "Managed network became owned default");
            helper.assertTrue(
                    first.page().entries().size() == 128
                            && first.page().totalCount() == 130
                            && first.page().hasNext(),
                    "Page was not bounded");
            NetworkTerminalResponse.Success second = success(
                    helper,
                    service.handle(
                            player,
                            new NetworkTerminalRequest.Page(VIEW, first.sessionId(), 1, new UUID(0, 127), false)));
            helper.assertTrue(
                    second.page().entries().size() == 2
                            && !second.page().hasNext()
                            && second.page().hasPrevious(),
                    "Next page was incorrect");
            failure(
                    helper,
                    service.handle(
                            player,
                            new NetworkTerminalRequest.Page(VIEW, first.sessionId(), 2, new UUID(99, 99), false)),
                    NetworkTerminalResponse.Reason.INVALID_REQUEST);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Page(VIEW, first.sessionId(), 2, null, false)),
                    NetworkTerminalResponse.Reason.STALE_REQUEST);
            service.close();
            helper.succeed();
        }
    }

    /** Corrupt owner state and initialization errors fail closed without publishing partial runtime state. */
    @GameTest(template = "bootstrap")
    public static void unavailableDataDoesNotBecomeAnEmptyNetwork(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer player = player(helper, OWNER);
            Path occupied = fixture.path.resolve(ManagedSavedDataNames.owner(OWNER) + ".dat");
            Files.createDirectory(occupied);
            NetworkTerminalService service = fixture.runtime(state(1, 1, 2));
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Open(VIEW)),
                    NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
            helper.assertTrue(
                    fixture.directory.ownedCount(OWNER) == 0 && Files.isDirectory(occupied),
                    "Unreadable owner data was replaced");
            service.close();
            AtomicLong attempts = new AtomicLong();
            NetworkRuntimeRegistry registry = new NetworkRuntimeRegistry(new ServerConfig(), (server, state) -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("Test repository unavailable");
            });
            registry.onServerStarted(new ServerStartedEvent(helper.getLevel().getServer()));
            registry.onServerStarted(new ServerStartedEvent(helper.getLevel().getServer()));
            failure(
                    helper,
                    registry.handle(player, new NetworkTerminalRequest.Open(VIEW)),
                    NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
            helper.assertTrue(attempts.get() == 1, "Initialization retried while unavailable");
            registry.onServerStopped(new ServerStoppedEvent(helper.getLevel().getServer()));
            helper.succeed();
        }
    }

    private static ServerConfig.State state(long epoch, long revision, int quota) {
        return new ServerConfig.State(epoch, revision, true, new ServerSettings(quota));
    }

    /** Wrong-thread calls reject before touching Minecraft objects, directory data or active sessions. */
    @GameTest(template = "bootstrap")
    public static void runtimeRejectsOffThreadStateAccess(GameTestHelper helper) throws Exception {
        try (Fixture fixture = new Fixture(helper)) {
            ServerPlayer player = player(helper, OWNER);
            NetworkTerminalService service = fixture.runtime(state(1, 1, 2));
            try (var executor = Executors.newSingleThreadExecutor()) {
                List<Runnable> operations = List.of(
                        () -> service.handle(player, new NetworkTerminalRequest.Open(VIEW)),
                        () -> service.applyConfiguration(state(1, 2, 0)),
                        () -> service.closePlayer(player),
                        service::close);
                for (Runnable operation : operations) {
                    boolean rejected = false;
                    try {
                        executor.submit(operation).get();
                    } catch (ExecutionException exception) {
                        rejected = exception.getCause() instanceof IllegalStateException;
                    }
                    helper.assertTrue(rejected, "Runtime allowed state access off the server thread");
                }
            }
            helper.assertTrue(fixture.directory.ownedCount(OWNER) == 0, "Wrong-thread calls changed network data");
            service.close();
            helper.succeed();
        }
    }

    /** An unknown operation failure expires only its session; replay is rejected while other players remain usable. */
    @GameTest(template = "bootstrap")
    public static void internalCreationFailureStopsFurtherMutation(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            AtomicLong attempts = new AtomicLong();
            UnsupportedOperationException injected = new UnsupportedOperationException(
                    "Sensitive internal diagnostic", new IOException("Sensitive root cause"));
            NetworkCreationService brokenCreation =
                    new NetworkCreationService(fixture.repository, fixture.directory, () -> {
                        long attempt = attempts.incrementAndGet();
                        if (attempt == 1) {
                            throw injected;
                        }
                        return new UUID(30, attempt);
                    });
            NetworkTerminalService service = new NetworkTerminalService(
                    helper.getLevel().getServer(),
                    fixture.directory,
                    brokenCreation,
                    state(1, 1, 2),
                    () -> new UUID(40, fixture.sessionIds.incrementAndGet()));
            ServerPlayer player = player(helper, OWNER);
            ServerPlayer other = player(helper, OTHER);
            UUID session = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            UUID otherSession = success(helper, service.handle(other, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            NetworkTerminalRequest.Create failedRequest = new NetworkTerminalRequest.Create(VIEW, session, 1, "First");
            failure(helper, service.handle(player, failedRequest), NetworkTerminalResponse.Reason.INTERNAL_ERROR);
            success(helper, service.handle(other, new NetworkTerminalRequest.Page(VIEW, otherSession, 1, null, false)));
            success(helper, service.handle(other, new NetworkTerminalRequest.Create(VIEW, otherSession, 2, "Other")));
            failure(helper, service.handle(player, failedRequest), NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 2, "Retry")),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            helper.assertTrue(
                    attempts.get() == 2
                            && fixture.directory.ownedCount(OWNER) == 0
                            && fixture.directory.ownedCount(OTHER) == 1,
                    "Failed intent was retried or unrelated creation was lost");
            UUID freshSession = success(helper, service.handle(player, new NetworkTerminalRequest.Open(NEXT_VIEW)))
                    .sessionId();
            helper.assertTrue(!freshSession.equals(session), "Explicit reopening retained the invalidated session");
            success(
                    helper,
                    service.handle(
                            player, new NetworkTerminalRequest.Create(NEXT_VIEW, freshSession, 1, "New intent")));
            helper.assertTrue(
                    attempts.get() == 3 && fixture.directory.ownedCount(OWNER) == 1,
                    "New explicit intent remained globally blocked");
            service.close();
            helper.succeed();
        }
    }

    /** An owner read failure before creation leaves admitted sessions and unrelated players functional. */
    @GameTest(template = "bootstrap")
    public static void ownerReadFailureIsRequestLocal(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            NetworkTerminalService service = fixture.runtime(state(1, 1, 2));
            ServerPlayer player = player(helper, OWNER);
            ServerPlayer other = player(helper, OTHER);
            UUID session = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            UUID otherSession = success(helper, service.handle(other, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            Path unavailable = fixture.path.resolve(ManagedSavedDataNames.owner(OWNER) + ".dat");
            Files.createDirectory(unavailable);
            NetworkTerminalRequest.Create failed = new NetworkTerminalRequest.Create(VIEW, session, 1, "First");
            failure(helper, service.handle(player, failed), NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
            success(helper, service.handle(other, new NetworkTerminalRequest.Page(VIEW, otherSession, 1, null, false)));
            success(helper, service.handle(other, new NetworkTerminalRequest.Create(VIEW, otherSession, 2, "Other")));
            failure(helper, service.handle(player, failed), NetworkTerminalResponse.Reason.STALE_REQUEST);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Page(VIEW, session, 2, null, false)),
                    NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
            helper.assertTrue(
                    fixture.directory.ownedCount(OWNER) == 0
                            && fixture.directory.ownedCount(OTHER) == 1
                            && Files.isDirectory(unavailable),
                    "Owner read failure changed records or harmed another player");
            service.close();
            helper.succeed();
        }
    }

    /** Exhausted creation order is a proven pre-commit failure, not a global or session-level outage. */
    @GameTest(template = "bootstrap")
    public static void exhaustedCreationOrderLeavesSessionsUsable(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper)) {
            NetworkMetadata existing =
                    new NetworkMetadata(new UUID(30, 0), OWNER, new ManagedName("Existing"), Long.MAX_VALUE, Set.of());
            fixture.repository.createNetwork(existing);
            fixture.directory.add(existing);
            NetworkTerminalService service = fixture.runtime(state(1, 1, 2));
            ServerPlayer player = player(helper, OWNER);
            ServerPlayer other = player(helper, OTHER);
            UUID session = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            UUID otherSession = success(helper, service.handle(other, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            NetworkTerminalRequest.Create failed = new NetworkTerminalRequest.Create(VIEW, session, 1, "Overflow");
            failure(helper, service.handle(player, failed), NetworkTerminalResponse.Reason.INTERNAL_ERROR);
            success(helper, service.handle(player, new NetworkTerminalRequest.Page(VIEW, session, 2, null, false)));
            failure(helper, service.handle(player, failed), NetworkTerminalResponse.Reason.STALE_REQUEST);
            success(helper, service.handle(other, new NetworkTerminalRequest.Create(VIEW, otherSession, 1, "Other")));
            helper.assertTrue(
                    fixture.directory.ownedCount(OWNER) == 1
                            && fixture.repository.findOwner(OWNER).isEmpty()
                            && fixture.directory.ownedCount(OTHER) == 1,
                    "Ordering rejection modified data or invalidated unrelated sessions");
            service.close();
            helper.succeed();
        }
    }

    /** Every unrelated data failure retains a server-side throwable and identifying request context. */
    @GameTest(template = "bootstrap")
    public static void dataFailuresKeepDistinctServerDiagnostics(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper);
                CapturedErrors captured = new CapturedErrors(NetworkTerminalService.class)) {
            Files.createDirectory(fixture.path.resolve(ManagedSavedDataNames.owner(OWNER) + ".dat"));
            Files.createDirectory(fixture.path.resolve(ManagedSavedDataNames.owner(OTHER) + ".dat"));
            NetworkTerminalService service = fixture.runtime(state(1, 1, 2));
            failure(
                    helper,
                    service.handle(player(helper, OWNER), new NetworkTerminalRequest.Open(VIEW)),
                    NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
            failure(
                    helper,
                    service.handle(player(helper, OTHER), new NetworkTerminalRequest.Open(NEXT_VIEW)),
                    NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
            List<LogEvent> events = captured.events();
            helper.assertTrue(events.size() == 2, "A later unrelated data failure lost its diagnostic");
            for (int index = 0; index < events.size(); index++) {
                LogEvent event = events.get(index);
                helper.assertTrue(
                        event.getThrown() instanceof IllegalStateException
                                && event.getThrown().getStackTrace().length > 0,
                        "Data diagnostic lost the throwable stack");
                String context = event.getMessage().getFormattedMessage();
                helper.assertTrue(
                        context.contains((index == 0 ? OWNER : OTHER).toString())
                                && context.contains((index == 0 ? VIEW : NEXT_VIEW).toString())
                                && context.contains("Open"),
                        "Data diagnostic lost its player/view/operation context");
            }
            service.close();
            helper.succeed();
        }
    }

    /** Unexpected request failures retain the exact server exception and cause without placing them in the reply. */
    @GameTest(template = "bootstrap")
    public static void internalFailuresKeepThrowableAndCause(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper);
                CapturedErrors captured = new CapturedErrors(NetworkTerminalService.class)) {
            IOException cause = new IOException("Sensitive root diagnostic");
            UnsupportedOperationException injected = new UnsupportedOperationException("Sensitive diagnostic", cause);
            NetworkCreationService creation = new NetworkCreationService(fixture.repository, fixture.directory, () -> {
                throw injected;
            });
            NetworkTerminalService service = new NetworkTerminalService(
                    helper.getLevel().getServer(), fixture.directory, creation, state(1, 1, 2), () -> new UUID(40, 1));
            ServerPlayer player = player(helper, OWNER);
            UUID session = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            NetworkTerminalResponse response =
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 1, "First"));
            failure(helper, response, NetworkTerminalResponse.Reason.INTERNAL_ERROR);
            helper.assertTrue(
                    !response.toString().contains("Sensitive"), "Client failure exposed server diagnostic text");
            List<LogEvent> events = captured.events();
            helper.assertTrue(
                    events.size() == 1
                            && events.getFirst().getThrown() == injected
                            && events.getFirst().getThrown().getCause() == cause,
                    "Internal diagnostic lost its throwable or cause");
            String context = events.getFirst().getMessage().getFormattedMessage();
            helper.assertTrue(
                    context.contains(OWNER.toString())
                            && context.contains(VIEW.toString())
                            && context.contains(session.toString())
                            && context.contains("Create")
                            && context.contains("1"),
                    "Internal diagnostic lost its request identity");
            service.close();
            helper.succeed();
        }
    }

    /** Failed initialization preserves its complete cause chain in the server log but only a fixed client reason. */
    @GameTest(template = "bootstrap")
    public static void registryInitializationKeepsThrowableAndCause(GameTestHelper helper) {
        try (CapturedErrors captured = new CapturedErrors(NetworkRuntimeRegistry.class)) {
            IOException cause = new IOException("Sensitive initialization root diagnostic");
            IllegalStateException injected = new IllegalStateException("Sensitive initialization diagnostic", cause);
            NetworkRuntimeRegistry registry = new NetworkRuntimeRegistry(new ServerConfig(), (server, state) -> {
                throw injected;
            });
            MinecraftServer server = helper.getLevel().getServer();
            registry.onServerStarted(new ServerStartedEvent(server));
            NetworkTerminalResponse response =
                    registry.handle(player(helper, OWNER), new NetworkTerminalRequest.Open(VIEW));
            failure(helper, response, NetworkTerminalResponse.Reason.DATA_UNAVAILABLE);
            helper.assertTrue(
                    !response.toString().contains("Sensitive"), "Initialization failure exposed diagnostic text");
            List<LogEvent> events = captured.events();
            helper.assertTrue(
                    events.size() == 1
                            && events.getFirst().getThrown() == injected
                            && events.getFirst().getThrown().getCause() == cause,
                    "Initialization diagnostic lost its throwable or cause");
            registry.onServerStopped(new ServerStoppedEvent(server));
            helper.succeed();
        }
    }

    /** A real owner registration followed by failure is not mistaken for an unmodified precondition rejection. */
    @GameTest(template = "bootstrap")
    public static void commitTailFailureExpiresOnlyItsSession(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper);
                CapturedErrors captured = new CapturedErrors(NetworkTerminalService.class)) {
            AtomicLong ownerWrites = new AtomicLong();
            IOException rootCause = new IOException("Injected owner registration failure");
            IllegalStateException injected = new IllegalStateException("Owner registered before failure", rootCause);
            DimensionDataStorage failingStorage =
                    new DimensionDataStorage(
                            fixture.path.toFile(),
                            DataFixers.getDataFixer(),
                            helper.getLevel().registryAccess()) {
                        @Override
                        public void set(String name, SavedData data) {
                            super.set(name, data);
                            if (name.equals(ManagedSavedDataNames.owner(OWNER)) && ownerWrites.incrementAndGet() == 1) {
                                throw injected;
                            }
                        }
                    };
            SavedNetworkRepository repository = new SavedNetworkRepository(failingStorage, fixture.path);
            AtomicLong networkIds = new AtomicLong();
            NetworkCreationService creation = new NetworkCreationService(
                    repository, fixture.directory, () -> new UUID(30, networkIds.incrementAndGet()));
            NetworkTerminalService service = new NetworkTerminalService(
                    helper.getLevel().getServer(),
                    fixture.directory,
                    creation,
                    state(1, 1, 2),
                    () -> new UUID(40, fixture.sessionIds.incrementAndGet()));
            ServerPlayer player = player(helper, OWNER);
            ServerPlayer other = player(helper, OTHER);
            UUID session = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            UUID otherSession = success(helper, service.handle(other, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            NetworkTerminalRequest.Create failed = new NetworkTerminalRequest.Create(VIEW, session, 1, "First");
            failure(helper, service.handle(player, failed), NetworkTerminalResponse.Reason.INTERNAL_ERROR);
            helper.assertTrue(
                    repository.findOwner(OWNER).isPresent(), "Fault did not cross authoritative owner registration");
            failure(helper, service.handle(player, failed), NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 2, "Replay")),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            success(helper, service.handle(other, new NetworkTerminalRequest.Page(VIEW, otherSession, 1, null, false)));
            success(helper, service.handle(other, new NetworkTerminalRequest.Create(VIEW, otherSession, 2, "Other")));
            helper.assertTrue(
                    ownerWrites.get() == 1 && fixture.directory.ownedCount(OTHER) == 1,
                    "Unknown commit was retried or invalidated the other player");
            List<LogEvent> events = captured.events();
            helper.assertTrue(
                    events.size() == 1
                            && events.getFirst().getThrown() != null
                            && events.getFirst().getThrown().getCause() == injected
                            && injected.getCause() == rootCause,
                    "Commit boundary diagnostic lost the original cause chain");
            service.close();
            helper.succeed();
        }
    }

    /** The first authoritative network set may register then fail, requiring only that session to expire. */
    @GameTest(template = "bootstrap")
    public static void firstNetworkRegistrationFailureExpiresOnlyItsSession(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper);
                CapturedErrors captured = new CapturedErrors(NetworkTerminalService.class)) {
            UUID firstId = new UUID(30, 1);
            String firstShard = ManagedSavedDataNames.network(firstId);
            AtomicLong firstWrites = new AtomicLong();
            IOException rootCause = new IOException("Injected first network registration failure");
            IllegalStateException injected = new IllegalStateException("Network registered before failure", rootCause);
            DimensionDataStorage failingStorage =
                    new DimensionDataStorage(
                            fixture.path.toFile(),
                            DataFixers.getDataFixer(),
                            helper.getLevel().registryAccess()) {
                        @Override
                        public void set(String name, SavedData data) {
                            super.set(name, data);
                            if (name.equals(firstShard) && firstWrites.incrementAndGet() == 1) {
                                throw injected;
                            }
                        }
                    };
            SavedNetworkRepository repository = new SavedNetworkRepository(failingStorage, fixture.path);
            AtomicLong networkIds = new AtomicLong();
            NetworkCreationService creation = new NetworkCreationService(
                    repository, fixture.directory, () -> new UUID(30, networkIds.incrementAndGet()));
            NetworkTerminalService service = new NetworkTerminalService(
                    helper.getLevel().getServer(),
                    fixture.directory,
                    creation,
                    state(1, 1, 2),
                    () -> new UUID(40, fixture.sessionIds.incrementAndGet()));
            ServerPlayer player = player(helper, OWNER);
            ServerPlayer other = player(helper, OTHER);
            UUID session = success(helper, service.handle(player, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            UUID otherSession = success(helper, service.handle(other, new NetworkTerminalRequest.Open(VIEW)))
                    .sessionId();
            NetworkTerminalRequest.Create failed = new NetworkTerminalRequest.Create(VIEW, session, 1, "First");
            NetworkTerminalResponse response = service.handle(player, failed);
            NetworkSavedData registered = failingStorage.get(
                    new SavedData.Factory<>(
                            () -> {
                                throw new IllegalStateException("Implicit test network creation is forbidden");
                            },
                            (tag, registries) -> NetworkSavedData.load(firstId, tag)),
                    firstShard);
            helper.assertTrue(
                    registered != null && registered.metadata().id().equals(firstId) && registered.isDirty(),
                    "Fault did not follow real authoritative network registration");
            failure(helper, response, NetworkTerminalResponse.Reason.INTERNAL_ERROR);
            helper.assertTrue(
                    repository.findOwner(OWNER).isEmpty() && fixture.directory.ownedCount(OWNER) == 0,
                    "Failed first registration continued into owner/default/index mutation");
            failure(helper, service.handle(player, failed), NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            failure(
                    helper,
                    service.handle(player, new NetworkTerminalRequest.Create(VIEW, session, 2, "Replay")),
                    NetworkTerminalResponse.Reason.SESSION_EXPIRED);
            success(helper, service.handle(other, new NetworkTerminalRequest.Page(VIEW, otherSession, 1, null, false)));
            success(helper, service.handle(other, new NetworkTerminalRequest.Create(VIEW, otherSession, 2, "Other")));
            helper.assertTrue(
                    firstWrites.get() == 1 && networkIds.get() == 2 && fixture.directory.ownedCount(OTHER) == 1,
                    "Unknown first registration was retried or blocked the other player");
            List<LogEvent> events = captured.events();
            helper.assertTrue(
                    events.size() == 1
                            && events.getFirst().getThrown() != null
                            && events.getFirst().getThrown().getCause() == injected
                            && injected.getCause() == rootCause,
                    "Registration diagnostic lost its original cause chain");
            service.close();
            helper.succeed();
        }
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, "TerminalTest"));
    }

    private static NetworkTerminalResponse.Success success(GameTestHelper helper, NetworkTerminalResponse response) {
        helper.assertTrue(
                response instanceof NetworkTerminalResponse.Success, "Expected terminal success, got " + response);
        return (NetworkTerminalResponse.Success) response;
    }

    private static void failure(
            GameTestHelper helper, NetworkTerminalResponse response, NetworkTerminalResponse.Reason reason) {
        helper.assertTrue(
                response instanceof NetworkTerminalResponse.Failure error && error.reason() == reason,
                "Expected " + reason + ", got " + response);
    }

    private static long fileCount(Path path) throws IOException {
        try (var files = Files.list(path)) {
            return files.count();
        }
    }

    private static final class CapturedErrors extends AbstractAppender implements AutoCloseable {
        private final Logger logger;
        private final List<LogEvent> captured = new ArrayList<>();

        private CapturedErrors(Class<?> source) {
            super("TerminalFailureCapture", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
            logger = (Logger) LogManager.getLogger(source);
            start();
            logger.addAppender(this);
        }

        @Override
        public synchronized void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR)) {
                if (captured.size() == 32) {
                    throw new AssertionError("Unexpectedly unbounded terminal error logging");
                }
                captured.add(event.toImmutable());
            }
        }

        private synchronized List<LogEvent> events() {
            return List.copyOf(captured);
        }

        @Override
        public void close() {
            logger.removeAppender(this);
            stop();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        private final Path path;
        private final DimensionDataStorage storage;
        private final SavedNetworkRepository repository;
        private final NetworkDirectory directory;
        private final NetworkCreationService creation;
        private final AtomicLong sessionIds = new AtomicLong();

        private Fixture(GameTestHelper helper) throws IOException {
            this.helper = helper;
            path = Files.createTempDirectory("omniresonance-terminal-test-");
            storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            directory = new NetworkDirectory(repository.loadNetworks());
            AtomicLong networkIds = new AtomicLong();
            creation =
                    new NetworkCreationService(repository, directory, () -> new UUID(30, networkIds.incrementAndGet()));
        }

        private NetworkTerminalService runtime(ServerConfig.State state) {
            return new NetworkTerminalService(
                    helper.getLevel().getServer(),
                    directory,
                    creation,
                    state,
                    () -> new UUID(40, sessionIds.incrementAndGet()));
        }

        @Override
        public void close() throws IOException {
            IOUtilities.waitUntilIOWorkerComplete();
            try (var paths = Files.walk(path)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
