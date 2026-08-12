package com.mochanes.web;

/**
 * Minimal base64 decoder.
 *
 * <p>Hand-rolled rather than {@code java.util.Base64} so the web build does not
 * depend on that class being present in the ahead-of-time compiler's class
 * library.
 */
final class Base64 {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Base64() {
    }

    static byte[] decode(String s) {
        int padding = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '='; i--) {
            padding++;
        }
        byte[] out = new byte[s.length() / 4 * 3 - padding];

        int index = 0;
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') {
                break;
            }
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                continue;   // tolerate line breaks and stray whitespace
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[index++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        return out;
    }
}
