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
            FullFilterCodec.requireBodyBound(buffer, TYPE.id());
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
                NetworkTerminalRequest decoded =
                        switch (tag) {
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
                            case 10 ->
                                new SetTunnelEnabled(view, session, sequence, buffer.readUUID(), buffer.readBoolean());
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
                            case 37 -> new OpenFilters(view, session, sequence);
                            case 38 -> new PagePresets(view, session, sequence, buffer.readInt());
                            case 39 ->
                                new OpenPreset(
                                        view,
                                        session,
                                        sequence,
                                        buffer.readUUID(),
                                        buffer.readLong(),
                                        buffer.readInt());
                            case 40 ->
                                new BeginPresetEdit(
                                        view,
                                        session,
                                        sequence,
                                        FilterMenuCodec.readOperation(buffer),
                                        buffer.readBoolean() ? buffer.readUUID() : null,
                                        FilterMenuCodec.readText(buffer));
                            case 41 -> new SavePresetEdit(view, session, sequence, FilterMenuCodec.readText(buffer));
                            case 42 ->
                                new ReadResourceRule(
                                        view,
                                        session,
                                        sequence,
                                        buffer.readUUID(),
                                        buffer.readLong(),
                                        buffer.readUUID());
                            case 43 ->
                                new BeginResourceRule(
                                        view,
                                        session,
                                        sequence,
                                        buffer.readUUID(),
                                        buffer.readBoolean() ? buffer.readUUID() : null,
                                        buffer.readBoolean());
                            case 44 ->
                                new SaveResourceRule(
                                        view,
                                        session,
                                        sequence,
                                        buffer.readBoolean()
                                                ? FullFilterCodec.readIntent(buffer.readByteArray(262144))
                                                : null);
                            case 45 ->
                                new SampleResourceRule(
                                        view,
                                        session,
                                        sequence,
                                        buffer.readResourceLocation(),
                                        buffer.readInt(),
                                        buffer.readInt());
                            case 47 ->
                                new QueryFilterLibrary(
                                        view,
                                        session,
                                        sequence,
                                        buffer.readUtf(256),
                                        buffer.readInt(),
                                        buffer.readLong());
                            case 46 ->
                                new PrepareResourceRuleUpload(
                                        view, session, sequence, buffer.readUUID(), buffer.readInt());
                            default -> throw new DecoderException("Unknown terminal request tag");
                        };
                if (tag >= 37 && buffer.isReadable()) throw new DecoderException("Trailing filter request fields");
                return decoded;
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new DecoderException("Invalid terminal request fields", failure);
            }
        }

        @Override
        public void encode(FriendlyByteBuf buffer, NetworkTerminalRequest request) {
            int start = buffer.writerIndex();
            switch (request) {
                case QueryFilterLibrary query -> {
                    writeEnvelope(buffer, 47, query);
                    buffer.writeUtf(query.query(), 256);
                    buffer.writeInt(query.offset());
                    buffer.writeLong(query.revision());
                }
                case ReadResourceRule read -> {
                    writeEnvelope(buffer, 42, read);
                    buffer.writeUUID(read.presetId());
                    buffer.writeLong(read.revision());
                    buffer.writeUUID(read.ruleId());
                }
                case BeginResourceRule begin -> {
                    writeEnvelope(buffer, 43, begin);
                    buffer.writeUUID(begin.presetId());
                    buffer.writeBoolean(begin.ruleId() != null);
                    if (begin.ruleId() != null) buffer.writeUUID(begin.ruleId());
                    buffer.writeBoolean(begin.remove());
                }
                case SaveResourceRule save -> {
                    writeEnvelope(buffer, 44, save);
                    buffer.writeBoolean(save.intent() != null);
                    if (save.intent() != null) buffer.writeByteArray(FullFilterCodec.intent(save.intent()));
                }
                case SampleResourceRule sample -> {
                    writeEnvelope(buffer, 45, sample);
                    buffer.writeResourceLocation(sample.typeId());
                    buffer.writeInt(sample.slot());
                    buffer.writeInt(sample.tank());
                }
                case PrepareResourceRuleUpload upload -> {
                    writeEnvelope(buffer, 46, upload);
                    buffer.writeUUID(upload.transferId());
                    buffer.writeInt(upload.length());
                }
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
                case OpenFilters open -> writeEnvelope(buffer, 37, open);
                case PagePresets page -> {
                    writeEnvelope(buffer, 38, page);
                    buffer.writeInt(page.offset());
                }
                case OpenPreset open -> {
                    writeEnvelope(buffer, 39, open);
                    buffer.writeUUID(open.presetId());
                    buffer.writeLong(open.revision());
                    buffer.writeInt(open.offset());
                }
                case BeginPresetEdit begin -> {
                    writeEnvelope(buffer, 40, begin);
                    FilterMenuCodec.writeOperation(buffer, begin.operation());
                    buffer.writeBoolean(begin.presetId() != null);
                    if (begin.presetId() != null) buffer.writeUUID(begin.presetId());
                    FilterMenuCodec.writeText(buffer, begin.originalRule());
                }
                case SavePresetEdit save -> {
                    writeEnvelope(buffer, 41, save);
                    FilterMenuCodec.writeText(buffer, save.value());
                }
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
            FullFilterCodec.requireEncodedBodyBound(buffer, start, TYPE.id());
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

    record OpenFilters(UUID viewId, UUID sessionId, long sequence) implements NetworkTerminalRequest {
        public OpenFilters {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record PagePresets(UUID viewId, UUID sessionId, long sequence, int offset) implements NetworkTerminalRequest {
        public PagePresets {
            requireEnvelope(viewId, sessionId, sequence);
            if (offset < 0 || offset > 262144) throw new IllegalArgumentException("Invalid preset offset");
        }
    }

    record OpenPreset(UUID viewId, UUID sessionId, long sequence, UUID presetId, long revision, int offset)
            implements NetworkTerminalRequest {
        public OpenPreset {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(presetId, "presetId");
            if (revision < 0 || offset < 0 || offset > 262144) throw new IllegalArgumentException("Invalid rule page");
        }
    }

    record BeginPresetEdit(
            UUID viewId,
            UUID sessionId,
            long sequence,
            io.github.loongin.omniresonance.filter.PresetEditOperation operation,
            @Nullable UUID presetId,
            String originalRule)
            implements NetworkTerminalRequest {
        public BeginPresetEdit(
                UUID viewId,
                UUID sessionId,
                long sequence,
                io.github.loongin.omniresonance.filter.PresetEditOperation operation,
                @Nullable UUID presetId) {
            this(viewId, sessionId, sequence, operation, presetId, "");
        }

        public BeginPresetEdit {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(originalRule, "originalRule");
            FilterMenuCodec.validateText(originalRule);
            if (operation != io.github.loongin.omniresonance.filter.PresetEditOperation.EDIT_RULE
                    && !originalRule.isEmpty()) throw new IllegalArgumentException("Unexpected original rule");
            if ((operation == io.github.loongin.omniresonance.filter.PresetEditOperation.CREATE) != (presetId == null))
                throw new IllegalArgumentException("Invalid preset edit identity");
        }
    }

    record SavePresetEdit(UUID viewId, UUID sessionId, long sequence, String value) implements NetworkTerminalRequest {
        public SavePresetEdit {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(value, "value");
            if (value.length() > FilterMenuCodec.MAXIMUM_TEXT_BYTES)
                throw new IllegalArgumentException("Invalid filter intent");
            FilterMenuCodec.validateText(value);
        }
    }

    record QueryFilterLibrary(UUID viewId, UUID sessionId, long sequence, String query, int offset, long revision)
            implements NetworkTerminalRequest {
        public QueryFilterLibrary {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(query);
            if (query.length() > 256 || offset < 0 || offset > 262144 || revision < -1)
                throw new IllegalArgumentException("Invalid filter query");
        }
    }

    record ReadResourceRule(UUID viewId, UUID sessionId, long sequence, UUID presetId, long revision, UUID ruleId)
            implements NetworkTerminalRequest {
        public ReadResourceRule {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(presetId);
            Objects.requireNonNull(ruleId);
            if (revision < 0) throw new IllegalArgumentException("Invalid revision");
        }
    }

    record BeginResourceRule(
            UUID viewId,
            UUID sessionId,
            long sequence,
            UUID presetId,
            @Nullable UUID ruleId,
            boolean remove) implements NetworkTerminalRequest {
        public BeginResourceRule {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(presetId);
            if (remove && ruleId == null) throw new IllegalArgumentException("Missing rule");
        }
    }

    record SaveResourceRule(
            UUID viewId,
            UUID sessionId,
            long sequence,
            @Nullable io.github.loongin.omniresonance.filter.ResourceRuleIntent intent)
            implements NetworkTerminalRequest {
        public SaveResourceRule {
            requireEnvelope(viewId, sessionId, sequence);
        }
    }

    record SampleResourceRule(UUID viewId, UUID sessionId, long sequence, ResourceLocation typeId, int slot, int tank)
            implements NetworkTerminalRequest {
        public SampleResourceRule {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(typeId);
            if (slot < 0 || tank < 0) throw new IllegalArgumentException("Invalid inventory sample");
        }
    }

    record PrepareResourceRuleUpload(UUID viewId, UUID sessionId, long sequence, UUID transferId, int length)
            implements NetworkTerminalRequest {
        public PrepareResourceRuleUpload {
            requireEnvelope(viewId, sessionId, sequence);
            Objects.requireNonNull(transferId);
            if (length <= 0
                    || length > ManagementTransferPool.MAXIMUM_OBJECT_BYTES
                    || FullFilterCodec.intentFitsPacket(length))
                throw new IllegalArgumentException("Invalid intent length");
        }
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
