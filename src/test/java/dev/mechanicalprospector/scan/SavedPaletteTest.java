package dev.mechanicalprospector.scan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SavedPaletteTest {
    @Test void constantBiomePaletteNeedsNoPackedArray() {
        assertEquals(0, SavedChunkSurvey.paletteIndex(new long[0], 1, 63));
    }
    @Test void paletteValuesDoNotCrossLongBoundaries() {
        for (int paletteSize : new int[]{2, 3, 5, 9, 17, 33, 64}) {
            int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1), perLong = 64 / bits;
            long[] data = new long[(64 + perLong - 1) / perLong];
            for (int index = 0; index < 64; index++)
                data[index / perLong] |= (long) (index % paletteSize) << ((index % perLong) * bits);
            for (int index = 0; index < 64; index++)
                assertEquals(index % paletteSize, SavedChunkSurvey.paletteIndex(data, paletteSize, index));
        }
    }
    @Test void malformedStorageIsDetectedInsteadOfReportedAsEmpty() {
        assertEquals(-1, SavedChunkSurvey.paletteIndex(new long[0], 3, 17));
    }
    @Test void coeSentinelsDistinguishInfiniteAndDepleted() {
        assertEquals(ScanCell.INFINITE, ScanCell.kindFor(0));
        assertEquals(ScanCell.DEPLETED, ScanCell.kindFor(-1));
        assertEquals(ScanCell.FINITE, ScanCell.kindFor(1));
        assertEquals(ScanCell.FINITE, ScanCell.kindFor(Long.MAX_VALUE));
    }
}
