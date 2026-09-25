// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.exchange;

import io.github.loongin.omniresonance.filter.FilterMode;
import io.github.loongin.omniresonance.filter.ResourceFilterCompiler;
import org.jetbrains.annotations.Nullable;

/** Pure filter eligibility shared by execution and terminal diagnostics; never compiles or mutates state. */
public enum ExchangeFilterStatus {
    MISSING,
    EMPTY_BLACKLIST,
    PREPARING,
    INVALID,
    READY,
    EMPTY_WHITELIST;

    public static ExchangeFilterStatus of(ExchangeTerms terms, @Nullable ResourceFilterCompiler.Compiled compiled) {
        if (terms.filter() == null) return MISSING;
        if (compiled != null && !compiled.valid()) return INVALID;
        if (terms.filter().ruleCount() == 0 || compiled != null && compiled.ruleCount() == 0)
            return terms.filterMode() == FilterMode.BLACKLIST ? EMPTY_BLACKLIST : EMPTY_WHITELIST;
        return compiled == null ? PREPARING : READY;
    }

    public boolean blocked() {
        return this == MISSING || this == EMPTY_BLACKLIST || this == INVALID;
    }
}
