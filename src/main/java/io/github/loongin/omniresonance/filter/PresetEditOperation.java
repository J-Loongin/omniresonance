// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

/** One explicitly saved preset mutation; only CREATE omits an existing preset identity. */
public enum PresetEditOperation {
    CREATE,
    RENAME,
    ADD_RULE,
    REMOVE_RULE,
    COPY,
    DELETE,
    EDIT_RULE
}
