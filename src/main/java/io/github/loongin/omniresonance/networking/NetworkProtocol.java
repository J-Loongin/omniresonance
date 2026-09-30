// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.networking;

/** Shared payload handshake version for core, node menus and optional AE integration. Immutable and
 * thread independent; initialization performs no registration, world access or mutation. SavedData and
 * individual object codec versions remain separate contracts. */
public final class NetworkProtocol {
    public static final String VERSION = "29";

    private NetworkProtocol() {}
}
