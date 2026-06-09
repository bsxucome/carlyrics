package com.bsxu.carlyrics.bridge;

import java.io.IOException;
import java.io.Reader;

public final class LimitedLineReader {

    private final Reader reader;
    private final int maxLineChars;

    public LimitedLineReader(Reader reader, int maxLineChars) {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        if (maxLineChars <= 0) {
            throw new IllegalArgumentException("maxLineChars must be positive");
        }
        this.reader = reader;
        this.maxLineChars = maxLineChars;
    }

    public String readLine() throws IOException {
        StringBuilder builder = new StringBuilder(Math.min(maxLineChars, 4096));
        boolean readAnyCharacter = false;

        while (true) {
            int value = reader.read();
            if (value == -1) {
                return readAnyCharacter ? builder.toString() : null;
            }
            readAnyCharacter = true;
            if (value == '\n') {
                return builder.toString();
            }
            if (value == '\r') {
                continue;
            }
            if (builder.length() >= maxLineChars) {
                throw new MessageTooLargeException(maxLineChars);
            }
            builder.append((char) value);
        }
    }

    public static final class MessageTooLargeException extends IOException {

        public MessageTooLargeException(int maxLineChars) {
            super("Bridge message exceeds " + maxLineChars + " characters");
        }
    }
}
