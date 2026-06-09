package com.bsxu.carlyrics.bridge;

import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LimitedLineReaderTest {

    @Test
    public void readsLfAndCrLfLines() throws Exception {
        LimitedLineReader reader = new LimitedLineReader(
                new StringReader("first\r\nsecond\n"),
                10
        );

        assertEquals("first", reader.readLine());
        assertEquals("second", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    public void acceptsLineAtExactLimit() throws Exception {
        LimitedLineReader reader = new LimitedLineReader(new StringReader("12345\n"), 5);

        assertEquals("12345", reader.readLine());
    }

    @Test(expected = LimitedLineReader.MessageTooLargeException.class)
    public void rejectsLinePastLimit() throws Exception {
        LimitedLineReader reader = new LimitedLineReader(new StringReader("123456\n"), 5);

        reader.readLine();
    }
}
