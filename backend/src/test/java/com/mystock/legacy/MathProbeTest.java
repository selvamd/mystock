package com.mystock.legacy;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dates / TimeSeriesData / TimeSeries / Transforms / BSOption / Corls: the copied classes must produce exactly
 * (Double.toString-equal) the outputs of the original legacy classes, recorded in golden/mathprobe.txt by
 * compiling mystk/java/db/*.java with javac and running the same probe.
 */
class MathProbeTest {
    @Test
    void copiedMathMatchesLegacyBitForBit() throws Exception {
        String golden;
        try (InputStream in = getClass().getResourceAsStream("/golden/mathprobe.txt")) {
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> expected = golden.lines().toList();
        List<String> actual;
        Legacy.LOCK.lock();
        try {
            int[] saved = Dates.dates;
            actual = MathProbe.run();
            Dates.dates = saved; // restore the global calendar for other tests in this JVM
        } finally {
            Legacy.LOCK.unlock();
        }
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i), "line " + (i + 1));
    }
}
