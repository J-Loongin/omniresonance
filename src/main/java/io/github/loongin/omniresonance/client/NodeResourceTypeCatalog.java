// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.networking.ResourceTypeCatalogPage;
import io.github.loongin.omniresonance.transfer.ResourceAdapterDirectory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** One client session's bounded catalog; only a fully validated catalog is published or searchable. */
final class NodeResourceTypeCatalog {
    record Request(UUID session, @Nullable UUID catalogId, int offset, long generation) {}

    /** Frozen once after completion, shared by this session's drafts without repeated whole-catalog copies. */
    static final class Snapshot {
        private final List<ResourceAdapterDirectory.Descriptor> entries;
        private final Map<ResourceLocation, ResourceAdapterDirectory.Descriptor> byId;

        private Snapshot(
                List<ResourceAdapterDirectory.Descriptor> entries,
                Map<ResourceLocation, ResourceAdapterDirectory.Descriptor> byId) {
            this.entries = List.copyOf(entries);
            this.byId = Map.copyOf(byId);
        }

        List<ResourceAdapterDirectory.Descriptor> entries() {
            return entries;
        }

        @Nullable
        ResourceAdapterDirectory.Descriptor find(ResourceLocation id) {
            return byId.get(id);
        }
    }

    private final List<ResourceAdapterDirectory.Descriptor> entries = new ArrayList<>();
    private final Map<ResourceLocation, ResourceAdapterDirectory.Descriptor> seen = new HashMap<>();
    private @Nullable UUID session;
    private @Nullable UUID catalogId;
    private @Nullable Request pending;
    private @Nullable Snapshot snapshot;
    private int total = -1;
    private long generation;
    private boolean failed;

    void open(UUID session) {
        close();
        this.session = Objects.requireNonNull(session, "session");
    }

    @Nullable
    Request nextRequest() {
        if (session == null || failed || snapshot != null || pending != null) return null;
        pending = new Request(session, catalogId, entries.size(), generation);
        return pending;
    }

    boolean complete(Request request, ResourceTypeCatalogPage page) {
        if (pending == null || !pending.equals(request)) return false;
        pending = null;
        if (page.offset() != entries.size()
                || catalogId != null && !catalogId.equals(page.catalogId())
                || total >= 0 && total != page.totalCount()) return fail();
        for (var descriptor : page.entries())
            if (seen.putIfAbsent(descriptor.typeId(), descriptor) != null) return fail();
        entries.addAll(page.entries());
        catalogId = page.catalogId();
        total = page.totalCount();
        if (entries.size() == total) {
            snapshot = new Snapshot(entries, seen);
            entries.clear();
            seen.clear();
        }
        return true;
    }

    boolean ready() {
        return snapshot != null;
    }

    boolean failed() {
        return failed;
    }

    int received() {
        return snapshot == null ? entries.size() : snapshot.entries().size();
    }

    int total() {
        return Math.max(0, total);
    }

    Snapshot snapshot() {
        if (snapshot == null) throw new IllegalStateException("Resource catalog is incomplete");
        return snapshot;
    }

    private boolean fail() {
        close();
        failed = true;
        return false;
    }

    void close() {
        generation++;
        session = null;
        catalogId = null;
        pending = null;
        snapshot = null;
        entries.clear();
        seen.clear();
        total = -1;
        failed = false;
    }
}
