// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Pure visible tooltip policy; search metadata and tag-copy actions are independent. */
final class DomainInventoryTooltip {
    private DomainInventoryTooltip() {}

    static List<Component> lines(
            String name,
            List<String> nativeLines,
            boolean item,
            boolean visibleBar,
            boolean fluid,
            boolean opaque,
            long amount,
            String unit) {
        if (amount < 0) throw new IllegalArgumentException("Negative tooltip amount");
        var result = new ArrayList<Component>();
        result.add(Component.literal(name));
        if (opaque) result.add(DomainInventoryView.text("opaque"));
        else for (var line : nativeLines) if (!line.equals(name)) result.add(Component.literal(line));
        // Our slot text abbreviates from 1000, unlike AE2's configurable slot-font thresholds.
        if (!item || opaque || visibleBar || amount >= 1000) {
            String number = fluid ? DomainFluidDisplay.exactBuckets(amount) : Long.toString(amount);
            result.add(
                    item || unit.isEmpty()
                            ? DomainInventoryView.text("quantity_exact", number)
                            : DomainInventoryView.text("quantity", number, unit));
        }
        return List.copyOf(result);
    }
}
