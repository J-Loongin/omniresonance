// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.network;

import io.github.loongin.omniresonance.filter.ItemFilterService;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** One server-thread terminal edit context. Owns only the reference, never grants authority or releases locks.
 * The service cancels/commits through the relevant authority before clearing this state. No persistence. */
final class TerminalEditSession {
    sealed interface Lease permits Topology, TopologyDeletion, Removal, NetworkRename, NetworkDeletion, Preset {}

    record Topology(NetworkTopologyService.Edit edit) implements Lease {
        Topology {
            Objects.requireNonNull(edit);
        }
    }

    record TopologyDeletion(NetworkTopologyService.DeletionEdit edit) implements Lease {
        TopologyDeletion {
            Objects.requireNonNull(edit);
        }
    }

    record Removal(NetworkAdministrationService.RemovalEdit edit) implements Lease {
        Removal {
            Objects.requireNonNull(edit);
        }
    }

    record NetworkRename(NetworkSettingsService.RenameEdit edit) implements Lease {
        NetworkRename {
            Objects.requireNonNull(edit);
        }
    }

    record NetworkDeletion(NetworkSettingsService.DeletionEdit edit) implements Lease {
        NetworkDeletion {
            Objects.requireNonNull(edit);
        }
    }

    record Preset(ItemFilterService.Edit edit) implements Lease {
        Preset {
            Objects.requireNonNull(edit);
        }
    }

    private @Nullable Lease current;

    void requireIdle() {
        if (current != null) throw new IllegalStateException("Terminal edit already active");
    }

    void begin(Lease lease) {
        requireIdle();
        current = Objects.requireNonNull(lease);
    }

    @Nullable
    Lease current() {
        return current;
    }

    void clear() {
        current = null;
    }

    @Nullable
    NetworkTopologyService.Edit topology() {
        return current instanceof Topology value ? value.edit() : null;
    }

    @Nullable
    NetworkTopologyService.DeletionEdit topologyDeletion() {
        return current instanceof TopologyDeletion value ? value.edit() : null;
    }

    @Nullable
    NetworkAdministrationService.RemovalEdit removal() {
        return current instanceof Removal value ? value.edit() : null;
    }

    @Nullable
    NetworkSettingsService.RenameEdit networkRename() {
        return current instanceof NetworkRename value ? value.edit() : null;
    }

    @Nullable
    NetworkSettingsService.DeletionEdit networkDeletion() {
        return current instanceof NetworkDeletion value ? value.edit() : null;
    }

    @Nullable
    ItemFilterService.Edit preset() {
        return current instanceof Preset value ? value.edit() : null;
    }
}
