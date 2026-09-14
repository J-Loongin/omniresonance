// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

/** Internal immutable canonical bytes; no accessor exposes the backing array. */
final class FilterComponentBytes {
    private final byte[] bytes;

    FilterComponentBytes(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    byte[] copy() {
        return bytes.clone();
    }

    int size() {
        return bytes.length;
    }

    byte at(int index) {
        return bytes[index];
    }
}
