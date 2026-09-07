// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Bounded typed terminal intent; actor, role, revisions and edit tokens remain server-owned. */
public sealed interface NetworkTerminalRequest extends CustomPacketPayload {
    Type<NetworkTerminalRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("omniresonance", "network_terminal_request"));
    StreamCodec<FriendlyByteBuf, NetworkTerminalRequest> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NetworkTerminalRequest decode(FriendlyByteBuf buffer) {
            NetworkSummary.requirePayloadBound(buffer);
            try {
                int tag = buffer.readUnsignedByte();
                if (tag == 0) {
                    return new Open(buffer.readUUID());
                }
                if (tag == 3) {
                    return new Close(buffer.readUUID(), buffer.readUUID());
                }
                UUID view = buffer.readUUID();
                UUID session = buffer.readUUID();
                long sequence = buffer.readLong();
                return switch (tag) {
                    case 1 ->
                        new Page(
                                view,
                                session,
                                sequence,
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 2 -> new Create(view, session, sequence, NetworkSummary.readName(buffer));
                    case 4 -> new OpenNetwork(view, session, sequence, buffer.readUUID());
                    case 5 -> new OpenTunnels(view, session, sequence);
                    case 6 ->
                        new PageTunnels(
                                view,
                                session,
                                sequence,
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 7 -> new BeginCreateTunnel(view, session, sequence, NetworkSummary.readName(buffer));
                    case 8 ->
                        new CreateTunnel(
                                view,
                                session,
                                sequence,
                                NetworkSummary.readName(buffer),
                                NetworkSummary.readName(buffer));
                    case 9 -> new BeginRenameTunnel(view, session, sequence, buffer.readUUID());
                    case 10 -> new SetTunnelEnabled(view, session, sequence, buffer.readUUID(), buffer.readBoolean());
                    case 11 -> new OpenChannels(view, session, sequence, buffer.readUUID());
                    case 12 ->
                        new PageChannels(
                                view,
                                session,
                                sequence,
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 16 -> new RequestDeleteTunnel(view, session, sequence, buffer.readUUID());
                    case 18 -> new ConfirmDelete(view, session, sequence);
                    case 19 -> new Heartbeat(view, session, sequence);
                    case 20 -> new CancelEdit(view, session, sequence);
                    case 21 -> new Back(view, session, sequence);
                    case 22 -> new RenameTunnel(view, session, sequence, NetworkSummary.readName(buffer));
                    case 23 -> new OpenTunnelSettings(view, session, sequence);
                    case 24 -> new OpenMembers(view, session, sequence);
                    case 25 ->
                        new PageMembers(
                                view,
                                session,
                                sequence,
                                buffer.readBoolean() ? buffer.readUUID() : null,
                                buffer.readBoolean());
                    case 26 -> new OpenAdministratorCandidates(view, session, sequence);
                    case 27 ->
                        new PageAdministratorCandidates(
                                view, session, sequence, buffer.readUUID(), buffer.readVarInt());
                    case 28 -> new AddAdministrator(view, session, sequence, buffer.readUUID());
                    case 29 -> new RequestRemoveAdministrator(view, session, sequence, buffer.readUUID());
                    case 30 -> new ConfirmRemoveAdministrator(view, session, sequence);
                    case 31 -> new OpenNetworkSettings(view, session, sequence);
                    case 32 -> new BeginRenameNetwork(view, session, sequence);
                    case 33 -> new RenameNetwork(view, session, sequence, NetworkSummary.readName(buffer));
                    case 34 -> new SetDefaultNetwork(view, session, sequence);
                    case 35 -> new RequestDeleteNetwork(view, session, sequence);
                    case 36 -> new ConfirmDeleteNetwork(view, session, sequence);
                    default -> throw new DecoderException("Unknown terminal request tag");
                };
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new DecoderException("Invalid terminal request fields", failure);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, NetworkTerminalRequest request) {
            int start = buffer.writerIndex();
            switch (request) {
                case Open open -> {
                    buffer.writeByte(0);
                    buffer.writeUUID(open.viewId());
                }
                case Close close -> {
                    buffer.writeByte(3);
                    buffer.writeUUID(close.viewId());
                    buffer.writeUUID(close.sessionId());
                }
                case Page page -> writePage(buffer, 1, page, page.anchor(), page.backwards());
                case Create create -> {
                    writeEnvelope(buffer, 2, create);
                    NetworkSummary.writeName(buffer, create.name());
                }
                case OpenNetwork open -> {
                    writeEnvelope(buffer, 4, open);
                    buffer.writeUUID(open.networkId());
                }
                case OpenTunnels open -> writeEnvelope(buffer, 5, open);
                case OpenMembers open -> writeEnvelope(buffer, 24, open);
                case PageMembers page -> writePage(buffer, 25, page, page.anchor(), page.backwards());
                case OpenAdministratorCandidates open -> writeEnvelope(buffer, 26, open);
                case PageAdministratorCandidates page -> {
                    writeEnvelope(buffer, 27, page);
                    buffer.writeUUID(page.snapshotId());
                    buffer.writeVarInt(page.offset());
                }
                case AddAdministrator add -> {
                    writeEnvelope(buffer, 28, add);
                    buffer.writeUUID(add.target());
                }
                case RequestRemoveAdministrator remove -> {
                    writeEnvelope(buffer, 29, remove);
                    buffer.writeUUID(remove.target());
                }
                case ConfirmRemoveAdministrator remove -> writeEnvelope(buffer, 30, remove);
                case OpenNetworkSettings open -> writeEnvelope(buffer, 31, open);
                case BeginRenameNetwork begin -> writeEnvelope(buffer, 32, begin);
                case RenameNetwork rename -> {
                    writeEnvelope(buffer, 33, rename);
                    NetworkSummary.writeName(buffer, rename.name());
                }
                case SetDefaultNetwork set -> writeEnvelope(buffer, 34, set);
                case RequestDeleteNetwork delete -> writeEnvelope(buffer, 35, delete);
                case ConfirmDeleteNetwork delete -> writeEnvelope(buffer, 36, delete);
                case PageTunnels page -> writePage(buffer, 6, page, page.anchor(), page.backwards());
                case BeginCreateTunnel begin -> {
                    writeEnvelope(buffer, 7, begin);
                    NetworkSummary.writeName(buffer, begin.suggestionPrefix());
                }
                case CreateTunnel create -> {
                    writeEnvelope(buffer, 8, create);
                    NetworkSummary.writeName(buffer, create.tunnelName());
                    NetworkSummary.writeName(buffer, create.initialChannelName());
                }
                case BeginRenameTunnel begin -> {
                    writeEnvelope(buffer, 9, begin);
                    buffer.writeUUID(begin.tunnelId());
                }
                case SetTunnelEnabled enabled -> {
                    writeEnvelope(buffer, 10, enabled);
                    buffer.writeUUID(enabled.tunnelId());
                    buffer.writeBoolean(enabled.enabled());
                }
                case OpenChannels open -> {
                    writeEnvelope(buffer, 11, open);
                    buffer.writeUUID(open.tunnelId());
                }
                case PageChannels page -> writePage(buffer, 12, page, page.anchor(), page.backwards());
                case RequestDeleteTunnel delete -> {
                    writeEnvelope(buffer, 16, delete);
                    buffer.writeUUID(delete.tunnelId());
                }
                case ConfirmDelete confirm -> writeEnvelope(buffer, 18, confirm);
                case Heartbeat heartbeat -> writeEnvelope(buffer, 19, heartbeat);
                case CancelEdit cancel -> writeEnvelope(buffer, 20, cancel);
                case Back back -> writeEnvelope(buffer, 21, back);
                case RenameTunnel rename -> {
                    writeEnvelope(buffer, 22, rename);
                    NetworkSummary.writeName(buffer, rename.name());
                }
                case OpenTunnelSettings open -> writeEnvelope(buffer, 23, open);
            }
            NetworkSummary.requireEncodedBound(buffer, start);
        }
    };

    UUID viewId();

    default @Nullable UUID sessionId() {
        return null;
    }

    default long sequence() {
        return 0;
    }

    @Override
    default Type<NetworkTerminalRequest> type() {
        return TYPE;
    }

    record Open(UUID viewId) implements NetworkTerminalRequest {
        public Open {
            Objects.requireNonNull(viewId, "viewId");
        }
    }

    record Page(
            UUID viewId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NetworkTerminalRequest {
        public Page {
            requireEnvelope(viewId, sessionId, sequence);
            requirePage(anchor, backwards);
        }
    }

    record Create(UUID viewId, UUID sessionId, long sequence, String name) implements NetworkTerminalRequest {
        public Create {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record OpenNetwork(UUID viewId, UUID sessionId, long sequence, UUID networkId) implements NetworkTerminalRequest {
        public OpenNetwork {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(networkId, "networkId");
        }
    }

    record OpenTunnels(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public OpenTunnels {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record PageTunnels(
            UUID viewId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NetworkTerminalRequest {
        public PageTunnels {
            requireEnvelope(viewId, sessionId, sequence);
            requirePage(anchor, backwards);
        }
    }

    record BeginCreateTunnel(UUID viewId, UUID sessionId, long sequence, String suggestionPrefix)
            implements NetworkTerminalRequest {
        public BeginCreateTunnel {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(suggestionPrefix, "suggestionPrefix");
        }
    }

    record CreateTunnel(UUID viewId, UUID sessionId, long sequence, String tunnelName, String initialChannelName)
            implements NetworkTerminalRequest {
        public CreateTunnel {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(tunnelName, "tunnelName");
            Objects.requireNonNull(initialChannelName, "initialChannelName");
        }
    }

    record RenameTunnel(UUID viewId, UUID sessionId, long sequence, String name) implements NetworkTerminalRequest {
        public RenameTunnel {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record BeginRenameTunnel(UUID viewId, UUID sessionId, long sequence, UUID tunnelId)
            implements NetworkTerminalRequest {
        public BeginRenameTunnel {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(tunnelId, "tunnelId");
        }
    }

    record SetTunnelEnabled(UUID viewId, UUID sessionId, long sequence, UUID tunnelId, boolean enabled)
            implements NetworkTerminalRequest {
        public SetTunnelEnabled {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(tunnelId, "tunnelId");
        }
    }

    record OpenChannels(UUID viewId, UUID sessionId, long sequence, UUID tunnelId) implements NetworkTerminalRequest {
        public OpenChannels {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(tunnelId, "tunnelId");
        }
    }

    record PageChannels(
            UUID viewId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NetworkTerminalRequest {
        public PageChannels {
            requireEnvelope(viewId, sessionId, sequence);
            requirePage(anchor, backwards);
        }
    }

    record RequestDeleteTunnel(UUID viewId, UUID sessionId, long sequence, UUID tunnelId)
            implements NetworkTerminalRequest {
        public RequestDeleteTunnel {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(tunnelId, "tunnelId");
        }
    }

    record ConfirmDelete(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public ConfirmDelete {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record Heartbeat(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public Heartbeat {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record CancelEdit(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public CancelEdit {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record Back(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public Back {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record OpenTunnelSettings(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public OpenTunnelSettings {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record Close(UUID viewId, UUID sessionId) implements NetworkTerminalRequest {
        public Close {
            Objects.requireNonNull(viewId, "viewId");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record OpenMembers(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public OpenMembers {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record PageMembers(
            UUID viewId,
            UUID sessionId,
            long sequence,
            @Nullable UUID anchor,
            boolean backwards) implements NetworkTerminalRequest {
        public PageMembers {
            requireEnvelope(viewId, sessionId, sequence);
            requirePage(anchor, backwards);
        }
    }

    record OpenAdministratorCandidates(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public OpenAdministratorCandidates {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record PageAdministratorCandidates(UUID viewId, UUID sessionId, long sequence, UUID snapshotId, int offset)
            implements NetworkTerminalRequest {
        public PageAdministratorCandidates {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(snapshotId, "snapshotId");
            if (offset < 0 || offset > 262144) throw new IllegalArgumentException("Invalid candidate offset");
        }
    }

    record AddAdministrator(UUID viewId, UUID sessionId, long sequence, UUID target) implements NetworkTerminalRequest {
        public AddAdministrator {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(target, "target");
        }
    }

    record RequestRemoveAdministrator(UUID viewId, UUID sessionId, long sequence, UUID target)
            implements NetworkTerminalRequest {
        public RequestRemoveAdministrator {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(target, "target");
        }
    }

    record ConfirmRemoveAdministrator(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public ConfirmRemoveAdministrator {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record OpenNetworkSettings(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public OpenNetworkSettings {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record BeginRenameNetwork(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public BeginRenameNetwork {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record RenameNetwork(UUID viewId, UUID sessionId, long sequence, String name) implements NetworkTerminalRequest {
        public RenameNetwork {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(name, "name");
        }
    }

    record SetDefaultNetwork(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public SetDefaultNetwork {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record RequestDeleteNetwork(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public RequestDeleteNetwork {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record ConfirmDeleteNetwork(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public ConfirmDeleteNetwork {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    private static void writeEnvelope(FriendlyByteBuf buffer, int tag, NetworkTerminalRequest request) {
        buffer.writeByte(tag);
        buffer.writeUUID(request.viewId());
        buffer.writeUUID(request.sessionId());
        buffer.writeLong(request.sequence());
    }

    private static void writePage(
            FriendlyByteBuf buffer, int tag, NetworkTerminalRequest request, @Nullable UUID anchor, boolean backwards) {
        writeEnvelope(buffer, tag, request);
        buffer.writeBoolean(anchor != null);
        if (anchor != null) {
            buffer.writeUUID(anchor);
        }
        buffer.writeBoolean(backwards);
    }

    private static void requireEnvelope(UUID viewId, UUID sessionId, long sequence) {
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (sequence < 1) {
            throw new IllegalArgumentException("Terminal request sequence must be positive");
        }
    }

    private static void requirePage(@Nullable UUID anchor, boolean backwards) {
        if (backwards && anchor == null) {
            throw new IllegalArgumentException("Backwards page requires an anchor");
        }
    }
}
