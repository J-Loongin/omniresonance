// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client text matching independent of server authority and world access. */
public final class ClientTextSearch {
    interface Catalog<T> {
        List<T> filter(String query, BiPredicate<String, String> matcher, long matcherRevision);
    }

    private static final BiPredicate<String, String> PLAIN = String::contains;
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTextSearch.class);
    private static BiPredicate<String, String> matcher = PLAIN;
    private static long revision;
    private static boolean failed;

    private ClientTextSearch() {}

    /** Installs a client-thread-only matcher; it receives folded text, owns no authority, and may be replaced. */
    public static void install(BiPredicate<String, String> replacement) {
        Objects.requireNonNull(replacement, "replacement");
        long nextRevision = Math.incrementExact(revision);
        matcher = replacement;
        revision = nextRevision;
        failed = false;
    }

    /** Restores ordinary client matching and invalidates cached results without changing server state. */
    public static void usePlain() {
        install(PLAIN);
    }

    static boolean failed() {
        return failed;
    }

    static String fold(String text) {
        return Objects.requireNonNull(text, "text").toLowerCase(Locale.ROOT);
    }

    static boolean matchesFolded(String text, String query) {
        try {
            return matcher.test(text, query);
        } catch (RuntimeException | LinkageError failure) {
            if (matcher == PLAIN) throw failure;
            usePlain();
            failed = true;
            LOGGER.warn("Optional client text search failed; falling back to ordinary matching", failure);
            return text.contains(query);
        }
    }

    static long matcherRevision() {
        return revision;
    }

    static <T> List<T> filter(Catalog<T> catalog, String query) {
        try {
            return catalog.filter(query, matcher, revision);
        } catch (RuntimeException | LinkageError failure) {
            if (matcher == PLAIN) {
                throw failure;
            }
            usePlain();
            failed = true;
            LOGGER.warn("Optional client text search failed; falling back to ordinary matching", failure);
            return catalog.filter(query, matcher, revision);
        }
    }
}
