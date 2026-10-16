package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Readable examples of the traps the differential vectors cover; each pairs Python's answer with Java's. */
class NumericTrapsTest {

    @Test
    void formattingIgnoresTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("1,235", String.format("%.3f", 1.2345)); // the trap: decimal comma and half-up
            assertEquals("1.234", PyFormat.format(1.2345, ".3f")); // 1.2345 is slightly below the tie
            assertEquals("12.5%", PyFormat.format(0.125, ".1%"));
            assertEquals("1,234,567", PyFormat.format(1234567, ","));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void fixedPointRoundsTheExactBinaryValueHalfEven() {
        assertEquals("0.12", PyFormat.format(0.125, ".2f"));
        assertEquals("1.00", PyFormat.format(1.005, ".2f"));
        assertEquals("-0.000", PyFormat.format(-0.0001, ".3f"));
        assertNotEquals(PyFormat.format(0.125, ".2f"), String.format(Locale.ROOT, "%.2f", 0.125));
    }

    @Test
    void roundIsBankersRoundingOnTheBinaryValue() {
        assertEquals(2, PyRound.round(2.5));
        assertEquals(-2, PyRound.round(-2.5));
        assertEquals(2.67, PyRound.round(2.675, 2));
        assertEquals(0.12, PyRound.round(0.125, 2));
        assertEquals(1200, PyRound.round(1250, -2));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(PyRound.round(-0.001, 2)));
        assertThrows(IllegalArgumentException.class, () -> PyRound.round(Double.NaN));
    }

    @Test
    void reprIsShortestRoundTripInPythonLayout() {
        assertEquals("1e-05", PyFloatRepr.repr(1e-5));
        assertEquals("100.0", PyFloatRepr.repr(100.0));
        assertEquals("1e+16", PyFloatRepr.repr(1e16));
        assertEquals("1000000000000000.0", PyFloatRepr.repr(1e15));
        assertEquals("5e-324", PyFloatRepr.repr(Double.MIN_VALUE));
        assertEquals("4.9E-324", Double.toString(Double.MIN_VALUE));
        assertEquals("NaN", PyFloatRepr.json(Double.NaN));
    }

    @Test
    void floorDivisionAndModuloFollowTheDivisorSign() {
        assertEquals(3, PyMath.mod(-5, 4));
        assertEquals(-2, PyMath.floorDiv(-5, 4));
        assertEquals(4294967295L, PyMath.mod(-1, 4294967296L));
        assertEquals(2, PyMath.mod(-len(6), 4)); // -len(x) % 4 pads to a 4-byte boundary
        assertThrows(ArithmeticException.class, () -> PyMath.trueDivide(1.0, 0.0));
    }

    private static long len(int n) {
        return n;
    }

    @Test
    void minMaxKeepTheFirstExtremeElement() {
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(PyBuiltins.max(0.0, -0.0)));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(PyBuiltins.max(-0.0, 0.0)));
        assertEquals(1.0, PyBuiltins.min(1.0, Double.NaN));
        assertEquals(Double.NaN, PyBuiltins.min(Double.NaN, 1.0));
        assertEquals(PyNum.of(3), PyBuiltins.max(List.of(PyNum.of(3), PyNum.of(3.0))));
    }

    @Test
    void sumIsCompensatedSincePython312() {
        double[] tenths = new double[10];
        java.util.Arrays.fill(tenths, 0.1);
        assertEquals(1.0, PyBuiltins.sum(tenths));
        double naive = 0;
        for (double v : tenths) {
            naive += v;
        }
        assertNotEquals(1.0, naive);
    }

    @Test
    void medianKeepsTheIntTypeForOddLengths() {
        assertEquals(PyNum.of(2), PyStatistics.median(List.of(PyNum.of(3), PyNum.of(1), PyNum.of(2))));
        assertEquals(PyNum.of(2.0), PyStatistics.median(List.of(PyNum.of(1), PyNum.of(3))));
        double[] q = PyStatistics.quantiles(new double[] {1.0, 2.0}, 100, PyStatistics.Method.INCLUSIVE);
        assertEquals(99, q.length);
        assertEquals(1.01, q[0]);
    }
}
