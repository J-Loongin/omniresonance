// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import com.mojang.authlib.GameProfile;
import io.github.loongin.omniresonance.bootstrap.OmniResonanceMod;
import io.github.loongin.omniresonance.config.ServerConfig;
import io.github.loongin.omniresonance.config.ServerSettings;
import io.github.loongin.omniresonance.node.NetworkNodeDirectory;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.node.NodeMode;
import io.github.loongin.omniresonance.persistence.NetworkSavedData;
import io.github.loongin.omniresonance.persistence.SavedNetworkRepository;
import io.github.loongin.omniresonance.security.EditLockTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-player permission and shared-lock contracts for tunnel, channel and node topology management. */
@GameTestHolder(OmniResonanceMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkTopologyManagementGameTests {
    private static final UUID NETWORK = new UUID(330, 1);
    private static final UUID OWNER = new UUID(330, 2);
    private static final UUID ADMIN = new UUID(330, 3);
    private static final UUID STRANGER = new UUID(330, 4);
    private static final UUID OP = new UUID(330, 5);
    private static final UUID NODE_A = new UUID(331, 1);
    private static final UUID NODE_B = new UUID(331, 2);
    private static final UUID TUNNEL = new UUID(332, 1);
    private static final UUID TUNNEL_SECOND = new UUID(332, 2);
    private static final UUID CHANNEL = new UUID(333, 1);
    private static final UUID TARGET_CHANNEL = new UUID(335, 1);

    private NetworkTopologyManagementGameTests() {}

    @GameTest(template = "bootstrap")
    public static void retiredBuiltinCannotBeNewlySelectedButExistingDomainConfigurationRemainsEditable(
            GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, ServerSettings.defaults())) {
            var previous = f.data.findNode(NODE_A).orElseThrow();
            var node = f.data.setNodeMode(NODE_A, previous.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            f.nodes.update(
                    new NetworkNodeDirectory.Entry(NETWORK, previous), new NetworkNodeDirectory.Entry(NETWORK, node));
            var owner = player(helper, OWNER);
            var edit = f.service.acquireNode(owner, NETWORK, NODE_A);
            var preset = io.github.loongin.omniresonance.filter.BuiltInPresets.ENTRIES
                    .getFirst()
                    .preset()
                    .id();
            var policy = new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Output(
                    1,
                    io.github.loongin.omniresonance.transfer.ResourceScope.all(),
                    io.github.loongin.omniresonance.transfer.RedstoneCondition.IGNORE,
                    preset,
                    io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                    java.util.Map.of(),
                    0);
            var intent = io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(
                    new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(policy, java.util.Map.of()));
            rejected(
                    helper,
                    NetworkTopologyService.Reason.UNAVAILABLE,
                    () -> f.service.saveDomainConfiguration(owner, edit, intent, WorkingFaces.explicit(1), true));
            f.service.cancel(owner, edit);
            var restored = f.data.saveDomainConfiguration(
                    NODE_A,
                    node.revision(),
                    new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(policy, java.util.Map.of()),
                    WorkingFaces.explicit(1),
                    true);
            f.nodes.update(
                    new NetworkNodeDirectory.Entry(NETWORK, node), new NetworkNodeDirectory.Entry(NETWORK, restored));
            var retainedEdit = f.service.acquireNode(owner, NETWORK, NODE_A);
            f.service.saveDomainConfiguration(owner, retainedEdit, intent, WorkingFaces.explicit(1), false);
            helper.assertTrue(
                    f.data.domainConfiguration(NODE_A)
                            .orElseThrow()
                            .policy()
                            .filterPresetId()
                            .equals(preset),
                    "Domain save lost the built-in preset reference");
            helper.assertTrue(
                    f.repository.findOwner(OWNER).isEmpty(), "Builtin domain output created owner preset data");
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void fullDomainSaveUsesLeaseAndCannotSelectForeignPreset(GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, ServerSettings.defaults())) {
            NetworkNodeRecord previous = f.data.findNode(NODE_A).orElseThrow();
            NetworkNodeRecord domainNode = f.data.setNodeMode(NODE_A, previous.revision(), NodeMode.DOMAIN, true)
                    .orElseThrow();
            f.nodes.update(
                    new NetworkNodeDirectory.Entry(NETWORK, previous),
                    new NetworkNodeDirectory.Entry(NETWORK, domainNode));
            ServerPlayer owner = player(helper, OWNER);
            var edit = f.service.acquireNode(owner, NETWORK, NODE_A);
            var draft = f.service.validateDomainEdit(owner, edit);
            helper.assertTrue(
                    !draft.configured() && draft.workingFaces().equals(WorkingFaces.explicit(0)),
                    "Fresh block domain must need a full save and explicit faces");
            var invalid = new io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.Input(
                    1,
                    io.github.loongin.omniresonance.transfer.ResourceScope.all(),
                    io.github.loongin.omniresonance.transfer.RedstoneCondition.IGNORE,
                    new UUID(555, 1),
                    io.github.loongin.omniresonance.filter.FilterMode.WHITELIST,
                    java.util.Map.of(),
                    0);
            var intent = io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(
                    new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(invalid, java.util.Map.of()));
            f.data.setDirty(false);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.UNAVAILABLE,
                    () -> f.service.saveDomainConfiguration(owner, edit, intent, WorkingFaces.explicit(1), false));
            helper.assertTrue(
                    !f.data.isDirty() && f.data.domainConfiguration(NODE_A).isEmpty(),
                    "Rejected domain save mutated authority");
            var good = io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(draft.storedPolicy());
            f.service.saveDomainConfiguration(owner, edit, good, WorkingFaces.explicit(48), false);
            var saved = f.data.domainConfiguration(NODE_A).orElseThrow();
            helper.assertTrue(
                    saved.configured() && saved.workingFaces().equals(WorkingFaces.explicit(48)),
                    "Full domain save was not published");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCK_EXPIRED,
                    () -> f.service.saveDomainConfiguration(owner, edit, good, WorkingFaces.explicit(48), false));
        }
        helper.succeed();
    }

    @GameTest(template = "bootstrap")
    public static void workingFaceDraftSaveRevalidatesAuthority(GameTestHelper helper) throws IOException {
        try (Fixture f = new Fixture(helper, ServerSettings.defaults())) {
            f.seedTunnelAndChannel();
            ServerPlayer owner = player(helper, OWNER);
            DirectNodeBinding draft = f.service.inspectDirectBinding(owner, NETWORK, NODE_A, CHANNEL);
            helper.assertTrue(draft.workingFaces().equals(WorkingFaces.explicit(0)), "New block draft must be empty");
            var edit = f.service.acquireNode(owner, NETWORK, NODE_A);
            long revision = f.data.topologyRevision();
            f.service.saveDirectBinding(
                    owner,
                    edit,
                    CHANNEL,
                    io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(draft.storedPolicy()),
                    WorkingFaces.explicit(48),
                    false);
            helper.assertTrue(f.data.topologyRevision() == revision + 1, "Face save did not revise topology");
            helper.assertTrue(
                    f.service
                            .inspectDirectBinding(owner, NETWORK, NODE_A, CHANNEL)
                            .workingFaces()
                            .equals(WorkingFaces.explicit(48)),
                    "Saved faces were not returned");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCK_EXPIRED,
                    () -> f.service.saveDirectBinding(
                            owner,
                            edit,
                            CHANNEL,
                            io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(
                                    draft.storedPolicy()),
                            WorkingFaces.explicit(0),
                            false));
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NO_ACCESS,
                    () -> f.service.inspectDirectBinding(player(helper, STRANGER), NETWORK, NODE_A, CHANNEL));
            helper.assertTrue(
                    f.data.findDirectBinding(NODE_A, CHANNEL)
                            .orElseThrow()
                            .workingFaces()
                            .equals(WorkingFaces.explicit(48)),
                    "Rejected edit mutated faces");
            var stale = f.service.acquireNode(owner, NETWORK, NODE_A);
            var old = f.nodes.byId(NODE_A).entry().orElseThrow();
            var updated = f.data.setDirectBinding(
                    NODE_A,
                    old.record().revision(),
                    CHANNEL,
                    draft.storedPolicy(),
                    WorkingFaces.explicit(0),
                    false,
                    -1);
            f.nodes.update(old, new NetworkNodeDirectory.Entry(NETWORK, updated));
            rejected(
                    helper,
                    NetworkTopologyService.Reason.STALE_REVISION,
                    () -> f.service.saveDirectBinding(
                            owner,
                            stale,
                            CHANNEL,
                            io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(
                                    draft.storedPolicy()),
                            WorkingFaces.explicit(48),
                            false));
            old = f.nodes.byId(NODE_B).entry().orElseThrow();
            updated = f.data.updateNodePhysicalSnapshot(NODE_B, old.record().position(), NodeForm.PANEL, Direction.WEST)
                    .orElseThrow();
            f.nodes.update(old, new NetworkNodeDirectory.Entry(NETWORK, updated));
            helper.assertTrue(
                    f.service
                            .inspectDirectBinding(owner, NETWORK, NODE_B, CHANNEL)
                            .workingFaces()
                            .attached(),
                    "Panel draft lost fixed attachment");
            var panelEdit = f.service.acquireNode(owner, NETWORK, NODE_B);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.UNAVAILABLE,
                    () -> f.service.saveDirectBinding(
                            owner,
                            panelEdit,
                            CHANNEL,
                            io.github.loongin.omniresonance.transfer.ResourcePolicyEdit.fromStored(
                                    draft.storedPolicy()),
                            WorkingFaces.explicit(48),
                            false));
            helper.assertTrue(f.data.findDirectBinding(NODE_B, CHANNEL).isEmpty(), "Forged panel created binding");
            f.service.cancel(owner, panelEdit);
        }
        helper.succeed();
    }

    /** Parent collection locks serialize creation while roles and quotas are revalidated on commit. */
    @GameTest(template = "bootstrap")
    public static void collectionEditsEnforceRolesLocksAndQuota(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, new ServerSettings(32, 1, 1, 1))) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            ServerPlayer stranger = player(helper, STRANGER);
            ServerPlayer operator = operator(helper);
            helper.assertTrue(operator.hasPermissions(4), "OP fixture lacks permission level");

            NetworkTopologyService.Edit ownerEdit = fixture.service.acquireTunnelCollection(owner, NETWORK);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnelCollection(administrator, NETWORK));
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NO_ACCESS,
                    () -> fixture.service.acquireTunnelCollection(stranger, NETWORK));
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NO_ACCESS,
                    () -> fixture.service.acquireTunnelCollection(operator, NETWORK));

            NetworkSavedData.TunnelCreation creation = fixture.service.createTunnel(
                    owner, ownerEdit, new ManagedName("Main"), new ManagedName("Channel 1"));
            NetworkTunnelRecord tunnel = creation.tunnel();
            helper.assertTrue(tunnel.tunnelNumber() == 1 && tunnel.enabled(), "Wrong first tunnel");
            NetworkTopologyService.Edit quotaEdit = fixture.service.acquireTunnelCollection(administrator, NETWORK);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.QUOTA_REACHED,
                    () -> fixture.service.createTunnel(
                            administrator, quotaEdit, new ManagedName("Second"), new ManagedName("Channel 1")));
            helper.assertTrue(fixture.data.lastTunnelNumber() == 1, "Quota rejection consumed a tunnel number");
            fixture.service.cancel(administrator, quotaEdit);

            helper.assertTrue(creation.initialChannel().channelNumber() == 1, "Wrong mandatory initial channel number");
            helper.succeed();
        }
    }

    /** Zero channel quota keeps the mandatory first channel while blocking extras and final-channel deletion. */
    @GameTest(template = "bootstrap")
    public static void mandatoryChannelSurvivesZeroQuotaAndDeletion(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, new ServerSettings(32, 4, 0, 1))) {
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            ServerPlayer stranger = player(helper, STRANGER);

            helper.assertTrue(
                    fixture.service
                            .suggestedTunnelName(owner, NETWORK, new ManagedNamePrefix("Tunnel"))
                            .value()
                            .equals("Tunnel 1"),
                    "Wrong smallest tunnel suggestion");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NO_ACCESS,
                    () -> fixture.service.suggestedTunnelName(stranger, NETWORK, new ManagedNamePrefix("Tunnel")));
            NetworkTopologyService.Edit tunnelEdit = fixture.service.acquireTunnelCollection(owner, NETWORK);
            NetworkSavedData.TunnelCreation creation = fixture.service.createTunnel(
                    owner, tunnelEdit, new ManagedName("Tunnel 1"), new ManagedName("Channel 1"));
            helper.assertTrue(
                    fixture.data.channelCount(creation.tunnel().tunnelId()) == 1,
                    "Mandatory channel was not created under zero quota");
            helper.assertTrue(
                    fixture.service
                            .suggestedChannelName(
                                    administrator,
                                    NETWORK,
                                    creation.tunnel().tunnelId(),
                                    new ManagedNamePrefix("Channel"))
                            .value()
                            .equals("Channel 2"),
                    "Wrong smallest channel suggestion");

            NetworkTopologyService.Edit channelEdit = fixture.service.acquireChannelCollection(
                    owner, NETWORK, creation.tunnel().tunnelId());
            rejected(
                    helper,
                    NetworkTopologyService.Reason.QUOTA_REACHED,
                    () -> fixture.service.createChannel(owner, channelEdit, new ManagedName("Extra")));
            fixture.service.cancel(owner, channelEdit);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LAST_CHANNEL,
                    () -> fixture.service.beginChannelDeletion(
                            owner, NETWORK, creation.initialChannel().channelId()));
            helper.assertTrue(
                    fixture.data
                            .findChannel(creation.initialChannel().channelId())
                            .isPresent(),
                    "Last-channel rejection changed authority");
            helper.succeed();
        }
    }

    /** Edit leases expire at 200 gt, heartbeat extends them, and exact object locks do not block unrelated objects. */
    @GameTest(template = "bootstrap")
    public static void objectLocksHeartbeatAndExpireAtExactTicks(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelAndChannel();
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);

            NetworkTopologyService.Edit tunnelEdit = fixture.service.acquireTunnel(owner, NETWORK, TUNNEL);
            NetworkTopologyService.Edit channelEdit = fixture.service.acquireChannel(owner, NETWORK, CHANNEL);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL));
            fixture.service.cancel(owner, channelEdit);
            for (int tick = 0; tick < 199; tick++) {
                fixture.service.tick();
            }
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL));
            fixture.service.tick();
            NetworkTopologyService.Edit replacement = fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL);
            fixture.service.cancel(administrator, replacement);

            NetworkTopologyService.Edit heartbeat = fixture.service.acquireTunnel(owner, NETWORK, TUNNEL);
            for (int tick = 0; tick < 100; tick++) {
                fixture.service.tick();
            }
            fixture.service.heartbeat(owner, heartbeat);
            for (int tick = 0; tick < 100; tick++) {
                fixture.service.tick();
            }
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL));
            fixture.service.cancel(owner, heartbeat);
            helper.succeed();
        }
    }

    /** Case-only tunnel renames release locks while another object's folded name remains reserved. */
    @GameTest(template = "bootstrap")
    public static void caseOnlyTunnelRenamePreservesIdentityAndRejectsConflicts(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.data.createTunnel(TUNNEL, new ManagedName("Alpha"), CHANNEL, new ManagedName("Alpha"), -1);
            fixture.data.createTunnel(
                    TUNNEL_SECOND, new ManagedName("Beta"), TARGET_CHANNEL, new ManagedName("Other"), -1);
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            long revision = fixture.data.findTunnel(TUNNEL).orElseThrow().revision();
            NetworkTunnelRecord renamed = fixture.service.renameTunnel(
                    owner, fixture.service.acquireTunnel(owner, NETWORK, TUNNEL), new ManagedName("ALPHA"));
            helper.assertTrue(
                    renamed.name().value().equals("ALPHA") && renamed.revision() == revision + 1,
                    "Case-only tunnel rename lost casing or consumed the wrong revision");
            helper.assertTrue(
                    fixture.data.findTunnel(TUNNEL).orElseThrow().equals(renamed),
                    "Case-only tunnel rename lost UUID access");
            NetworkTopologyService.Edit next = fixture.service.acquireTunnel(administrator, NETWORK, TUNNEL);
            fixture.data.setDirty(false);
            fixture.service.renameTunnel(administrator, next, new ManagedName("ALPHA"));
            helper.assertTrue(
                    !fixture.data.isDirty()
                            && fixture.data.findTunnel(TUNNEL).orElseThrow().equals(renamed),
                    "Exact same-name save changed the tunnel");
            NetworkTopologyService.Edit conflict = fixture.service.acquireTunnel(owner, NETWORK, TUNNEL);
            long topologyRevision = fixture.data.topologyRevision();
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NAME_CONFLICT,
                    () -> fixture.service.renameTunnel(owner, conflict, new ManagedName("BETA")));
            helper.assertTrue(
                    !fixture.data.isDirty()
                            && fixture.data.topologyRevision() == topologyRevision
                            && fixture.data.findTunnel(TUNNEL).orElseThrow().equals(renamed),
                    "Rejected folded-name conflict changed authority");
            fixture.service.cancel(owner, conflict);
            helper.succeed();
        }
    }

    /** Case-only channel renames release locks while another object's folded name remains reserved. */
    @GameTest(template = "bootstrap")
    public static void caseOnlyChannelRenamePreservesIdentityAndRejectsConflicts(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.data.createTunnel(TUNNEL, new ManagedName("Alpha"), CHANNEL, new ManagedName("Alpha"), -1);
            fixture.data.createTunnel(
                    TUNNEL_SECOND, new ManagedName("Beta"), TARGET_CHANNEL, new ManagedName("Other"), -1);
            fixture.data.createChannel(
                    TUNNEL,
                    fixture.data.findTunnel(TUNNEL).orElseThrow().revision(),
                    new UUID(333, 2),
                    new ManagedName("Beta"),
                    -1);
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            long revision = fixture.data.findChannel(CHANNEL).orElseThrow().revision();
            NetworkChannelRecord renamed = fixture.service.renameChannel(
                    owner, fixture.service.acquireChannel(owner, NETWORK, CHANNEL), new ManagedName("ALPHA"));
            helper.assertTrue(
                    renamed.name().value().equals("ALPHA") && renamed.revision() == revision + 1,
                    "Case-only channel rename lost casing or consumed the wrong revision");
            helper.assertTrue(
                    fixture.data.findChannel(CHANNEL).orElseThrow().equals(renamed),
                    "Case-only channel rename lost UUID access");
            NetworkTopologyService.Edit next = fixture.service.acquireChannel(administrator, NETWORK, CHANNEL);
            fixture.data.setDirty(false);
            fixture.service.renameChannel(administrator, next, new ManagedName("ALPHA"));
            helper.assertTrue(
                    !fixture.data.isDirty()
                            && fixture.data.findChannel(CHANNEL).orElseThrow().equals(renamed),
                    "Exact same-name save changed the channel");
            NetworkTopologyService.Edit conflict = fixture.service.acquireChannel(owner, NETWORK, CHANNEL);
            long topologyRevision = fixture.data.topologyRevision();
            rejected(
                    helper,
                    NetworkTopologyService.Reason.NAME_CONFLICT,
                    () -> fixture.service.renameChannel(owner, conflict, new ManagedName("BETA")));
            helper.assertTrue(
                    !fixture.data.isDirty()
                            && fixture.data.topologyRevision() == topologyRevision
                            && fixture.data.findChannel(CHANNEL).orElseThrow().equals(renamed),
                    "Rejected folded-name conflict changed authority");
            fixture.service.cancel(owner, conflict);
            helper.succeed();
        }
    }

    /** Channel cascade refuses a live affected-node edit and later updates SavedData plus the derived directory. */
    @GameTest(template = "bootstrap")
    public static void channelDeletionChecksAffectedNodeLocksAndCommitsAtomically(GameTestHelper helper)
            throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelAndChannel();
            fixture.seedDirectBindings();
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);

            NetworkTopologyService.Edit nodeEdit = fixture.service.acquireNode(owner, NETWORK, NODE_A);
            NetworkTopologyService.DeletionEdit deletion =
                    fixture.service.beginChannelDeletion(administrator, NETWORK, CHANNEL);
            helper.assertTrue(deletion.impact().bindingCount() == 2, "Wrong deletion impact");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.confirmChannelDeletion(administrator, deletion));
            helper.assertTrue(fixture.data.findChannel(CHANNEL).isPresent(), "Locked delete removed channel");
            fixture.service.cancel(owner, nodeEdit);

            List<NetworkNodeRecord> changed = fixture.service.confirmChannelDeletion(administrator, deletion);
            helper.assertTrue(changed.size() == 2, "Cascade did not update both nodes");
            helper.assertTrue(fixture.data.findChannel(CHANNEL).isEmpty(), "Channel survived confirmed delete");
            for (NetworkNodeRecord node : changed) {
                NetworkNodeDirectory.Lookup lookup = fixture.nodes.byId(node.nodeId());
                helper.assertTrue(
                        lookup.status() == NetworkNodeDirectory.Status.UNIQUE
                                && lookup.entry().orElseThrow().record().equals(node),
                        "Derived node directory did not receive cascade revision");
            }
            helper.succeed();
        }
    }

    /** A healthy tunnel cascade still publishes every changed node after preflight and releases the edit. */
    @GameTest(template = "bootstrap")
    public static void healthyTunnelDeletionPublishesEveryAffectedNode(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelAndChannel();
            fixture.seedDirectBindings();
            ServerPlayer owner = player(helper, OWNER);
            NetworkTopologyService.DeletionEdit deletion = fixture.service.beginTunnelDeletion(owner, NETWORK, TUNNEL);
            long topologyRevision = fixture.data.topologyRevision();
            List<NetworkNodeRecord> changed = fixture.service.confirmTunnelDeletion(owner, deletion);
            helper.assertTrue(
                    changed.size() == 2
                            && fixture.data.findTunnel(TUNNEL).isEmpty()
                            && fixture.data.findChannel(CHANNEL).isEmpty()
                            && fixture.data.topologyRevision() == topologyRevision + 1,
                    "Healthy tunnel deletion did not commit the complete cascade");
            for (NetworkNodeRecord node : changed) {
                helper.assertTrue(
                        fixture.data.directBindings(node.nodeId()).isEmpty()
                                && fixture.nodes
                                        .byId(node.nodeId())
                                        .entry()
                                        .orElseThrow()
                                        .record()
                                        .equals(node),
                        "Healthy tunnel deletion left bindings or stale directory authority");
            }
            helper.assertTrue(
                    !fixture.locks.isHeld(deletion.edit().token(), OWNER, 0),
                    "Successful tunnel deletion retained its edit lock");
            helper.succeed();
        }
    }

    /** A startup-conflicted affected node rejects channel cascade before any authoritative mutation. */
    @GameTest(template = "bootstrap")
    public static void conflictedNodeRejectsChannelDeletionBeforeMutation(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults(), true)) {
            ServerPlayer owner = player(helper, OWNER);
            NetworkTopologyService.DeletionEdit deletion =
                    fixture.service.beginChannelDeletion(owner, NETWORK, CHANNEL);
            CompoundTag before =
                    fixture.data.save(new CompoundTag(), helper.getLevel().registryAccess());
            long topologyRevision = fixture.data.topologyRevision();
            NetworkNodeDirectory.Entry healthy =
                    fixture.nodes.byId(NODE_A).entry().orElseThrow();
            List<NetworkNodeRecord> nodes = fixture.data.nodes();
            List<DirectNodeBinding> bindings = fixture.data.directBindings(NODE_A);
            helper.assertTrue(
                    fixture.nodes.byId(NODE_B).status() == NetworkNodeDirectory.Status.CONFLICTED,
                    "Fixture did not retain a startup-conflicted affected node");
            fixture.data.setDirty(false);
            RuntimeException failure = null;
            try {
                fixture.service.confirmChannelDeletion(owner, deletion);
            } catch (RuntimeException rejected) {
                failure = rejected;
            }
            helper.assertTrue(
                    before.equals(fixture.data.save(
                            new CompoundTag(), helper.getLevel().registryAccess())),
                    "Conflicted node cascade changed network NBT before rejection");
            helper.assertTrue(
                    failure instanceof NetworkTopologyService.Rejected
                            && ((NetworkTopologyService.Rejected) failure).reason()
                                    == NetworkTopologyService.Reason.UNAVAILABLE,
                    "Uncertain cascade did not reject with UNAVAILABLE");
            helper.assertTrue(
                    !fixture.data.isDirty()
                            && fixture.data.topologyRevision() == topologyRevision
                            && fixture.data.nodes().equals(nodes)
                            && fixture.data.directBindings(NODE_A).equals(bindings),
                    "Rejected cascade changed revisions, nodes, bindings or dirty state");
            helper.assertTrue(
                    fixture.nodes.byId(NODE_A).entry().orElseThrow().equals(healthy),
                    "Rejected cascade changed healthy directory authority");
            fixture.service.cancel(owner, deletion.edit());
            NetworkTopologyService.Edit healthyEdit = fixture.service.acquireNode(owner, NETWORK, NODE_A);
            fixture.service.cancel(owner, healthyEdit);
            helper.succeed();
        }
    }

    /** A startup-conflicted affected node rejects tunnel cascade before any authoritative mutation. */
    @GameTest(template = "bootstrap")
    public static void conflictedNodeRejectsTunnelDeletionBeforeMutation(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults(), true)) {
            ServerPlayer owner = player(helper, OWNER);
            NetworkTopologyService.DeletionEdit deletion = fixture.service.beginTunnelDeletion(owner, NETWORK, TUNNEL);
            CompoundTag before =
                    fixture.data.save(new CompoundTag(), helper.getLevel().registryAccess());
            long topologyRevision = fixture.data.topologyRevision();
            NetworkNodeDirectory.Entry healthy =
                    fixture.nodes.byId(NODE_A).entry().orElseThrow();
            List<NetworkNodeRecord> nodes = fixture.data.nodes();
            List<DirectNodeBinding> bindings = fixture.data.directBindings(NODE_A);
            helper.assertTrue(
                    fixture.nodes.byId(NODE_B).status() == NetworkNodeDirectory.Status.CONFLICTED,
                    "Fixture did not retain a startup-conflicted affected node");
            fixture.data.setDirty(false);
            RuntimeException failure = null;
            try {
                fixture.service.confirmTunnelDeletion(owner, deletion);
            } catch (RuntimeException rejected) {
                failure = rejected;
            }
            helper.assertTrue(
                    before.equals(fixture.data.save(
                            new CompoundTag(), helper.getLevel().registryAccess())),
                    "Conflicted node cascade changed network NBT before rejection");
            helper.assertTrue(
                    failure instanceof NetworkTopologyService.Rejected
                            && ((NetworkTopologyService.Rejected) failure).reason()
                                    == NetworkTopologyService.Reason.UNAVAILABLE,
                    "Uncertain cascade did not reject with UNAVAILABLE");
            helper.assertTrue(
                    !fixture.data.isDirty()
                            && fixture.data.topologyRevision() == topologyRevision
                            && fixture.data.nodes().equals(nodes)
                            && fixture.data.directBindings(NODE_A).equals(bindings),
                    "Rejected cascade changed revisions, nodes, bindings or dirty state");
            helper.assertTrue(
                    fixture.nodes.byId(NODE_A).entry().orElseThrow().equals(healthy),
                    "Rejected cascade changed healthy directory authority");
            fixture.service.cancel(owner, deletion.edit());
            NetworkTopologyService.Edit healthyEdit = fixture.service.acquireNode(owner, NETWORK, NODE_A);
            fixture.service.cancel(owner, healthyEdit);
            helper.succeed();
        }
    }

    /** A direct tunnel switch owns the node lock and commits all old bindings under one revision. */
    @GameTest(template = "bootstrap")
    public static void directTunnelSwitchLocksPreviewsAndCommitsAtomically(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelSwitchBindings();
            fixture.data.setDirty(false);
            ServerPlayer owner = player(helper, OWNER);
            ServerPlayer administrator = player(helper, ADMIN);
            NetworkNodeRecord before = fixture.data.findNode(NODE_A).orElseThrow();
            long topologyRevision = fixture.data.topologyRevision();

            NetworkTopologyService.Edit ordinaryBinding = fixture.service.acquireNode(owner, NETWORK, NODE_A);
            rejected(
                    helper,
                    NetworkTopologyService.Reason.TUNNEL_SWITCH_REQUIRED,
                    () -> fixture.service.setDirectBinding(
                            owner, ordinaryBinding, TARGET_CHANNEL, TransferDirection.OUTPUT, false));
            fixture.service.cancel(owner, ordinaryBinding);
            helper.assertTrue(
                    fixture.data.directBindings(NODE_A).size() == 3, "Ordinary cross-tunnel save changed bindings");

            NetworkTopologyService.TunnelSwitchEdit switchEdit =
                    fixture.service.requestTunnelSwitch(owner, NETWORK, NODE_A, TUNNEL_SECOND);

            helper.assertTrue(switchEdit.removedBindingCount() == 3, "Wrong tunnel-switch binding count");
            helper.assertTrue(
                    switchEdit.targetTunnelId().equals(TUNNEL_SECOND)
                            && switchEdit.targetName().value().equals("Target"),
                    "Wrong tunnel-switch target");
            helper.assertTrue(!fixture.data.isDirty(), "Tunnel-switch preview dirtied authority");
            rejected(
                    helper,
                    NetworkTopologyService.Reason.LOCKED,
                    () -> fixture.service.requestTunnelSwitch(administrator, NETWORK, NODE_A, TUNNEL_SECOND));

            NetworkNodeRecord switched = fixture.service.confirmTunnelSwitch(owner, switchEdit);

            helper.assertTrue(switched.revision() == before.revision() + 1, "Node revision changed more than once");
            helper.assertTrue(
                    fixture.data.topologyRevision() == topologyRevision + 1,
                    "Topology revision changed more than once");
            helper.assertTrue(fixture.data.directBindings(NODE_A).isEmpty(), "Old direct bindings survived switch");
            helper.assertTrue(fixture.data.directTunnelId(NODE_A).isEmpty(), "Target tunnel was persisted early");
            NetworkNodeDirectory.Lookup lookup = fixture.nodes.byId(NODE_A);
            helper.assertTrue(
                    lookup.status() == NetworkNodeDirectory.Status.UNIQUE
                            && lookup.entry().orElseThrow().record().equals(switched),
                    "Derived node directory missed tunnel-switch revision");
            helper.succeed();
        }
    }

    /** Disabled targets and stale switch summaries preserve every old direct binding. */
    @GameTest(template = "bootstrap")
    public static void rejectedDirectTunnelSwitchPreservesOldBindings(GameTestHelper helper) throws IOException {
        try (Fixture fixture = new Fixture(helper, ServerSettings.defaults())) {
            fixture.seedTunnelSwitchBindings();
            ServerPlayer owner = player(helper, OWNER);
            List<DirectNodeBinding> before = fixture.data.directBindings(NODE_A);
            NetworkTunnelRecord target = fixture.data.findTunnel(TUNNEL_SECOND).orElseThrow();
            fixture.data
                    .setTunnelEnabled(TUNNEL_SECOND, target.revision(), false)
                    .orElseThrow();
            fixture.data.setDirty(false);

            rejected(
                    helper,
                    NetworkTopologyService.Reason.TUNNEL_DISABLED,
                    () -> fixture.service.requestTunnelSwitch(owner, NETWORK, NODE_A, TUNNEL_SECOND));
            helper.assertTrue(fixture.data.directBindings(NODE_A).equals(before), "Disabled target removed bindings");
            helper.assertTrue(!fixture.data.isDirty(), "Disabled target dirtied authority");

            NetworkTunnelRecord disabled =
                    fixture.data.findTunnel(TUNNEL_SECOND).orElseThrow();
            fixture.data
                    .setTunnelEnabled(TUNNEL_SECOND, disabled.revision(), true)
                    .orElseThrow();
            NetworkTopologyService.TunnelSwitchEdit switchEdit =
                    fixture.service.requestTunnelSwitch(owner, NETWORK, NODE_A, TUNNEL_SECOND);
            NetworkTunnelRecord enabled = fixture.data.findTunnel(TUNNEL_SECOND).orElseThrow();
            fixture.data
                    .renameTunnel(TUNNEL_SECOND, enabled.revision(), new ManagedName("Changed"))
                    .orElseThrow();
            fixture.data.setDirty(false);

            rejected(
                    helper,
                    NetworkTopologyService.Reason.STALE_REVISION,
                    () -> fixture.service.confirmTunnelSwitch(owner, switchEdit));
            helper.assertTrue(fixture.data.directBindings(NODE_A).equals(before), "Stale switch removed bindings");
            helper.assertTrue(!fixture.data.isDirty(), "Stale switch dirtied authority");
            helper.succeed();
        }
    }

    private static ServerPlayer player(GameTestHelper helper, UUID id) {
        return new FakePlayer(helper.getLevel(), new GameProfile(id, "Topology" + id.getLeastSignificantBits()));
    }

    private static ServerPlayer operator(GameTestHelper helper) {
        return new OperatorFakePlayer(helper, new GameProfile(OP, "TopologyOperator"));
    }

    private static void rejected(GameTestHelper helper, NetworkTopologyService.Reason expected, Runnable operation) {
        try {
            operation.run();
        } catch (NetworkTopologyService.Rejected rejected) {
            helper.assertTrue(rejected.reason() == expected, "Expected " + expected + ", got " + rejected.reason());
            return;
        }
        helper.fail("Expected topology rejection " + expected);
    }

    private static final class OperatorFakePlayer extends FakePlayer {
        private OperatorFakePlayer(GameTestHelper helper, GameProfile profile) {
            super(helper.getLevel(), profile);
        }

        @Override
        public boolean hasPermissions(int level) {
            return true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path path;
        private final SavedNetworkRepository repository;
        private final NetworkSavedData data;
        private final NetworkNodeDirectory nodes;
        private final EditLockTable locks;
        private final NetworkTopologyService service;

        private Fixture(GameTestHelper helper, ServerSettings settings) throws IOException {
            this(helper, settings, false);
        }

        private Fixture(GameTestHelper helper, ServerSettings settings, boolean startupConflict) throws IOException {
            path = Files.createTempDirectory("omniresonance-topology-management-test-");
            DimensionDataStorage storage = new DimensionDataStorage(
                    path.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
            repository = new SavedNetworkRepository(storage, path);
            NetworkMetadata metadata =
                    new NetworkMetadata(NETWORK, OWNER, new ManagedName("Topology"), 0, Set.of(ADMIN));
            repository.createNetwork(metadata);
            data = repository.findLoadedNetwork(NETWORK).orElseThrow();
            seedNode(NODE_A, "Node A", 1);
            seedNode(NODE_B, "Node B", 2);
            NetworkDirectory networks = new NetworkDirectory(List.of(metadata));
            List<NetworkNodeDirectory.Entry> entries = new ArrayList<>();
            for (NetworkNodeRecord node : data.nodes()) {
                entries.add(new NetworkNodeDirectory.Entry(NETWORK, node));
            }
            if (startupConflict) {
                seedTunnelAndChannel();
                entries.clear();
                for (UUID nodeId : List.of(NODE_A, NODE_B)) {
                    NetworkNodeRecord original = data.findNode(nodeId).orElseThrow();
                    NetworkNodeRecord bound = data.setDirectBinding(
                            nodeId, original.revision(), CHANNEL, TransferDirection.INPUT, false, -1);
                    entries.add(new NetworkNodeDirectory.Entry(NETWORK, bound));
                }
                UUID otherNetworkId = new UUID(336, 1);
                repository.createNetwork(
                        new NetworkMetadata(otherNetworkId, OWNER, new ManagedName("Other network"), 1, Set.of()));
                NetworkNodeRecord duplicate = repository
                        .findLoadedNetwork(otherNetworkId)
                        .orElseThrow()
                        .createNode(
                                NODE_B,
                                new ManagedName("Duplicate"),
                                GlobalPos.of(Level.OVERWORLD, new BlockPos(3, 64, 0)),
                                NodeForm.BLOCK,
                                Direction.DOWN);
                entries.add(new NetworkNodeDirectory.Entry(otherNetworkId, duplicate));
            }
            nodes = new NetworkNodeDirectory(entries);
            locks = new EditLockTable();
            ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(TUNNEL, CHANNEL, new UUID(334, 1), new UUID(334, 2)));
            service = new NetworkTopologyService(
                    helper.getLevel().getServer(),
                    networks,
                    repository,
                    nodes,
                    locks,
                    new ServerConfig.State(1, 1, true, settings),
                    ids::removeFirst);
        }

        private void seedNode(UUID id, String name, int x) {
            NetworkNodeRecord node = data.createNode(
                    id,
                    new ManagedName(name),
                    GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, 0)),
                    NodeForm.BLOCK,
                    Direction.DOWN);
            data.setNodeMode(id, node.revision(), NodeMode.DIRECT, false).orElseThrow();
        }

        private void seedTunnelAndChannel() {
            if (data.findTunnel(TUNNEL).isEmpty()) {
                NetworkSavedData.TunnelCreation creation =
                        data.createTunnel(TUNNEL, new ManagedName("Main"), CHANNEL, new ManagedName("Items"), -1);
                data.createChannel(
                        TUNNEL, creation.tunnel().revision(), new UUID(333, 2), new ManagedName("Reserve"), -1);
            }
        }

        private void seedDirectBindings() {
            NetworkNodeRecord first = data.findNode(NODE_A).orElseThrow();
            NetworkNodeRecord updatedA =
                    data.setDirectBinding(NODE_A, first.revision(), CHANNEL, TransferDirection.INPUT, false, -1);
            nodes.update(nodes.byId(NODE_A).entry().orElseThrow(), new NetworkNodeDirectory.Entry(NETWORK, updatedA));
            NetworkNodeRecord second = data.findNode(NODE_B).orElseThrow();
            NetworkNodeRecord updatedB =
                    data.setDirectBinding(NODE_B, second.revision(), CHANNEL, TransferDirection.OUTPUT, false, -1);
            nodes.update(nodes.byId(NODE_B).entry().orElseThrow(), new NetworkNodeDirectory.Entry(NETWORK, updatedB));
        }

        private void seedTunnelSwitchBindings() {
            NetworkSavedData.TunnelCreation source =
                    data.createTunnel(TUNNEL, new ManagedName("Source"), CHANNEL, new ManagedName("A"), -1);
            data.createChannel(TUNNEL, source.tunnel().revision(), new UUID(333, 2), new ManagedName("B"), -1);
            data.createChannel(
                    TUNNEL,
                    data.findTunnel(TUNNEL).orElseThrow().revision(),
                    new UUID(333, 3),
                    new ManagedName("C"),
                    -1);
            data.createTunnel(
                    TUNNEL_SECOND, new ManagedName("Target"), TARGET_CHANNEL, new ManagedName("Target channel"), -1);
            NetworkNodeDirectory.Entry original = nodes.byId(NODE_A).entry().orElseThrow();
            NetworkNodeRecord node = original.record();
            for (UUID channelId : List.of(CHANNEL, new UUID(333, 2), new UUID(333, 3))) {
                node = data.setDirectBinding(NODE_A, node.revision(), channelId, TransferDirection.INPUT, false, -1);
            }
            nodes.update(original, new NetworkNodeDirectory.Entry(NETWORK, node));
        }

        @Override
        public void close() throws IOException {
            service.close();
            locks.clear();
            IOUtilities.waitUntilIOWorkerComplete();
            try (var files = Files.walk(path)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
