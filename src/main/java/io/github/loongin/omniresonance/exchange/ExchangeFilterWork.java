// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.ResourceFilterCache;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import io.github.loongin.omniresonance.transfer.ResourceDirectScheduler;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One server-thread protocol publication's shared filter preparation. Uses the existing bounded tag/compiler cache
 * with one frozen approved library, never the editable owner library. All resource types share one root/token;
 * replacement, tag invalidation and close retire derived permits. No authority mutation, simulation or I/O occurs.
 */
public final class ExchangeFilterWork implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final @Nullable ResourceFilterCache cache;
    private final @Nullable ResourceFilterCache.Key key;
    private final @Nullable ResourceDirectScheduler.FilterView unfiltered;
    private ResourceFilterCache.TagSource source;
    private boolean closed;

    /** Publication-time setup; source opens bounded immutable-generation native tag iterators on the server thread. */
    public ExchangeFilterWork(ExchangeAgreement agreement, ResourceFilterCache.TagSource source) {
        Objects.requireNonNull(agreement);
        this.source = Objects.requireNonNull(source);
        var filter = agreement.terms().filter();
        var ownerId = agreement.consent().sourceOwner();
        var snapshot = new ResourceFilterCompiler.OwnerSnapshot(ownerId, filter == null ? Map.of() : filter.presets());
        if (filter == null) {
            cache = null;
            key = null;
            unfiltered = new ResourceDirectScheduler.FilterView(
                    new Object(),
                    ResourceFilterCompiler.compile(
                            null, snapshot, (type, tag) -> ResourceFilterCompiler.TagSnapshot.missing(0)));
        } else {
            cache = new ResourceFilterCache(id -> id.equals(ownerId) ? snapshot : null, tag -> this.source.open(tag));
            key = cache.acquire(ownerId, filter.root());
            unfiltered = null;
        }
    }

    /** Compatibility adapter for already prepared immutable tag membership; no eager member copying occurs here. */
    public static ExchangeFilterWork prepared(ExchangeAgreement agreement, ResourceFilterCompiler.Tags tags) {
        return new ExchangeFilterWork(agreement, adapt(tags));
    }

    /** Shared immutable permit view, read without compiling or discovering native tags. */
    public ResourceDirectScheduler.FilterView view() {
        check();
        return cache == null ? Objects.requireNonNull(unfiltered) : cache.view(Objects.requireNonNull(key));
    }

    /** Advances existing preparation by at most 256 charged member/graph units; no rule-library edits are followed. */
    public int advance(int units) {
        check();
        if (units < 0 || units > 256) throw new IllegalArgumentException("Invalid shared filter slice");
        return cache == null ? 0 : cache.advance(Objects.requireNonNull(key), units);
    }

    /** Invalidates all type permits before any new native membership is read. */
    public void tagsChanged() {
        check();
        if (cache != null) cache.tagsChanged();
    }

    /** Replaces a prepared-tag provider and invalidates shared permits without changing approved rules. */
    public void replaceTags(ResourceFilterCompiler.Tags tags) {
        check();
        source = adapt(tags);
        tagsChanged();
    }

    private static ResourceFilterCache.TagSource adapt(ResourceFilterCompiler.Tags tags) {
        Objects.requireNonNull(tags);
        return key -> {
            var snapshot = Objects.requireNonNull(tags.resolve(key.typeId(), key.tagId()));
            return snapshot.exists() ? snapshot.members().iterator() : null;
        };
    }

    private void check() {
        if (Thread.currentThread() != owner || closed)
            throw new IllegalStateException("Closed or off-thread exchange filter");
    }

    @Override
    public void close() {
        check();
        if (cache != null) cache.close();
        closed = true;
    }
}
