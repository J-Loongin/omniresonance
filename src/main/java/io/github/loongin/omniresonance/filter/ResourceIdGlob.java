// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.filter;

import java.util.Objects;

/** Immutable anchored glob; pure cursors own their state. No regex or backtracking. */
public final class ResourceIdGlob {
    private final String pattern;

    public ResourceIdGlob(String pattern) {
        Objects.requireNonNull(pattern);
        if (pattern.isEmpty() || pattern.length() > 256) throw new IllegalArgumentException("Invalid glob length");
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "_-. /:*?".indexOf(c) >= 0) || c == ' ')
                throw new IllegalArgumentException("Invalid glob character");
            if (c != '*' || normalized.isEmpty() || normalized.charAt(normalized.length() - 1) != '*')
                normalized.append(c);
        }
        this.pattern = normalized.toString();
    }

    public String pattern() {
        return pattern;
    }

    public Evaluation evaluate(String id) {
        return new Evaluation(Objects.requireNonNull(id));
    }
    /** Non-hot-path convenience; runtime callers must drive evaluate().step(budget). */
    public boolean matches(String id) {
        Evaluation e = evaluate(id);
        while (!e.done()) e.step(4096);
        return e.matches();
    }
    /** One unit per DP cell; O(pattern length * ID length) time, O(pattern length) memory. */
    public final class Evaluation {
        private final String input;
        private boolean[] previous = new boolean[pattern.length() + 1];
        private boolean[] current = new boolean[pattern.length() + 1];
        private int row;
        private int column;
        private boolean done;

        private Evaluation(String input) {
            this.input = input;
        }

        public int step(int budget) {
            if (budget < 0) throw new IllegalArgumentException("Negative budget");
            int used = 0;
            while (used < budget && !done) {
                used++;
                if (column == 0) {
                    if (row > 0) {
                        char c = input.charAt(row - 1);
                        if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "_-./:".indexOf(c) >= 0)) {
                            done = true;
                            previous[pattern.length()] = false;
                            continue;
                        }
                    }
                    current[0] = row == 0;
                } else {
                    char p = pattern.charAt(column - 1);
                    current[column] = p == '*'
                            ? (current[column - 1] || (row > 0 && previous[column]))
                            : row > 0 && previous[column - 1] && (p == '?' || p == input.charAt(row - 1));
                }
                if (++column > pattern.length()) {
                    boolean[] swap = previous;
                    previous = current;
                    current = swap;
                    column = 0;
                    if (row++ == input.length()) done = true;
                }
            }
            return used;
        }

        public boolean done() {
            return done;
        }

        public boolean matches() {
            if (!done) throw new IllegalStateException("Incomplete glob");
            return previous[pattern.length()];
        }
    }
}
