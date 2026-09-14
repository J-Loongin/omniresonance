// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

/**
 * Borrowed immutable bytes available only during a synchronous owning-thread completion callback. No backing array
 * escapes. Implementations reject off-thread or expired reads. Decoders may retain detached DTOs, never this view.
 * Reading performs no simulation or authority mutation; invalid indices fail before returning a byte.
 */
public interface ManagementObjectView {
    /** Returns the complete length while the callback scope remains valid. */
    int length();
    /** Returns one byte while the callback scope remains valid; invalid indices are rejected. */
    byte byteAt(int index);
}
