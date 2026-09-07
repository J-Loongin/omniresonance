// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.compat.jec;

import java.util.function.BiPredicate;
import me.towdium.jecharacters.utils.Match;

/** Optional client-thread-only JEC bridge. This class must only be loaded when JEC is installed. */
public final class JecSearchMatcher implements BiPredicate<String, String> {
    /**
     * Matches already-folded client text using JEC's public API. No world or authority is accessed;
     * JEC owns its matching cache and failures propagate to the caller's ordinary-search fallback.
     */
    @Override
    public boolean test(String name, String query) {
        return Match.contains(name, query);
    }
}
