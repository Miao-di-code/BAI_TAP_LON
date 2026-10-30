package com.btl.auction.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LineFramingTest {
    @Test
    void readsUtf8CrlfAndEndOfStream() throws IOException {
        byte[] source = "{\"name\":\"Đấu giá\"}\r\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(source);
        assertEquals("{\"name\":\"Đấu giá\"}", LineFraming.readLine(input));
        assertNull(LineFraming.readLine(input));
    }

    @Test
    void rejectsOversizedTruncatedAndMalformedUtf8() {
        byte[] oversized = ("x".repeat(LineFraming.MAX_LINE_BYTES + 1) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(LineFraming.LineTooLongException.class,
                () -> LineFraming.readLine(new ByteArrayInputStream(oversized)));
        assertThrows(IOException.class, () -> LineFraming.readLine(
                new ByteArrayInputStream("partial".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> LineFraming.readLine(
                new ByteArrayInputStream(new byte[] {(byte) 0xc3, (byte) 0x28, '\n'})));
    }
}
