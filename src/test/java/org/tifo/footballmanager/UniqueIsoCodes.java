package org.tifo.footballmanager;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out ISO codes that are unique within a JVM, so a test that creates two or three countries
 * in one transaction never draws the same code twice and trips the global unique index on
 * {@code country.iso_code}.
 *
 * <p><b>P0-51.</b> {@code country.iso_code} is {@code varchar(3)} with a <b>global</b> unique index,
 * and about eighty test call sites generated one as {@code "R" + uuid.substring(0, 2)} - only 256
 * possible values, so a collision was a matter of luck and a randomly red gate is worse than a
 * permanently red one. This helper hands out a code from a counter instead, so no two calls in the
 * same JVM ever agree.
 *
 * <p>Each call takes an optional prefix (one or two letters) so a test can still recognise its own
 * country; the rest is filled from a counter that never repeats. The result is always three
 * characters, which is all the column holds.
 */
public final class UniqueIsoCodes {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private UniqueIsoCodes() {
    }

    /** A unique three-character code with no prefix. */
    public static String next() {
        return next("");
    }

    /** A unique three-character code beginning with {@code prefix} (one or two letters). */
    public static String next(String prefix) {
        if (prefix == null) prefix = "";
        if (prefix.length() > 2) prefix = prefix.substring(0, 2);
        int n = COUNTER.incrementAndGet();
        int width = 3 - prefix.length();
        if (width <= 0) {
            return prefix.substring(0, 3);
        }
        // base-36 counter, zero-padded to `width` characters -> 36^width possible values.
        StringBuilder body = new StringBuilder();
        int v = n;
        for (int i = 0; i < width; i++) {
            body.append(ALPHABET[v % 36]);
            v /= 36;
        }
        return prefix + body.reverse().toString();
    }
}
