// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

/** Node-specific shortcut eligibility over the shared client-only search presentation state. */
final class NodeTunnelSearch extends ClientSearchState {
    boolean openFromKey(
            int keyCode,
            int modifiers,
            NodeMenuInteractionPolicy.Model interaction,
            boolean modalOpen,
            boolean catalogReady) {
        return openFromKey(
                keyCode,
                modifiers,
                !modalOpen
                        && catalogReady
                        && !interaction.mutationPending()
                        && NodeMenuInteractionPolicy.topBarAction(interaction.authoritative())
                                == TerminalHeaderLayout.Action.SEARCH);
    }
}
