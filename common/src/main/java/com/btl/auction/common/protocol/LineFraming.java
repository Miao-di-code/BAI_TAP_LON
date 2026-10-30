package com.btl.auction.common.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

public final class LineFraming {
    public static final int MAX_LINE_BYTES = 1_048_576;

    private LineFraming() {
    }

    public static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') {
                byte[] line = bytes.toByteArray();
                int length = line.length > 0 && line[line.length - 1] == '\r'
                        ? line.length - 1 : line.length;
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .decode(ByteBuffer.wrap(line, 0, length)).toString();
                } catch (CharacterCodingException ex) {
                    throw new IOException("Invalid UTF-8", ex);
                }
            }
            if (bytes.size() >= MAX_LINE_BYTES) {
                throw new LineTooLongException();
            }
            bytes.write(next);
        }
        if (bytes.size() != 0) {
            throw new IOException("Truncated JSON line");
        }
        return null;
    }

    public static final class LineTooLongException extends IOException {
        public LineTooLongException() {
            super("JSON line exceeds 1 MiB");
        }
    }
}
