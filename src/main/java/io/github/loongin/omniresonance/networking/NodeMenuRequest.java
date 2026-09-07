// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.node.NodeMode;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Typed bounded node-Menu intent; identity, authority, node, token and revision remain server-owned. */
public sealed interface NodeMenuRequest extends CustomPacketPayload {
    Type<NodeMenuRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "node_menu_request"));
    StreamCodec<FriendlyByteBuf, NodeMenuRequest> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NodeMenuRequest decode(FriendlyByteBuf buffer) {
            NodeMenuCodecSupport.requirePayloadBound(buffer);
            try {
                int tag = buffer.readUnsignedByte();
                if (tag > 36) {
                    throw new DecoderException("Unknown node-menu request");
                }
                int containerId = buffer.readVarInt();
                UUID sessionId = buffer.readUUID();
                long sequence = buffer.readLong();
                return switch (tag) {
                    case 0 ->
                        new Page(
                                containerId,
                                sessionId,
                                sequence,
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 1 -> new BeginBlank(containerId, sessionId, sequence, buffer.readUUID());
                    case 2 -> new Link(containerId, sessionId, sequence, NetworkSummary.readName(buffer));
                    case 3 -> new BeginRename(containerId, sessionId, sequence);
                    case 4 -> new Rename(containerId, sessionId, sequence, NetworkSummary.readName(buffer));
                    case 5 -> new BeginMode(containerId, sessionId, sequence);
                    case 6 ->
                        new SetMode(
                                containerId,
                                sessionId,
                                sequence,
                                NodeMenuCodecSupport.readMode(buffer),
                                buffer.readBoolean());
                    case 7 -> new SetEnabled(containerId, sessionId, sequence, buffer.readBoolean());
                    case 8 -> new SetChunkLoadingRequested(containerId, sessionId, sequence, buffer.readBoolean());
                    case 9 -> new Heartbeat(containerId, sessionId, sequence);
                    case 10 -> new CancelEdit(containerId, sessionId, sequence);
                    case 11 -> new Back(containerId, sessionId, sequence);
                    case 12 -> new OpenModeRoot(containerId, sessionId, sequence);
                    case 13 -> new OpenNetworkSelection(containerId, sessionId, sequence);
                    case 14 -> new BeginNetworkMove(containerId, sessionId, sequence, buffer.readUUID());
                    case 15 -> new MoveNetwork(containerId, sessionId, sequence, NetworkSummary.readName(buffer));
                    case 16 -> new OpenDirect(containerId, sessionId, sequence);
                    case 18 ->
                        new PageTunnels(
                                containerId,
                                sessionId,
                                sequence,
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 19 -> new OpenTunnel(containerId, sessionId, sequence, buffer.readUUID());
                    case 20 ->
                        new PageChannels(
                                containerId,
                                sessionId,
                                sequence,
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 21 -> new BeginBinding(containerId, sessionId, sequence, buffer.readUUID());
                    case 22 ->
                        new SetBindingDirection(
                                containerId,
                                sessionId,
                                sequence,
                                NodeMenuCodecSupport.readTransferDirection(buffer),
                                buffer.readBoolean());
                    case 23 -> new RemoveBinding(containerId, sessionId, sequence);
                    case 24 -> new OpenDomain(containerId, sessionId, sequence);
                    case 25 -> new BeginDomainEdit(containerId, sessionId, sequence);
                    case 26 ->
                        new SetDomainDirection(
                                containerId,
                                sessionId,
                                sequence,
                                NodeMenuCodecSupport.readTransferDirection(buffer),
                                buffer.readBoolean());
                    case 27 -> new RemoveDomain(containerId, sessionId, sequence);
                    case 28 ->
                        new BeginCreateChannel(containerId, sessionId, sequence, NetworkSummary.readName(buffer));
                    case 29 -> new BeginRenameChannel(containerId, sessionId, sequence, buffer.readUUID());
                    case 30 -> new SaveChannel(containerId, sessionId, sequence, NetworkSummary.readName(buffer));
                    case 31 -> new RequestDeleteChannel(containerId, sessionId, sequence, buffer.readUUID());
                    case 32 -> new ConfirmDeleteChannel(containerId, sessionId, sequence);
                    case 33 -> new OpenChannel(containerId, sessionId, sequence, buffer.readUUID());
                    case 34 -> new OpenChannelSettings(containerId, sessionId, sequence);
                    case 35 -> new RequestTunnelSwitch(containerId, sessionId, sequence, buffer.readUUID());
                    case 36 -> new ConfirmTunnelSwitch(containerId, sessionId, sequence);
                    default -> throw new DecoderException("Unknown node-menu request");
                };
            } catch (DecoderException failure) {
                throw failure;
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new DecoderException("Invalid node-menu request", failure);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, NodeMenuRequest request) {
            int start = buffer.writerIndex();
            switch (request) {
                case Page page -> {
                    writeEnvelope(buffer, 0, page);
                    buffer.writeBoolean(page.anchor() != null);
                    if (page.anchor() != null) {
                        buffer.writeUUID(page.anchor());
                    }
                    buffer.writeBoolean(page.backwards());
                }
                case BeginBlank begin -> {
                    writeEnvelope(buffer, 1, begin);
                    buffer.writeUUID(begin.networkId());
                }
                case Link link -> {
                    writeEnvelope(buffer, 2, link);
                    NetworkSummary.writeName(buffer, link.name());
                }
                case BeginRename begin -> writeEnvelope(buffer, 3, begin);
                case Rename rename -> {
                    writeEnvelope(buffer, 4, rename);
                    NetworkSummary.writeName(buffer, rename.name());
                }
                case BeginMode begin -> writeEnvelope(buffer, 5, begin);
                case SetMode mode -> {
                    writeEnvelope(buffer, 6, mode);
                    NodeMenuCodecSupport.writeMode(buffer, mode.mode());
                    buffer.writeBoolean(mode.confirmedReset());
                }
                case SetEnabled enabled -> {
                    writeEnvelope(buffer, 7, enabled);
                    buffer.writeBoolean(enabled.enabled());
                }
                case SetChunkLoadingRequested requested -> {
                    writeEnvelope(buffer, 8, requested);
                    buffer.writeBoolean(requested.requested());
                }
                case Heartbeat heartbeat -> writeEnvelope(buffer, 9, heartbeat);
                case CancelEdit cancel -> writeEnvelope(buffer, 10, cancel);
                case Back back -> writeEnvelope(buffer, 11, back);
                case OpenModeRoot open -> writeEnvelope(buffer, 12, open);
                case OpenNetworkSelection open -> writeEnvelope(buffer, 13, open);
                case BeginNetworkMove begin -> {
                    writeEnvelope(buffer, 14, begin);
                    buffer.writeUUID(begin.targetNetworkId());
                }
                case MoveNetwork move -> {
                    writeEnvelope(buffer, 15, move);
                    NetworkSummary.writeName(buffer, move.name());
                }
                case OpenDirect open -> writeEnvelope(buffer, 16, open);
                case PageTunnels page -> {
                    writeEnvelope(buffer, 18, page);
                    writePageAnchor(buffer, page.anchor());
                    buffer.writeBoolean(page.backwards());
                }
                case OpenTunnel open -> {
                    writeEnvelope(buffer, 19, open);
                    buffer.writeUUID(open.tunnelId());
                }
                case PageChannels page -> {
                    writeEnvelope(buffer, 20, page);
                    writePageAnchor(buffer, page.anchor());
                    buffer.writeBoolean(page.backwards());
                }
                case BeginBinding begin -> {
                    writeEnvelope(buffer, 21, begin);
                    buffer.writeUUID(begin.channelId());
                }
                case SetBindingDirection direction -> {
                    writeEnvelope(buffer, 22, direction);
                    NodeMenuCodecSupport.writeTransferDirection(buffer, direction.direction());
                    buffer.writeBoolean(direction.confirmedReset());
                }
                case RemoveBinding remove -> writeEnvelope(buffer, 23, remove);
                case OpenDomain open -> writeEnvelope(buffer, 24, open);
                case BeginDomainEdit begin -> writeEnvelope(buffer, 25, begin);
                case SetDomainDirection direction -> {
                    writeEnvelope(buffer, 26, direction);
                    NodeMenuCodecSupport.writeTransferDirection(buffer, direction.direction());
                    buffer.writeBoolean(direction.confirmedReset());
                }
                case RemoveDomain remove -> writeEnvelope(buffer, 27, remove);
                case BeginCreateChannel begin -> {
                    writeEnvelope(buffer, 28, begin);
                    NetworkSummary.writeName(buffer, begin.suggestionPrefix());
                }
                case BeginRenameChannel begin -> {
                    writeEnvelope(buffer, 29, begin);
                    buffer.writeUUID(begin.channelId());
                }
                case SaveChannel save -> {
                    writeEnvelope(buffer, 30, save);
                    NetworkSummary.writeName(buffer, save.name());
                }
                case RequestDeleteChannel delete -> {
                    writeEnvelope(buffer, 31, delete);
                    buffer.writeUUID(delete.channelId());
                }
                case ConfirmDeleteChannel confirm -> writeEnvelope(buffer, 32, confirm);
                case OpenChannel open -> {
                    writeEnvelope(buffer, 33, open);
                    buffer.writeUUID(open.channelId());
                }
                case OpenChannelSettings open -> writeEnvelope(buffer, 34, open);
                case RequestTunnelSwitch switchRequest -> {
                    writeEnvelope(buffer, 35, switchRequest);
                    buffer.writeUUID(switchRequest.targetTunnelId());
                }
                case ConfirmTunnelSwitch confirm -> writeEnvelope(buffer, 36, confirm);
            }
            NodeMenuCodecSupport.requireEncodedBound(buffer, start);
        }
    };

    int containerId();

    UUID sessionId();

    long sequence();

    @Override
    default Type<NodeMenuRequest> type() {
        return TYPE;
    }

    record Page(
            int containerId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NodeMenuRequest {
        public Page {
            requireEnvelope(containerId, sessionId, sequence);
            if (backwards && anchor == null) {
                throw new IllegalArgumentException("Backwards node page requires an anchor");
            }
        }
    }

    record BeginBlank(int containerId, UUID sessionId, long sequence, UUID networkId) implements NodeMenuRequest {
        public BeginBlank {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(networkId, "networkId");
        }
    }

    record Link(int containerId, UUID sessionId, long sequence, String name) implements NodeMenuRequest {
        public Link {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record BeginRename(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public BeginRename {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record Rename(int containerId, UUID sessionId, long sequence, String name) implements NodeMenuRequest {
        public Rename {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record BeginMode(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public BeginMode {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record SetMode(int containerId, UUID sessionId, long sequence, NodeMode mode, boolean confirmedReset)
            implements NodeMenuRequest {
        public SetMode {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(mode, "mode");
            if (mode == NodeMode.UNCONFIGURED) {
                throw new IllegalArgumentException("Unconfigured mode cannot be selected");
            }
        }
    }

    record SetEnabled(int containerId, UUID sessionId, long sequence, boolean enabled) implements NodeMenuRequest {
        public SetEnabled {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record SetChunkLoadingRequested(int containerId, UUID sessionId, long sequence, boolean requested)
            implements NodeMenuRequest {
        public SetChunkLoadingRequested {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record Heartbeat(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public Heartbeat {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record CancelEdit(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public CancelEdit {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record Back(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public Back {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record OpenModeRoot(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public OpenModeRoot {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record OpenNetworkSelection(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public OpenNetworkSelection {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record BeginNetworkMove(int containerId, UUID sessionId, long sequence, UUID targetNetworkId)
            implements NodeMenuRequest {
        public BeginNetworkMove {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(targetNetworkId, "targetNetworkId");
        }
    }

    record MoveNetwork(int containerId, UUID sessionId, long sequence, String name) implements NodeMenuRequest {
        public MoveNetwork {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record OpenDirect(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public OpenDirect {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record PageTunnels(
            int containerId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NodeMenuRequest {
        public PageTunnels {
            requireEnvelope(containerId, sessionId, sequence);
            requirePageAnchor(anchor, backwards);
        }
    }

    record OpenTunnel(int containerId, UUID sessionId, long sequence, UUID tunnelId) implements NodeMenuRequest {
        public OpenTunnel {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(tunnelId, "tunnelId");
        }
    }

    record PageChannels(
            int containerId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NodeMenuRequest {
        public PageChannels {
            requireEnvelope(containerId, sessionId, sequence);
            requirePageAnchor(anchor, backwards);
        }
    }

    record BeginBinding(int containerId, UUID sessionId, long sequence, UUID channelId) implements NodeMenuRequest {
        public BeginBinding {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(channelId, "channelId");
        }
    }

    record SetBindingDirection(
            int containerId, UUID sessionId, long sequence, TransferDirection direction, boolean confirmedReset)
            implements NodeMenuRequest {
        public SetBindingDirection {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(direction, "direction");
        }
    }

    record RemoveBinding(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public RemoveBinding {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record OpenDomain(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public OpenDomain {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record BeginDomainEdit(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public BeginDomainEdit {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record SetDomainDirection(
            int containerId, UUID sessionId, long sequence, TransferDirection direction, boolean confirmedReset)
            implements NodeMenuRequest {
        public SetDomainDirection {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(direction, "direction");
        }
    }

    record RemoveDomain(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public RemoveDomain {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record BeginCreateChannel(int containerId, UUID sessionId, long sequence, String suggestionPrefix)
            implements NodeMenuRequest {
        public BeginCreateChannel {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(suggestionPrefix, "suggestionPrefix");
        }
    }

    record BeginRenameChannel(int containerId, UUID sessionId, long sequence, UUID channelId)
            implements NodeMenuRequest {
        public BeginRenameChannel {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(channelId, "channelId");
        }
    }

    record SaveChannel(int containerId, UUID sessionId, long sequence, String name) implements NodeMenuRequest {
        public SaveChannel {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record RequestDeleteChannel(int containerId, UUID sessionId, long sequence, UUID channelId)
            implements NodeMenuRequest {
        public RequestDeleteChannel {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(channelId, "channelId");
        }
    }

    record ConfirmDeleteChannel(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public ConfirmDeleteChannel {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record OpenChannel(int containerId, UUID sessionId, long sequence, UUID channelId) implements NodeMenuRequest {
        public OpenChannel {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(channelId, "channelId");
        }
    }

    record OpenChannelSettings(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public OpenChannelSettings {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    record RequestTunnelSwitch(int containerId, UUID sessionId, long sequence, UUID targetTunnelId)
            implements NodeMenuRequest {
        public RequestTunnelSwitch {
            requireEnvelope(containerId, sessionId, sequence);
            Objects.requireNonNull(targetTunnelId, "targetTunnelId");
        }
    }

    record ConfirmTunnelSwitch(int containerId, UUID sessionId, long sequence) implements NodeMenuRequest {
        public ConfirmTunnelSwitch {
            requireEnvelope(containerId, sessionId, sequence);
        }
    }

    private static void writeEnvelope(FriendlyByteBuf buffer, int tag, NodeMenuRequest request) {
        buffer.writeByte(tag);
        buffer.writeVarInt(request.containerId());
        buffer.writeUUID(request.sessionId());
        buffer.writeLong(request.sequence());
    }

    private static void writePageAnchor(FriendlyByteBuf buffer, @Nullable UUID anchor) {
        buffer.writeBoolean(anchor != null);
        if (anchor != null) {
            buffer.writeUUID(anchor);
        }
    }

    private static void requirePageAnchor(@Nullable UUID anchor, boolean backwards) {
        if (backwards && anchor == null) {
            throw new IllegalArgumentException("Backwards node page requires an anchor");
        }
    }

    private static void requireEnvelope(int containerId, UUID sessionId, long sequence) {
        if (containerId < 0 || sequence < 1) {
            throw new IllegalArgumentException("Invalid node-menu request envelope");
        }
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
