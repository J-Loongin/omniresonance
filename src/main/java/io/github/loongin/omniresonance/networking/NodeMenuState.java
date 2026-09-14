// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NodeForm;
import io.github.loongin.omniresonance.transfer.ResourcePolicyEdit;
import io.github.loongin.omniresonance.transfer.ResourceTransferPolicy;
import io.github.loongin.omniresonance.transfer.StoredResourcePolicy;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/** Bounded immutable authoritative states rendered by the physical node Menu. */
public sealed interface NodeMenuState {
    record Unavailable() implements NodeMenuState {}

    record NoAccess() implements NodeMenuState {}

    record NoNetworks() implements NodeMenuState {}

    record BlankList(NodeNetworkPage page) implements NodeMenuState {
        public BlankList {
            Objects.requireNonNull(page, "page");
        }
    }

    record BlankEdit(NodeNetworkSummary network, long suggestedNodeNumber) implements NodeMenuState {
        public BlankEdit {
            Objects.requireNonNull(network, "network");
            if (suggestedNodeNumber < 1) {
                throw new IllegalArgumentException("Suggested node number must be positive");
            }
        }
    }

    record LinkedRoot(NodeMenuNodeSummary node) implements NodeMenuState {
        public LinkedRoot {
            Objects.requireNonNull(node, "node");
        }
    }

    record LinkedRename(NodeMenuNodeSummary node) implements NodeMenuState {
        public LinkedRename {
            Objects.requireNonNull(node, "node");
        }
    }

    record LinkedMode(NodeMenuNodeSummary node) implements NodeMenuState {
        public LinkedMode {
            Objects.requireNonNull(node, "node");
        }
    }

    record ModeRoot(NodeMenuNodeSummary node) implements NodeMenuState {
        public ModeRoot {
            Objects.requireNonNull(node, "node");
        }
    }

    record NetworkSelection(NodeMenuNodeSummary node, NodeNetworkPage page) implements NodeMenuState {
        public NetworkSelection {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(page, "page");
        }
    }

    record NetworkMoveEdit(NodeMenuNodeSummary node, NodeNetworkSummary target) implements NodeMenuState {
        public NetworkMoveEdit {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(target, "target");
            if (node.networkId().equals(target.networkId())) {
                throw new IllegalArgumentException("Network move target must differ from the current network");
            }
        }
    }

    record DirectTunnelList(NodeMenuNodeSummary node, NodeTunnelPage page, long revision) implements NodeMenuState {
        public DirectTunnelList {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(page, "page");
            if (revision < 0) {
                throw new IllegalArgumentException("Negative tunnel catalog revision");
            }
        }
    }

    record RestrictedTunnel(NodeMenuNodeSummary node, NodeTunnelSummary tunnel) implements NodeMenuState {
        public RestrictedTunnel {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tunnel, "tunnel");
            if (tunnel.enabled()) {
                throw new IllegalArgumentException("Restricted tunnel must be disabled");
            }
        }
    }

    record DirectChannelList(NodeMenuNodeSummary node, NodeTunnelSummary tunnel, NodeChannelPage page)
            implements NodeMenuState {
        public DirectChannelList {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tunnel, "tunnel");
            Objects.requireNonNull(page, "page");
            if (!tunnel.enabled()) {
                throw new IllegalArgumentException("Channel list requires an enabled tunnel");
            }
        }
    }

    record DirectBindingEdit(
            NodeMenuNodeSummary node,
            NodeTunnelSummary tunnel,
            NodeChannelSummary channel,
            @Nullable ResourcePolicyEdit policy,
            FilterPresetPage presets,
            @Nullable String selectedPresetName,
            WorkingFaces workingFaces,
            List<NodeFacePreview> previews)
            implements NodeMenuState {
        public DirectBindingEdit(
                NodeMenuNodeSummary node,
                NodeTunnelSummary tunnel,
                NodeChannelSummary channel,
                ResourcePolicyEdit policy,
                FilterPresetPage presets,
                @Nullable String selectedPresetName) {
            this(
                    node,
                    tunnel,
                    channel,
                    policy,
                    presets,
                    selectedPresetName,
                    node.form() == NodeForm.PANEL ? WorkingFaces.attachedFace() : WorkingFaces.explicit(0),
                    List.of());
        }

        public DirectBindingEdit(NodeMenuNodeSummary node, NodeTunnelSummary tunnel, NodeChannelSummary channel) {
            this(
                    node,
                    tunnel,
                    channel,
                    ResourcePolicyEdit.fromStored(new StoredResourcePolicy(
                            ResourceTransferPolicy.defaults(
                                    channel.currentDirection() == null
                                            ? TransferDirection.INPUT
                                            : channel.currentDirection()),
                            java.util.Map.of())),
                    new FilterPresetPage(List.of(), 0, 0, 0),
                    null);
        }

        public DirectBindingEdit withPolicy(@Nullable ResourcePolicyEdit value) {
            return new DirectBindingEdit(
                    node, tunnel, channel, value, presets, selectedPresetName, workingFaces, previews);
        }

        public DirectBindingEdit {
            Objects.requireNonNull(workingFaces, "workingFaces").validate(node.form());
            previews = NodeFacePreview.validate(previews);
            if (node.form() == NodeForm.PANEL
                    && previews.stream().anyMatch(preview -> preview.direction() != node.facing()))
                throw new IllegalArgumentException("Panel preview must match attachment");
            Objects.requireNonNull(presets, "presets");
            if (selectedPresetName != null) new io.github.loongin.omniresonance.network.ManagedName(selectedPresetName);
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tunnel, "tunnel");
            Objects.requireNonNull(channel, "channel");
            if (!tunnel.enabled()) {
                throw new IllegalArgumentException("Binding edit requires an enabled tunnel");
            }
        }
    }

    record DirectChannelRoot(
            NodeMenuNodeSummary node,
            NodeTunnelSummary tunnel,
            NodeChannelSummary channel,
            @Nullable NodeResourcePolicySummary policy,
            NodeTransferStatus transferStatus)
            implements NodeMenuState {
        public DirectChannelRoot(NodeMenuNodeSummary node, NodeTunnelSummary tunnel, NodeChannelSummary channel) {
            this(node, tunnel, channel, null, NodeTransferStatus.IDLE);
        }

        public DirectChannelRoot {
            requireEnabledChannelView(node, tunnel, channel);
            Objects.requireNonNull(transferStatus, "transferStatus");
        }
    }

    record DirectChannelSettings(NodeMenuNodeSummary node, NodeTunnelSummary tunnel, NodeChannelSummary channel)
            implements NodeMenuState {
        public DirectChannelSettings {
            requireEnabledChannelView(node, tunnel, channel);
        }
    }

    record DirectTunnelSwitch(NodeMenuNodeSummary node, NodeTunnelSwitchSummary summary) implements NodeMenuState {
        public DirectTunnelSwitch {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(summary, "summary");
        }
    }

    record DomainRoot(NodeMenuNodeSummary node, @Nullable TransferDirection direction) implements NodeMenuState {
        public DomainRoot {
            Objects.requireNonNull(node, "node");
        }
    }

    record DomainEdit(NodeMenuNodeSummary node, @Nullable TransferDirection direction) implements NodeMenuState {
        public DomainEdit {
            Objects.requireNonNull(node, "node");
        }
    }

    record DirectChannelEdit(
            NodeMenuNodeSummary node,
            NodeTunnelSummary tunnel,
            @Nullable NodeChannelSummary existing,
            @Nullable String suggestedName)
            implements NodeMenuState {
        public DirectChannelEdit {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tunnel, "tunnel");
            if (!tunnel.enabled() || (existing == null) != (suggestedName != null)) {
                throw new IllegalArgumentException("Invalid direct channel edit state");
            }
            if (suggestedName != null
                    && !new io.github.loongin.omniresonance.network.ManagedName(suggestedName)
                            .value()
                            .equals(suggestedName)) {
                throw new IllegalArgumentException("Channel suggestion must be canonical");
            }
        }
    }

    record DirectChannelDelete(NodeMenuNodeSummary node, NodeTunnelSummary tunnel, TopologyDeletionSummary summary)
            implements NodeMenuState {
        public DirectChannelDelete {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tunnel, "tunnel");
            Objects.requireNonNull(summary, "summary");
            if (!tunnel.enabled()
                    || summary.kind() != TopologyDeletionSummary.Kind.CHANNEL
                    || summary.channelCount() != 0) {
                throw new IllegalArgumentException("Invalid direct channel deletion state");
            }
        }
    }

    private static NodeTransferStatus readTransferStatus(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> NodeTransferStatus.IDLE;
            case 1 -> NodeTransferStatus.RUNNING;
            case 2 -> NodeTransferStatus.WAITING_BUDGET;
            case 3 -> NodeTransferStatus.BLOCKED;
            case 4 -> NodeTransferStatus.FAILED;
            case 5 -> NodeTransferStatus.NO_WORK_FACES;
            default -> throw new DecoderException("Invalid item transfer status");
        };
    }

    static NodeMenuState read(FriendlyByteBuf buffer) {
        return switch (buffer.readUnsignedByte()) {
            case 0 -> new Unavailable();
            case 1 -> new NoAccess();
            case 2 -> new NoNetworks();
            case 3 -> new BlankList(NodeNetworkPage.read(buffer));
            case 4 -> new BlankEdit(NodeNetworkSummary.read(buffer), buffer.readLong());
            case 5 -> new LinkedRoot(NodeMenuNodeSummary.read(buffer));
            case 6 -> new LinkedRename(NodeMenuNodeSummary.read(buffer));
            case 7 -> new LinkedMode(NodeMenuNodeSummary.read(buffer));
            case 8 -> new ModeRoot(NodeMenuNodeSummary.read(buffer));
            case 9 -> new NetworkSelection(NodeMenuNodeSummary.read(buffer), NodeNetworkPage.read(buffer));
            case 10 -> new NetworkMoveEdit(NodeMenuNodeSummary.read(buffer), NodeNetworkSummary.read(buffer));
            case 11 ->
                new DirectTunnelList(NodeMenuNodeSummary.read(buffer), NodeTunnelPage.read(buffer), buffer.readLong());
            case 12 -> new RestrictedTunnel(NodeMenuNodeSummary.read(buffer), NodeTunnelSummary.read(buffer));
            case 13 ->
                new DirectChannelList(
                        NodeMenuNodeSummary.read(buffer), NodeTunnelSummary.read(buffer), NodeChannelPage.read(buffer));
            case 14 ->
                new DirectBindingEdit(
                        NodeMenuNodeSummary.read(buffer),
                        NodeTunnelSummary.read(buffer),
                        NodeChannelSummary.read(buffer),
                        buffer.readBoolean() ? ResourcePolicyMenuCodec.read(buffer) : null,
                        FilterPresetPage.read(buffer),
                        buffer.readBoolean() ? NetworkSummary.readName(buffer) : null,
                        NodeWorkingFacesCodec.read(buffer),
                        NodeFacePreview.readList(buffer));
            case 15 ->
                new DomainRoot(
                        NodeMenuNodeSummary.read(buffer),
                        buffer.readBoolean() ? NodeMenuCodecSupport.readTransferDirection(buffer) : null);
            case 16 ->
                new DomainEdit(
                        NodeMenuNodeSummary.read(buffer),
                        buffer.readBoolean() ? NodeMenuCodecSupport.readTransferDirection(buffer) : null);
            case 17 ->
                new DirectChannelEdit(
                        NodeMenuNodeSummary.read(buffer),
                        NodeTunnelSummary.read(buffer),
                        buffer.readBoolean() ? NodeChannelSummary.read(buffer) : null,
                        buffer.readBoolean() ? NetworkSummary.readName(buffer) : null);
            case 18 ->
                new DirectChannelDelete(
                        NodeMenuNodeSummary.read(buffer),
                        NodeTunnelSummary.read(buffer),
                        TopologyDeletionSummary.read(buffer));
            case 19 ->
                new DirectChannelRoot(
                        NodeMenuNodeSummary.read(buffer),
                        NodeTunnelSummary.read(buffer),
                        NodeChannelSummary.read(buffer),
                        buffer.readBoolean() ? NodeResourcePolicySummary.read(buffer) : null,
                        readTransferStatus(buffer));
            case 20 ->
                new DirectChannelSettings(
                        NodeMenuNodeSummary.read(buffer),
                        NodeTunnelSummary.read(buffer),
                        NodeChannelSummary.read(buffer));
            case 21 -> new DirectTunnelSwitch(NodeMenuNodeSummary.read(buffer), NodeTunnelSwitchSummary.read(buffer));
            default -> throw new DecoderException("Unknown node-menu state");
        };
    }

    static void write(FriendlyByteBuf buffer, NodeMenuState state) {
        switch (state) {
            case Unavailable ignored -> buffer.writeByte(0);
            case NoAccess ignored -> buffer.writeByte(1);
            case NoNetworks ignored -> buffer.writeByte(2);
            case BlankList list -> {
                buffer.writeByte(3);
                list.page().write(buffer);
            }
            case BlankEdit edit -> {
                buffer.writeByte(4);
                edit.network().write(buffer);
                buffer.writeLong(edit.suggestedNodeNumber());
            }
            case LinkedRoot root -> {
                buffer.writeByte(5);
                root.node().write(buffer);
            }
            case LinkedRename rename -> {
                buffer.writeByte(6);
                rename.node().write(buffer);
            }
            case LinkedMode mode -> {
                buffer.writeByte(7);
                mode.node().write(buffer);
            }
            case ModeRoot root -> {
                buffer.writeByte(8);
                root.node().write(buffer);
            }
            case NetworkSelection selection -> {
                buffer.writeByte(9);
                selection.node().write(buffer);
                selection.page().write(buffer);
            }
            case NetworkMoveEdit edit -> {
                buffer.writeByte(10);
                edit.node().write(buffer);
                edit.target().write(buffer);
            }
            case DirectTunnelList list -> {
                buffer.writeByte(11);
                list.node().write(buffer);
                list.page().write(buffer);
                buffer.writeLong(list.revision());
            }
            case RestrictedTunnel restricted -> {
                buffer.writeByte(12);
                restricted.node().write(buffer);
                restricted.tunnel().write(buffer);
            }
            case DirectChannelList list -> {
                buffer.writeByte(13);
                list.node().write(buffer);
                list.tunnel().write(buffer);
                list.page().write(buffer);
            }
            case DirectBindingEdit edit -> {
                buffer.writeByte(14);
                edit.node().write(buffer);
                edit.tunnel().write(buffer);
                edit.channel().write(buffer);
                buffer.writeBoolean(edit.policy() != null);
                if (edit.policy() != null) ResourcePolicyMenuCodec.write(buffer, edit.policy());
                edit.presets().write(buffer);
                buffer.writeBoolean(edit.selectedPresetName() != null);
                if (edit.selectedPresetName() != null) NetworkSummary.writeName(buffer, edit.selectedPresetName());
                NodeWorkingFacesCodec.write(buffer, edit.workingFaces());
                NodeFacePreview.writeList(buffer, edit.previews());
            }
            case DomainRoot root -> {
                buffer.writeByte(15);
                root.node().write(buffer);
                writeOptionalDirection(buffer, root.direction());
            }
            case DomainEdit edit -> {
                buffer.writeByte(16);
                edit.node().write(buffer);
                writeOptionalDirection(buffer, edit.direction());
            }
            case DirectChannelEdit edit -> {
                buffer.writeByte(17);
                edit.node().write(buffer);
                edit.tunnel().write(buffer);
                buffer.writeBoolean(edit.existing() != null);
                if (edit.existing() != null) {
                    edit.existing().write(buffer);
                }
                buffer.writeBoolean(edit.suggestedName() != null);
                if (edit.suggestedName() != null) {
                    NetworkSummary.writeName(buffer, edit.suggestedName());
                }
            }
            case DirectChannelDelete delete -> {
                buffer.writeByte(18);
                delete.node().write(buffer);
                delete.tunnel().write(buffer);
                delete.summary().write(buffer);
            }
            case DirectChannelRoot root -> {
                buffer.writeByte(19);
                root.node().write(buffer);
                root.tunnel().write(buffer);
                root.channel().write(buffer);
                buffer.writeBoolean(root.policy() != null);
                if (root.policy() != null) NodeResourcePolicySummary.write(buffer, root.policy());
                buffer.writeByte(
                        switch (root.transferStatus()) {
                            case IDLE -> 0;
                            case RUNNING -> 1;
                            case WAITING_BUDGET -> 2;
                            case BLOCKED -> 3;
                            case FAILED -> 4;
                            case NO_WORK_FACES -> 5;
                        });
            }
            case DirectChannelSettings settings -> {
                buffer.writeByte(20);
                settings.node().write(buffer);
                settings.tunnel().write(buffer);
                settings.channel().write(buffer);
            }
            case DirectTunnelSwitch tunnelSwitch -> {
                buffer.writeByte(21);
                tunnelSwitch.node().write(buffer);
                tunnelSwitch.summary().write(buffer);
            }
        }
    }

    private static void requireEnabledChannelView(
            NodeMenuNodeSummary node, NodeTunnelSummary tunnel, NodeChannelSummary channel) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(tunnel, "tunnel");
        Objects.requireNonNull(channel, "channel");
        if (!tunnel.enabled()) {
            throw new IllegalArgumentException("Channel view requires an enabled tunnel");
        }
    }

    private static void writeOptionalDirection(FriendlyByteBuf buffer, @Nullable TransferDirection direction) {
        buffer.writeBoolean(direction != null);
        if (direction != null) {
            NodeMenuCodecSupport.writeTransferDirection(buffer, direction);
        }
    }
}
