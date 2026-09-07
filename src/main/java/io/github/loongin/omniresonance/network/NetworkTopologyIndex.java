// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-owned network-local topology indexes. Queries copy only requested snapshots; mutations update every
 * affected index incrementally and perform no persistence, world access, permission checks or simulation.
 */
public final class NetworkTopologyIndex {
    public static final int MAXIMUM_PAGE_ENTRIES = 128;
    private static final int MAXIMUM_TUNNELS = 65535;
    private static final int MAXIMUM_MANAGED_ENTRIES = 262144;
    private static final Comparator<NetworkTunnelRecord> TUNNEL_ORDER =
            Comparator.comparingLong(NetworkTunnelRecord::tunnelNumber).thenComparing(NetworkTunnelRecord::tunnelId);
    private static final Comparator<NetworkChannelRecord> CHANNEL_ORDER = Comparator.comparingLong(
                    NetworkChannelRecord::channelNumber)
            .thenComparing(NetworkChannelRecord::channelId);

    private final Thread owningThread = Thread.currentThread();
    private final Map<UUID, NetworkTunnelRecord> tunnels = new HashMap<>();
    private final Map<String, UUID> tunnelsByName = new HashMap<>();
    private final Map<Long, UUID> tunnelsByNumber = new HashMap<>();
    private final NavigableSet<NetworkTunnelRecord> tunnelOrder = new TreeSet<>(TUNNEL_ORDER);
    private final Map<UUID, NetworkChannelRecord> channels = new HashMap<>();
    private final Map<UUID, NavigableSet<NetworkChannelRecord>> channelsByTunnel = new HashMap<>();
    private final Map<ScopedName, UUID> channelsByName = new HashMap<>();
    private final Map<BindingKey, DirectNodeBinding> bindings = new HashMap<>();
    private final Map<UUID, Set<BindingKey>> bindingsByNode = new HashMap<>();
    private final Map<UUID, Set<BindingKey>> bindingsByChannel = new HashMap<>();
    private final Map<UUID, DomainNodeConfiguration> domains = new HashMap<>();

    /** Copies one already-validated decoded topology and independently verifies index uniqueness. */
    public NetworkTopologyIndex(
            Collection<NetworkTunnelRecord> tunnels,
            Collection<NetworkChannelRecord> channels,
            Collection<DirectNodeBinding> bindings,
            Collection<DomainNodeConfiguration> domains) {
        Objects.requireNonNull(tunnels, "tunnels");
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(domains, "domains");
        for (NetworkTunnelRecord tunnel : tunnels) {
            addTunnel(Objects.requireNonNull(tunnel, "tunnel"));
        }
        for (NetworkChannelRecord channel : channels) {
            addChannel(Objects.requireNonNull(channel, "channel"));
        }
        for (NetworkTunnelRecord tunnel : this.tunnels.values()) {
            if (channelCount(tunnel.tunnelId()) == 0) {
                throw new IllegalArgumentException("Network tunnel has no channel");
            }
        }
        for (DirectNodeBinding binding : bindings) {
            DirectNodeBinding required = Objects.requireNonNull(binding, "binding");
            if (findBinding(required.nodeId(), required.channelId()).isPresent()) {
                throw new IllegalArgumentException("Duplicate direct binding key");
            }
            putBinding(required);
        }
        for (DomainNodeConfiguration domain : domains) {
            DomainNodeConfiguration required = Objects.requireNonNull(domain, "domain");
            if (this.domains.containsKey(required.nodeId())) {
                throw new IllegalArgumentException("Duplicate domain configuration key");
            }
            putDomain(required);
        }
    }

    public int tunnelCount() {
        requireOwningThread();
        return tunnels.size();
    }

    public int channelCount(UUID tunnelId) {
        requireOwningThread();
        NavigableSet<NetworkChannelRecord> values = channelsByTunnel.get(Objects.requireNonNull(tunnelId, "tunnelId"));
        return values == null ? 0 : values.size();
    }

    /** Returns the total channel count in O(1) without copying or changing topology state. */
    public int channelCount() {
        requireOwningThread();
        return channels.size();
    }

    /** Returns the total direct-binding count in O(1) without copying or changing topology state. */
    public int bindingCount() {
        requireOwningThread();
        return bindings.size();
    }

    /** Returns the total domain-configuration count in O(1) without copying or changing topology state. */
    public int domainCount() {
        requireOwningThread();
        return domains.size();
    }

    public int bindingCount(UUID nodeId) {
        requireOwningThread();
        Set<BindingKey> values = bindingsByNode.get(Objects.requireNonNull(nodeId, "nodeId"));
        return values == null ? 0 : values.size();
    }

    public int bindingCountForTunnel(UUID tunnelId) {
        requireOwningThread();
        int count = 0;
        for (NetworkChannelRecord channel : channels(Objects.requireNonNull(tunnelId, "tunnelId"))) {
            count = Math.addExact(
                    count,
                    bindingsByChannel
                            .getOrDefault(channel.channelId(), Set.of())
                            .size());
        }
        return count;
    }

    public DirectionCounts directionCounts(UUID channelId) {
        requireOwningThread();
        Objects.requireNonNull(channelId, "channelId");
        int inputs = 0;
        int outputs = 0;
        for (BindingKey key : bindingsByChannel.getOrDefault(channelId, Set.of())) {
            if (bindings.get(key).direction() == TransferDirection.INPUT) {
                inputs = Math.incrementExact(inputs);
            } else {
                outputs = Math.incrementExact(outputs);
            }
        }
        return new DirectionCounts(inputs, outputs);
    }

    public boolean containsTunnelName(ManagedName name) {
        requireOwningThread();
        return tunnelsByName.containsKey(Objects.requireNonNull(name, "name").uniquenessKey());
    }

    public boolean containsChannelName(UUID tunnelId, ManagedName name) {
        requireOwningThread();
        return channelsByName.containsKey(new ScopedName(
                Objects.requireNonNull(tunnelId, "tunnelId"),
                Objects.requireNonNull(name, "name").uniquenessKey()));
    }

    /** Returns the smallest unoccupied localized tunnel suggestion without reserving a number or mutating state. */
    public ManagedName suggestedTunnelName(ManagedNamePrefix prefix) {
        requireOwningThread();
        Objects.requireNonNull(prefix, "prefix");
        for (long number = 1; number <= MAXIMUM_TUNNELS; number++) {
            ManagedName candidate = prefix.numbered(number);
            if (!tunnelsByName.containsKey(candidate.uniquenessKey())) {
                return candidate;
            }
        }
        throw new IllegalStateException("No tunnel suggestion is available");
    }

    /** Returns the smallest unoccupied localized channel suggestion within one existing tunnel. */
    public ManagedName suggestedChannelName(UUID tunnelId, ManagedNamePrefix prefix) {
        requireOwningThread();
        Objects.requireNonNull(tunnelId, "tunnelId");
        Objects.requireNonNull(prefix, "prefix");
        if (!tunnels.containsKey(tunnelId)) {
            throw new IllegalArgumentException("Missing tunnel for channel suggestion");
        }
        for (long number = 1; number <= MAXIMUM_TUNNELS; number++) {
            ManagedName candidate = prefix.numbered(number);
            if (!channelsByName.containsKey(new ScopedName(tunnelId, candidate.uniquenessKey()))) {
                return candidate;
            }
        }
        throw new IllegalStateException("No channel suggestion is available");
    }

    public Optional<NetworkTunnelRecord> findTunnel(UUID tunnelId) {
        requireOwningThread();
        return Optional.ofNullable(tunnels.get(Objects.requireNonNull(tunnelId, "tunnelId")));
    }

    public Optional<NetworkChannelRecord> findChannel(UUID channelId) {
        requireOwningThread();
        return Optional.ofNullable(channels.get(Objects.requireNonNull(channelId, "channelId")));
    }

    public Optional<DirectNodeBinding> findBinding(UUID nodeId, UUID channelId) {
        requireOwningThread();
        return Optional.ofNullable(bindings.get(new BindingKey(
                Objects.requireNonNull(nodeId, "nodeId"), Objects.requireNonNull(channelId, "channelId"))));
    }

    /**
     * Returns the tunnel derived from any one of a node's direct bindings, or empty when it has none.
     * This owning-thread query neither simulates nor mutates topology; null, wrong-thread access and broken internal
     * references reject without changing indexes.
     */
    public Optional<UUID> directTunnelId(UUID nodeId) {
        requireOwningThread();
        Set<BindingKey> keys = bindingsByNode.get(Objects.requireNonNull(nodeId, "nodeId"));
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }
        DirectNodeBinding binding =
                Objects.requireNonNull(bindings.get(keys.iterator().next()), "binding");
        NetworkChannelRecord channel = Objects.requireNonNull(channels.get(binding.channelId()), "channel");
        return Optional.of(channel.tunnelId());
    }

    public Optional<DomainNodeConfiguration> domain(UUID nodeId) {
        requireOwningThread();
        return Optional.ofNullable(domains.get(Objects.requireNonNull(nodeId, "nodeId")));
    }

    public List<NetworkTunnelRecord> tunnels() {
        requireOwningThread();
        return List.copyOf(tunnelOrder);
    }

    public List<NetworkChannelRecord> channels(UUID tunnelId) {
        requireOwningThread();
        NavigableSet<NetworkChannelRecord> values = channelsByTunnel.get(Objects.requireNonNull(tunnelId, "tunnelId"));
        return values == null ? List.of() : List.copyOf(values);
    }

    public List<DirectNodeBinding> bindings(UUID nodeId) {
        requireOwningThread();
        Set<BindingKey> keys = bindingsByNode.get(Objects.requireNonNull(nodeId, "nodeId"));
        if (keys == null) {
            return List.of();
        }
        List<DirectNodeBinding> values = new ArrayList<>(keys.size());
        for (BindingKey key : keys) {
            values.add(bindings.get(key));
        }
        values.sort(Comparator.comparing(DirectNodeBinding::channelId));
        return List.copyOf(values);
    }

    public Collection<NetworkChannelRecord> allChannels() {
        requireOwningThread();
        return List.copyOf(channels.values());
    }

    public Collection<DirectNodeBinding> allBindings() {
        requireOwningThread();
        return List.copyOf(bindings.values());
    }

    public Collection<DomainNodeConfiguration> allDomains() {
        requireOwningThread();
        return List.copyOf(domains.values());
    }

    public Page<NetworkTunnelRecord> pageTunnels(@Nullable UUID anchor, boolean backwards, int limit) {
        requireOwningThread();
        validatePage(anchor, backwards, limit, tunnels);
        if (tunnelOrder.isEmpty()) {
            return new Page<>(List.of(), 0, false, false);
        }
        NavigableSet<NetworkTunnelRecord> window = anchor == null
                ? tunnelOrder
                : backwards
                        ? tunnelOrder.headSet(tunnels.get(anchor), false).descendingSet()
                        : tunnelOrder.tailSet(tunnels.get(anchor), false);
        List<NetworkTunnelRecord> entries = copyWindow(window.iterator(), limit, backwards);
        return page(entries, tunnelOrder);
    }

    public Page<NetworkChannelRecord> pageChannels(UUID tunnelId, @Nullable UUID anchor, boolean backwards, int limit) {
        requireOwningThread();
        Objects.requireNonNull(tunnelId, "tunnelId");
        NavigableSet<NetworkChannelRecord> ordered = channelsByTunnel.get(tunnelId);
        validatePageBounds(anchor, backwards, limit);
        NetworkChannelRecord anchored = anchor == null ? null : channels.get(anchor);
        if (anchor != null && (anchored == null || !anchored.tunnelId().equals(tunnelId))) {
            throw new IllegalArgumentException("Invalid tunnel-local channel anchor");
        }
        if (ordered == null || ordered.isEmpty()) {
            return new Page<>(List.of(), 0, false, false);
        }
        NavigableSet<NetworkChannelRecord> window = anchor == null
                ? ordered
                : backwards ? ordered.headSet(anchored, false).descendingSet() : ordered.tailSet(anchored, false);
        List<NetworkChannelRecord> entries = copyWindow(window.iterator(), limit, backwards);
        return page(entries, ordered);
    }

    public void addTunnel(NetworkTunnelRecord tunnel) {
        requireOwningThread();
        validateTunnel(tunnel);
        indexTunnel(tunnel);
    }

    /** Atomically indexes a new tunnel and its mandatory first channel after complete preflight. */
    public void addTunnelWithInitialChannel(NetworkTunnelRecord tunnel, NetworkChannelRecord initialChannel) {
        requireOwningThread();
        validateTunnel(tunnel);
        Objects.requireNonNull(initialChannel, "initialChannel");
        if (tunnel.lastChannelNumber() != 1
                || initialChannel.channelId().equals(tunnel.tunnelId())
                || !initialChannel.tunnelId().equals(tunnel.tunnelId())
                || initialChannel.channelNumber() != 1
                || channels.size() >= MAXIMUM_MANAGED_ENTRIES
                || channels.containsKey(initialChannel.channelId())
                || channelsByTunnel.containsKey(tunnel.tunnelId())
                || channelsByName.containsKey(
                        new ScopedName(tunnel.tunnelId(), initialChannel.name().uniquenessKey()))) {
            throw new IllegalArgumentException("Invalid mandatory initial channel");
        }
        indexTunnel(tunnel);
        indexChannel(initialChannel);
    }

    private void validateTunnel(NetworkTunnelRecord tunnel) {
        Objects.requireNonNull(tunnel, "tunnel");
        if (tunnels.size() >= MAXIMUM_TUNNELS
                || tunnels.containsKey(tunnel.tunnelId())
                || tunnelsByName.containsKey(tunnel.name().uniquenessKey())
                || tunnelsByNumber.containsKey(tunnel.tunnelNumber())) {
            throw new IllegalArgumentException("Duplicate or excessive network tunnel");
        }
    }

    private void indexTunnel(NetworkTunnelRecord tunnel) {
        tunnels.put(tunnel.tunnelId(), tunnel);
        tunnelsByName.put(tunnel.name().uniquenessKey(), tunnel.tunnelId());
        tunnelsByNumber.put(tunnel.tunnelNumber(), tunnel.tunnelId());
        tunnelOrder.add(tunnel);
    }

    public void replaceTunnel(NetworkTunnelRecord current, NetworkTunnelRecord updated) {
        requireOwningThread();
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(updated, "updated");
        if (!current.tunnelId().equals(updated.tunnelId())
                || current.tunnelNumber() != updated.tunnelNumber()
                || tunnels.get(current.tunnelId()) != current) {
            throw new IllegalArgumentException("Tunnel replacement identity mismatch");
        }
        UUID nameConflict = tunnelsByName.get(updated.name().uniquenessKey());
        if (nameConflict != null && !nameConflict.equals(current.tunnelId())) {
            throw new IllegalArgumentException("Duplicate network tunnel name");
        }
        tunnelOrder.remove(current);
        tunnelsByName.remove(current.name().uniquenessKey());
        tunnels.put(updated.tunnelId(), updated);
        tunnelsByName.put(updated.name().uniquenessKey(), updated.tunnelId());
        tunnelOrder.add(updated);
    }

    public void addChannel(NetworkChannelRecord channel) {
        requireOwningThread();
        validateChannel(channel);
        indexChannel(channel);
    }

    private void validateChannel(NetworkChannelRecord channel) {
        Objects.requireNonNull(channel, "channel");
        ScopedName name = new ScopedName(channel.tunnelId(), channel.name().uniquenessKey());
        NavigableSet<NetworkChannelRecord> ordered = channelsByTunnel.get(channel.tunnelId());
        if (!tunnels.containsKey(channel.tunnelId())
                || channels.size() >= MAXIMUM_MANAGED_ENTRIES
                || (ordered != null && ordered.size() >= MAXIMUM_TUNNELS)
                || channels.containsKey(channel.channelId())
                || channelsByName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate, dangling or excessive network channel");
        }
        if (ordered != null) {
            for (NetworkChannelRecord existing : ordered) {
                if (existing.channelNumber() == channel.channelNumber()) {
                    throw new IllegalArgumentException("Duplicate tunnel-local channel number");
                }
            }
        }
    }

    private void indexChannel(NetworkChannelRecord channel) {
        ScopedName name = new ScopedName(channel.tunnelId(), channel.name().uniquenessKey());
        channels.put(channel.channelId(), channel);
        channelsByName.put(name, channel.channelId());
        channelsByTunnel
                .computeIfAbsent(channel.tunnelId(), ignored -> new TreeSet<>(CHANNEL_ORDER))
                .add(channel);
    }

    public void replaceChannel(NetworkChannelRecord current, NetworkChannelRecord updated) {
        requireOwningThread();
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(updated, "updated");
        if (!current.channelId().equals(updated.channelId())
                || !current.tunnelId().equals(updated.tunnelId())
                || current.channelNumber() != updated.channelNumber()
                || channels.get(current.channelId()) != current) {
            throw new IllegalArgumentException("Channel replacement identity mismatch");
        }
        ScopedName updatedName =
                new ScopedName(updated.tunnelId(), updated.name().uniquenessKey());
        UUID nameConflict = channelsByName.get(updatedName);
        if (nameConflict != null && !nameConflict.equals(current.channelId())) {
            throw new IllegalArgumentException("Duplicate tunnel-local channel name");
        }
        NavigableSet<NetworkChannelRecord> ordered = channelsByTunnel.get(current.tunnelId());
        ordered.remove(current);
        channelsByName.remove(new ScopedName(current.tunnelId(), current.name().uniquenessKey()));
        channels.put(updated.channelId(), updated);
        channelsByName.put(updatedName, updated.channelId());
        ordered.add(updated);
    }

    public void putBinding(DirectNodeBinding binding) {
        requireOwningThread();
        Objects.requireNonNull(binding, "binding");
        NetworkChannelRecord target = channels.get(binding.channelId());
        if (target == null) {
            throw new IllegalArgumentException("Direct binding references a missing channel");
        }
        Optional<UUID> currentTunnel = directTunnelId(binding.nodeId());
        if (currentTunnel.isPresent() && !currentTunnel.orElseThrow().equals(target.tunnelId())) {
            throw new IllegalArgumentException("Direct node bindings must share one tunnel");
        }
        BindingKey key = new BindingKey(binding.nodeId(), binding.channelId());
        if (!bindings.containsKey(key) && bindings.size() >= MAXIMUM_MANAGED_ENTRIES) {
            throw new IllegalArgumentException("Too many direct bindings");
        }
        bindings.put(key, binding);
        bindingsByNode
                .computeIfAbsent(binding.nodeId(), ignored -> new HashSet<>())
                .add(key);
        bindingsByChannel
                .computeIfAbsent(binding.channelId(), ignored -> new HashSet<>())
                .add(key);
    }

    public Optional<DirectNodeBinding> removeBinding(UUID nodeId, UUID channelId) {
        requireOwningThread();
        BindingKey key = new BindingKey(
                Objects.requireNonNull(nodeId, "nodeId"), Objects.requireNonNull(channelId, "channelId"));
        DirectNodeBinding removed = bindings.remove(key);
        if (removed != null) {
            removeKey(bindingsByNode, nodeId, key);
            removeKey(bindingsByChannel, channelId, key);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Removes every direct binding owned by one node and returns the exact removed count.
     * This owning-thread mutation preflights all three binding indexes before changing them; null, wrong-thread access
     * and broken internal indexes reject without mutation.
     */
    public int removeBindings(UUID nodeId) {
        requireOwningThread();
        UUID requiredNodeId = Objects.requireNonNull(nodeId, "nodeId");
        Set<BindingKey> indexed = bindingsByNode.get(requiredNodeId);
        if (indexed == null || indexed.isEmpty()) {
            return 0;
        }
        List<BindingKey> keys = List.copyOf(indexed);
        for (BindingKey key : keys) {
            DirectNodeBinding binding = bindings.get(key);
            if (binding == null
                    || !binding.nodeId().equals(requiredNodeId)
                    || !bindingsByChannel
                            .getOrDefault(binding.channelId(), Set.of())
                            .contains(key)) {
                throw new IllegalStateException("Inconsistent direct binding indexes");
            }
        }
        for (BindingKey key : keys) {
            DirectNodeBinding removed = bindings.remove(key);
            removeKey(bindingsByNode, requiredNodeId, key);
            removeKey(bindingsByChannel, removed.channelId(), key);
        }
        return keys.size();
    }

    public void putDomain(DomainNodeConfiguration configuration) {
        requireOwningThread();
        Objects.requireNonNull(configuration, "configuration");
        if (!domains.containsKey(configuration.nodeId()) && domains.size() >= MAXIMUM_MANAGED_ENTRIES) {
            throw new IllegalArgumentException("Too many domain configurations");
        }
        domains.put(configuration.nodeId(), configuration);
    }

    public Optional<DomainNodeConfiguration> removeDomain(UUID nodeId) {
        requireOwningThread();
        return Optional.ofNullable(domains.remove(Objects.requireNonNull(nodeId, "nodeId")));
    }

    public TopologyDeletionImpact summarizeChannel(UUID channelId, long topologyRevision) {
        requireOwningThread();
        Objects.requireNonNull(channelId, "channelId");
        if (!channels.containsKey(channelId)) {
            throw new IllegalArgumentException("Missing channel");
        }
        List<UUID> nodes = sortedAffected(bindingsByChannel.get(channelId));
        int bindingCount = bindingsByChannel.getOrDefault(channelId, Set.of()).size();
        return new TopologyDeletionImpact(channelId, 0, bindingCount, nodes, topologyRevision);
    }

    public TopologyDeletionImpact summarizeTunnel(UUID tunnelId, long topologyRevision) {
        requireOwningThread();
        NavigableSet<NetworkChannelRecord> tunnelChannels =
                channelsByTunnel.get(Objects.requireNonNull(tunnelId, "tunnelId"));
        if (!tunnels.containsKey(tunnelId)) {
            throw new IllegalArgumentException("Missing tunnel");
        }
        Set<BindingKey> affectedBindings = new HashSet<>();
        if (tunnelChannels != null) {
            for (NetworkChannelRecord channel : tunnelChannels) {
                affectedBindings.addAll(bindingsByChannel.getOrDefault(channel.channelId(), Set.of()));
            }
        }
        List<UUID> nodes = affectedBindings.stream()
                .map(BindingKey::nodeId)
                .distinct()
                .sorted()
                .toList();
        return new TopologyDeletionImpact(
                tunnelId,
                tunnelChannels == null ? 0 : tunnelChannels.size(),
                affectedBindings.size(),
                nodes,
                topologyRevision);
    }

    public void removeChannel(UUID channelId) {
        requireOwningThread();
        NetworkChannelRecord channel = channels.remove(Objects.requireNonNull(channelId, "channelId"));
        if (channel == null) {
            return;
        }
        channelsByName.remove(new ScopedName(channel.tunnelId(), channel.name().uniquenessKey()));
        NavigableSet<NetworkChannelRecord> ordered = channelsByTunnel.get(channel.tunnelId());
        ordered.remove(channel);
        if (ordered.isEmpty()) {
            channelsByTunnel.remove(channel.tunnelId());
        }
        for (BindingKey key : List.copyOf(bindingsByChannel.getOrDefault(channelId, Set.of()))) {
            removeBinding(key.nodeId(), key.channelId());
        }
    }

    public void removeTunnel(UUID tunnelId) {
        requireOwningThread();
        NetworkTunnelRecord tunnel = tunnels.remove(Objects.requireNonNull(tunnelId, "tunnelId"));
        if (tunnel == null) {
            return;
        }
        tunnelsByName.remove(tunnel.name().uniquenessKey());
        tunnelsByNumber.remove(tunnel.tunnelNumber());
        tunnelOrder.remove(tunnel);
        for (NetworkChannelRecord channel : channels(tunnelId)) {
            removeChannel(channel.channelId());
        }
    }

    public boolean removeNodeConfigurations(UUID nodeId) {
        requireOwningThread();
        Objects.requireNonNull(nodeId, "nodeId");
        boolean changed = false;
        for (BindingKey key : List.copyOf(bindingsByNode.getOrDefault(nodeId, Set.of()))) {
            removeBinding(key.nodeId(), key.channelId());
            changed = true;
        }
        return removeDomain(nodeId).isPresent() || changed;
    }

    private static <T> List<T> copyWindow(Iterator<T> iterator, int limit, boolean backwards) {
        List<T> entries = new ArrayList<>(limit);
        while (entries.size() < limit && iterator.hasNext()) {
            entries.add(iterator.next());
        }
        if (backwards) {
            Collections.reverse(entries);
        }
        return entries;
    }

    private static <T> Page<T> page(List<T> entries, NavigableSet<T> ordered) {
        if (entries.isEmpty()) {
            return new Page<>(List.of(), ordered.size(), false, false);
        }
        return new Page<>(
                entries,
                ordered.size(),
                ordered.lower(entries.getFirst()) != null,
                ordered.higher(entries.getLast()) != null);
    }

    private static void validatePage(@Nullable UUID anchor, boolean backwards, int limit, Map<UUID, ?> values) {
        validatePageBounds(anchor, backwards, limit);
        if (anchor != null && !values.containsKey(anchor)) {
            throw new IllegalArgumentException("Invalid topology page bounds or anchor");
        }
    }

    private static void validatePageBounds(@Nullable UUID anchor, boolean backwards, int limit) {
        if (limit < 1 || limit > MAXIMUM_PAGE_ENTRIES || (backwards && anchor == null)) {
            throw new IllegalArgumentException("Invalid topology page bounds");
        }
    }

    private List<UUID> sortedAffected(@Nullable Set<BindingKey> keys) {
        if (keys == null) {
            return List.of();
        }
        return keys.stream().map(BindingKey::nodeId).distinct().sorted().toList();
    }

    private static void removeKey(Map<UUID, Set<BindingKey>> index, UUID id, BindingKey key) {
        Set<BindingKey> keys = index.get(id);
        keys.remove(key);
        if (keys.isEmpty()) {
            index.remove(id);
        }
    }

    private void requireOwningThread() {
        if (Thread.currentThread() != owningThread) {
            throw new IllegalStateException("Network topology index accessed outside its owning server thread");
        }
    }

    /** Immutable bounded ordered page copied from one topology index query. */
    public record Page<T>(List<T> entries, int totalCount, boolean hasPrevious, boolean hasNext) {
        public Page {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (entries.size() > MAXIMUM_PAGE_ENTRIES || totalCount < entries.size()) {
                throw new IllegalArgumentException("Invalid topology page");
            }
        }
    }

    public record DirectionCounts(int inputs, int outputs) {
        public DirectionCounts {
            if (inputs < 0 || outputs < 0) {
                throw new IllegalArgumentException("Negative channel direction count");
            }
        }
    }

    private record ScopedName(UUID tunnelId, String name) {}

    private record BindingKey(UUID nodeId, UUID channelId) {}
}
