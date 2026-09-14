// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.persistence;

import io.github.loongin.omniresonance.network.DirectNodeBinding;
import io.github.loongin.omniresonance.network.DomainNodeConfiguration;
import io.github.loongin.omniresonance.network.ManagedName;
import io.github.loongin.omniresonance.network.NetworkChannelRecord;
import io.github.loongin.omniresonance.network.NetworkTunnelRecord;
import io.github.loongin.omniresonance.network.TransferDirection;
import io.github.loongin.omniresonance.network.WorkingFaces;
import io.github.loongin.omniresonance.node.NetworkNodeRecord;
import io.github.loongin.omniresonance.node.NodeMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Strict legacy/v8 network topology codec; all decoded values and encoded tags are independently owned. */
final class NetworkTopologyNbt {
    static final int MAXIMUM_TUNNELS = 65535;
    static final int MAXIMUM_MANAGED_ENTRIES = 262144;
    static final int MAXIMUM_BINDINGS_PER_NODE = 1024;
    private static final Set<String> TUNNEL_FIELDS =
            Set.of("tunnel_id", "tunnel_number", "name", "revision", "enabled", "last_channel_number");
    private static final Set<String> CHANNEL_FIELDS =
            Set.of("channel_id", "tunnel_id", "channel_number", "name", "revision");
    private static final Set<String> DIRECT_FIELDS = Set.of("node_id", "channel_id", "direction");
    private static final Set<String> DIRECT_V6_FIELDS = Set.of("node_id", "channel_id", "direction", "item_policy");
    private static final Set<String> DIRECT_V7_FIELDS =
            Set.of("node_id", "channel_id", "direction", "item_policy", "working_face_mask", "working_face_attached");
    private static final Set<String> DIRECT_V8_FIELDS = Set.of(
            "node_id", "channel_id", "direction", "resource_policy", "working_face_mask", "working_face_attached");
    private static final Set<String> DOMAIN_FIELDS = Set.of("node_id", "direction");
    private static final Comparator<NetworkTunnelRecord> TUNNEL_ORDER =
            Comparator.comparingLong(NetworkTunnelRecord::tunnelNumber).thenComparing(NetworkTunnelRecord::tunnelId);
    private static final Comparator<NetworkChannelRecord> CHANNEL_ORDER = Comparator.comparing(
                    NetworkChannelRecord::tunnelId)
            .thenComparingLong(NetworkChannelRecord::channelNumber)
            .thenComparing(NetworkChannelRecord::channelId);
    private static final Comparator<DirectNodeBinding> DIRECT_ORDER =
            Comparator.comparing(DirectNodeBinding::nodeId).thenComparing(DirectNodeBinding::channelId);
    private static final Comparator<DomainNodeConfiguration> DOMAIN_ORDER =
            Comparator.comparing(DomainNodeConfiguration::nodeId);

    private NetworkTopologyNbt() {}

    static Decoded empty() {
        return new Decoded(0, 0, Map.of(), Map.of(), List.of(), Map.of());
    }

    static Decoded decode(
            CompoundTag tag,
            Map<UUID, NetworkNodeRecord> nodes,
            Set<net.minecraft.resources.ResourceLocation> registeredTypes) {
        ManagedDataNbt.requireType(tag, "last_tunnel_number", Tag.TAG_LONG);
        ManagedDataNbt.requireType(tag, "topology_revision", Tag.TAG_LONG);
        long lastTunnelNumber = tag.getLong("last_tunnel_number");
        long topologyRevision = tag.getLong("topology_revision");
        if (lastTunnelNumber < 0 || topologyRevision < 0) {
            throw new IllegalArgumentException("Negative topology number or revision");
        }

        Map<UUID, NetworkTunnelRecord> tunnels = decodeTunnels(tag, lastTunnelNumber);
        Map<UUID, NetworkChannelRecord> channels = decodeChannels(tag, tunnels);
        requireEveryTunnelHasChannel(tunnels, channels);
        List<DirectNodeBinding> directBindings = decodeDirectBindings(tag, nodes, channels, registeredTypes);
        Map<UUID, DomainNodeConfiguration> domains = decodeDomains(tag, nodes);
        return new Decoded(lastTunnelNumber, topologyRevision, tunnels, channels, directBindings, domains);
    }

    static List<NetworkTunnelRecord> sortedTunnels(Collection<NetworkTunnelRecord> values) {
        List<NetworkTunnelRecord> sorted = new ArrayList<>(values);
        sorted.sort(TUNNEL_ORDER);
        return List.copyOf(sorted);
    }

    static List<NetworkChannelRecord> sortedChannels(Collection<NetworkChannelRecord> values) {
        List<NetworkChannelRecord> sorted = new ArrayList<>(values);
        sorted.sort(CHANNEL_ORDER);
        return List.copyOf(sorted);
    }

    static List<DirectNodeBinding> sortedDirectBindings(Collection<DirectNodeBinding> values) {
        List<DirectNodeBinding> sorted = new ArrayList<>(values);
        sorted.sort(DIRECT_ORDER);
        return List.copyOf(sorted);
    }

    static ListTag encodeTunnels(Collection<NetworkTunnelRecord> values) {
        ListTag encoded = new ListTag();
        for (NetworkTunnelRecord tunnel : sortedTunnels(values)) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("tunnel_id", tunnel.tunnelId());
            tag.putLong("tunnel_number", tunnel.tunnelNumber());
            tag.putString("name", tunnel.name().value());
            tag.putLong("revision", tunnel.revision());
            tag.putBoolean("enabled", tunnel.enabled());
            tag.putLong("last_channel_number", tunnel.lastChannelNumber());
            encoded.add(tag);
        }
        return encoded;
    }

    static ListTag encodeChannels(Collection<NetworkChannelRecord> values) {
        ListTag encoded = new ListTag();
        for (NetworkChannelRecord channel : sortedChannels(values)) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("channel_id", channel.channelId());
            tag.putUUID("tunnel_id", channel.tunnelId());
            tag.putLong("channel_number", channel.channelNumber());
            tag.putString("name", channel.name().value());
            tag.putLong("revision", channel.revision());
            encoded.add(tag);
        }
        return encoded;
    }

    static ListTag encodeDirectBindings(Collection<DirectNodeBinding> values) {
        ListTag encoded = new ListTag();
        for (DirectNodeBinding binding : sortedDirectBindings(values)) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("node_id", binding.nodeId());
            tag.putUUID("channel_id", binding.channelId());
            tag.putString("direction", binding.direction().serializedName());
            tag.put("resource_policy", ResourcePolicyNbt.encode(binding.storedPolicy()));
            tag.putInt("working_face_mask", binding.workingFaces().mask());
            tag.putBoolean("working_face_attached", binding.workingFaces().attached());
            encoded.add(tag);
        }
        return encoded;
    }

    static ListTag encodeDomains(Collection<DomainNodeConfiguration> values) {
        List<DomainNodeConfiguration> sorted = new ArrayList<>(values);
        sorted.sort(DOMAIN_ORDER);
        ListTag encoded = new ListTag();
        for (DomainNodeConfiguration configuration : sorted) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("node_id", configuration.nodeId());
            tag.putString("direction", configuration.direction().serializedName());
            encoded.add(tag);
        }
        return encoded;
    }

    private static Map<UUID, NetworkTunnelRecord> decodeTunnels(CompoundTag root, long lastTunnelNumber) {
        ListTag entries = readCompoundList(root, "tunnels", MAXIMUM_TUNNELS);
        Map<UUID, NetworkTunnelRecord> tunnels = new HashMap<>(entries.size());
        Set<Long> numbers = new HashSet<>(entries.size());
        Set<String> names = new HashSet<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            requireExactFields(tag, TUNNEL_FIELDS, "tunnel");
            UUID id = ManagedDataNbt.readUuid(tag, "tunnel_id");
            long number = readLong(tag, "tunnel_number");
            ManagedName name = ManagedDataNbt.readName(tag);
            long revision = readLong(tag, "revision");
            boolean enabled = readBoolean(tag, "enabled");
            long lastChannelNumber = readLong(tag, "last_channel_number");
            NetworkTunnelRecord tunnel =
                    new NetworkTunnelRecord(id, number, name, revision, enabled, lastChannelNumber);
            if (number > lastTunnelNumber
                    || tunnels.putIfAbsent(id, tunnel) != null
                    || !numbers.add(number)
                    || !names.add(name.uniquenessKey())) {
                throw new IllegalArgumentException("Duplicate or unallocated network tunnel");
            }
        }
        return tunnels;
    }

    private static Map<UUID, NetworkChannelRecord> decodeChannels(
            CompoundTag root, Map<UUID, NetworkTunnelRecord> tunnels) {
        ListTag entries = readCompoundList(root, "channels", MAXIMUM_MANAGED_ENTRIES);
        Map<UUID, NetworkChannelRecord> channels = new HashMap<>(entries.size());
        Set<ScopedNumber> numbers = new HashSet<>(entries.size());
        Set<ScopedName> names = new HashSet<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            requireExactFields(tag, CHANNEL_FIELDS, "channel");
            UUID id = ManagedDataNbt.readUuid(tag, "channel_id");
            UUID tunnelId = ManagedDataNbt.readUuid(tag, "tunnel_id");
            long number = readLong(tag, "channel_number");
            ManagedName name = ManagedDataNbt.readName(tag);
            long revision = readLong(tag, "revision");
            NetworkTunnelRecord tunnel = tunnels.get(tunnelId);
            NetworkChannelRecord channel = new NetworkChannelRecord(id, tunnelId, number, name, revision);
            if (tunnel == null
                    || number > tunnel.lastChannelNumber()
                    || channels.putIfAbsent(id, channel) != null
                    || !numbers.add(new ScopedNumber(tunnelId, number))
                    || !names.add(new ScopedName(tunnelId, name.uniquenessKey()))) {
                throw new IllegalArgumentException("Duplicate, dangling or unallocated network channel");
            }
        }
        return channels;
    }

    private static void requireEveryTunnelHasChannel(
            Map<UUID, NetworkTunnelRecord> tunnels, Map<UUID, NetworkChannelRecord> channels) {
        Set<UUID> populated = new HashSet<>();
        for (NetworkChannelRecord channel : channels.values()) {
            populated.add(channel.tunnelId());
        }
        if (!populated.containsAll(tunnels.keySet())) {
            throw new IllegalArgumentException("Every network tunnel must contain a channel");
        }
    }

    private static List<DirectNodeBinding> decodeDirectBindings(
            CompoundTag root,
            Map<UUID, NetworkNodeRecord> nodes,
            Map<UUID, NetworkChannelRecord> channels,
            Set<net.minecraft.resources.ResourceLocation> registeredTypes) {
        ListTag entries = readCompoundList(root, "direct_bindings", MAXIMUM_MANAGED_ENTRIES);
        List<DirectNodeBinding> bindings = new ArrayList<>(entries.size());
        Set<BindingKey> keys = new HashSet<>(entries.size());
        Map<UUID, Integer> perNode = new HashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            boolean current = ManagedDataNbt.readSchemaVersion(root) >= 6;
            boolean facesPresent = ManagedDataNbt.readSchemaVersion(root) >= 7;
            requireExactFields(
                    tag,
                    ManagedDataNbt.readSchemaVersion(root) >= 8
                            ? DIRECT_V8_FIELDS
                            : facesPresent ? DIRECT_V7_FIELDS : current ? DIRECT_V6_FIELDS : DIRECT_FIELDS,
                    "direct binding");
            UUID nodeId = ManagedDataNbt.readUuid(tag, "node_id");
            UUID channelId = ManagedDataNbt.readUuid(tag, "channel_id");
            TransferDirection direction = readDirection(tag);
            NetworkNodeRecord node = nodes.get(nodeId);
            int count = perNode.merge(nodeId, 1, Integer::sum);
            if (node == null
                    || node.mode() != NodeMode.DIRECT
                    || !channels.containsKey(channelId)
                    || !keys.add(new BindingKey(nodeId, channelId))
                    || count > MAXIMUM_BINDINGS_PER_NODE) {
                throw new IllegalArgumentException("Invalid direct-node binding relationship");
            }
            if (ManagedDataNbt.readSchemaVersion(root) >= 8)
                ManagedDataNbt.requireType(tag, "resource_policy", Tag.TAG_COMPOUND);
            WorkingFaces faces = WorkingFaces.attachedFace();
            if (facesPresent) {
                ManagedDataNbt.requireType(tag, "working_face_mask", Tag.TAG_INT);
                faces = new WorkingFaces(tag.getInt("working_face_mask"), readBoolean(tag, "working_face_attached"));
            }
            faces.validate(node.form());
            bindings.add(new DirectNodeBinding(
                    nodeId,
                    channelId,
                    ManagedDataNbt.readSchemaVersion(root) >= 8
                            ? ResourcePolicyNbt.decode(tag.getCompound("resource_policy"), direction, registeredTypes)
                            : new io.github.loongin.omniresonance.transfer.StoredResourcePolicy(
                                    io.github.loongin.omniresonance.transfer.ResourceTransferPolicy.legacy(
                                            current
                                                    ? ItemPolicyNbt.decode(tag, direction)
                                                    : io.github.loongin.omniresonance.transfer.ItemTransferPolicy
                                                            .defaults(direction)),
                                    Map.of()),
                    faces));
        }
        return List.copyOf(bindings);
    }

    private static Map<UUID, DomainNodeConfiguration> decodeDomains(
            CompoundTag root, Map<UUID, NetworkNodeRecord> nodes) {
        ListTag entries = readCompoundList(root, "domain_configurations", MAXIMUM_MANAGED_ENTRIES);
        Map<UUID, DomainNodeConfiguration> domains = new HashMap<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            requireExactFields(tag, DOMAIN_FIELDS, "domain configuration");
            UUID nodeId = ManagedDataNbt.readUuid(tag, "node_id");
            DomainNodeConfiguration configuration = new DomainNodeConfiguration(nodeId, readDirection(tag));
            NetworkNodeRecord node = nodes.get(nodeId);
            if (node == null || node.mode() != NodeMode.DOMAIN || domains.putIfAbsent(nodeId, configuration) != null) {
                throw new IllegalArgumentException("Invalid domain-node configuration relationship");
            }
        }
        return domains;
    }

    private static ListTag readCompoundList(CompoundTag root, String key, int maximum) {
        ManagedDataNbt.requireType(root, key, Tag.TAG_LIST);
        ListTag entries = (ListTag) root.get(key);
        int size = entries.size();
        if (size > maximum) {
            throw new IllegalArgumentException("Topology collection exceeds its hard limit: " + key);
        }
        if (entries.getElementType() != Tag.TAG_COMPOUND && !(size == 0 && entries.getElementType() == Tag.TAG_END)) {
            throw new IllegalArgumentException("Expected compound topology list: " + key);
        }
        return entries;
    }

    private static long readLong(CompoundTag tag, String key) {
        ManagedDataNbt.requireType(tag, key, Tag.TAG_LONG);
        return tag.getLong(key);
    }

    private static boolean readBoolean(CompoundTag tag, String key) {
        ManagedDataNbt.requireType(tag, key, Tag.TAG_BYTE);
        byte value = tag.getByte(key);
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException("Invalid boolean topology field: " + key);
        }
        return value == 1;
    }

    private static TransferDirection readDirection(CompoundTag tag) {
        ManagedDataNbt.requireType(tag, "direction", Tag.TAG_STRING);
        return TransferDirection.fromSerializedName(tag.getString("direction"));
    }

    private static void requireExactFields(CompoundTag tag, Set<String> fields, String kind) {
        if (!tag.getAllKeys().equals(fields)) {
            throw new IllegalArgumentException("Missing or unexpected " + kind + " fields");
        }
    }

    record Decoded(
            long lastTunnelNumber,
            long topologyRevision,
            Map<UUID, NetworkTunnelRecord> tunnels,
            Map<UUID, NetworkChannelRecord> channels,
            List<DirectNodeBinding> directBindings,
            Map<UUID, DomainNodeConfiguration> domainConfigurations) {
        Decoded {
            tunnels = Map.copyOf(tunnels);
            channels = Map.copyOf(channels);
            directBindings = List.copyOf(directBindings);
            domainConfigurations = Map.copyOf(domainConfigurations);
        }
    }

    private record ScopedNumber(UUID tunnelId, long number) {}

    private record ScopedName(UUID tunnelId, String name) {}

    private record BindingKey(UUID nodeId, UUID channelId) {}
}
