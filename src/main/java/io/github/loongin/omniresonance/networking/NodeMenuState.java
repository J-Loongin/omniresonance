// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.netty.handler.codec.DecoderException;
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

    record DirectBindingEdit(NodeMenuNodeSummary node, NodeTunnelSummary tunnel, NodeChannelSummary channel)
            implements NodeMenuState {
        public DirectBindingEdit {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(tunnel, "tunnel");
            Objects.requireNonNull(channel, "channel");
            if (!tunnel.enabled()) {
                throw new IllegalArgumentException("Binding edit requires an enabled tunnel");
            }
        }
    }

    record DirectChannelRoot(NodeMenuNodeSummary node, NodeTunnelSummary tunnel, NodeChannelSummary channel)
            implements NodeMenuState {
        public DirectChannelRoot {
            requireEnabledChannelView(node, tunnel, channel);
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
                        NodeChannelSummary.read(buffer));
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
                        NodeChannelSummary.read(buffer));
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
