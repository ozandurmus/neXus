package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Asserts the numeric layer against vectors computed by CPython 3.12 (tools/packet_analysis/numeric/gen_cases.py).
 * Strings must be equal; floats bit-equal via {@link Double#doubleToRawLongBits}, except that for NaN only NaN-ness
 * is compared (the sign/payload of a NaN produced by arithmetic is CPU-specific, e.g. x86 sets the sign bit, and is
 * invisible in repr/json output); ints equal; Python exceptions map to Java exception classes.
 */
class CPythonDifferentialTest {

    private static JsonNode doc;
    private static double[] pool;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = CPythonDifferentialTest.class
                .getResourceAsStream("/packetanalysis/numeric/reference_vectors.json.gz")) {
            if (in == null) {
                fail("missing reference_vectors.json.gz");
            }
            doc = new ObjectMapper().readTree(new GZIPInputStream(in));
        }
        assertEquals("3.12", doc.get("interpreter").asText().substring(0, 4), "vectors must come from CPython 3.12");
        JsonNode p = doc.get("pool");
        pool = new double[p.size()];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = floatOf(p.get(i).asText());
        }
    }

    // ---------------------------------------------------------------- decoding and comparison helpers

    static double floatOf(String enc) {
        if (!enc.startsWith("f:")) {
            throw new IllegalStateException("not a float: " + enc);
        }
        return Double.longBitsToDouble(Long.parseUnsignedLong(enc.substring(2), 16));
    }

    static PyNum numOf(String enc) {
        if (enc.startsWith("i:")) {
            return PyNum.of(Long.parseLong(enc.substring(2)));
        }
        return PyNum.of(floatOf(enc));
    }

    static List<PyNum> numsOf(JsonNode arr) {
        List<PyNum> out = new ArrayList<>(arr.size());
        arr.forEach(n -> out.add(numOf(n.asText())));
        return out;
    }

    static String encode(PyNum n) {
        if (n instanceof PyInt i) {
            return "i:" + i.value();
        }
        double d = n.toDouble();
        return Double.isNaN(d) ? "f:nan" : "f:" + hex(d);
    }

    static String normalize(String expected) {
        if (expected.startsWith("f:")) {
            double d = floatOf(expected);
            return Double.isNaN(d) ? "f:nan" : expected;
        }
        return expected;
    }

    static String errorOf(Throwable t) {
        if (t instanceof ArithmeticException) {
            return "Arithmetic";
        }
        if (t instanceof UnsupportedOperationException) {
            return "Unsupported:" + t.getMessage();
        }
        if (t instanceof IllegalArgumentException) {
            return "IllegalArgument";
        }
        return t.getClass().getSimpleName() + ":" + t.getMessage();
    }

    /** The Java exception class each CPython exception must map to. */
    static String expectedError(String referenceError) {
        return switch (referenceError) {
            case "!ValueError", "!StatisticsError" -> "IllegalArgument";
            case "!OverflowError", "!ZeroDivisionError", "!IntTooWide" -> "Arithmetic";
            default -> "unmapped " + referenceError;
        };
    }

    /** Collects mismatches of one section and fails with the first few. */
    static final class Check {
        private final String section;
        private final List<String> failures = new ArrayList<>();
        private int cases;
        private int mismatches;

        Check(String section) {
            this.section = section;
        }

        /** Compares an encoded result (string, "i:", "f:") or a Python error with what Java produced. */
        void expect(String context, String expected, Supplier<String> actual) {
            cases++;
            String want = expected.startsWith("!") ? "!" + expectedError(expected) : normalize(expected);
            String got;
            try {
                got = actual.get();
            } catch (RuntimeException e) {
                got = "!" + errorOf(e);
            }
            if (!want.equals(got)) {
                mismatches++;
                if (failures.size() < 25) {
                    failures.add(context + ": expected " + want + " (reference " + expected + "), got " + got);
                }
            }
        }

        void done(int minimumCases) {
            System.out.printf("reference-vectors %-14s %6d cases, %d mismatches%n", section, cases, mismatches);
            if (mismatches > 0) {
                fail(section + ": " + mismatches + " of " + cases + " mismatches\n" + String.join("\n", failures));
            }
            assertTrue(cases >= minimumCases, section + " ran only " + cases + " cases");
        }
    }

    static String hex(double d) {
        String h = Long.toHexString(Double.doubleToRawLongBits(d));
        return "0".repeat(16 - h.length()) + h;
    }

    static boolean allFloats(List<PyNum> nums) {
        return nums.stream().allMatch(n -> n instanceof PyFloat);
    }

    static double[] doubles(List<PyNum> nums) {
        return nums.stream().mapToDouble(PyNum::toDouble).toArray();
    }

    static String encodeAll(List<PyNum> nums) {
        StringBuilder sb = new StringBuilder("[");
        for (PyNum n : nums) {
            sb.append(encode(n)).append(',');
        }
        return sb.append(']').toString();
    }

    static String expectedAll(JsonNode arr) {
        StringBuilder sb = new StringBuilder("[");
        arr.forEach(n -> sb.append(normalize(n.asText())).append(','));
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------- sections

    @Test
    void reprAndJson() {
        Check repr = new Check("repr");
        JsonNode rows = doc.get("repr");
        for (int i = 0; i < pool.length; i++) {
            double x = pool[i];
            repr.expect(hex(x), rows.get(i).asText(), () -> PyFloatRepr.repr(x));
            if (Double.isFinite(x)) {
                repr.expect("json " + hex(x), rows.get(i).asText(), () -> PyFloatRepr.json(x));
                repr.expect("PyFloat.repr " + hex(x), rows.get(i).asText(), () -> PyNum.of(x).repr());
            }
        }
        repr.done(30_000);
        Check json = new Check("json");
        for (JsonNode row : doc.get("json")) {
            double x = floatOf(row.get(0).asText());
            json.expect(hex(x), row.get(1).asText(), () -> PyFloatRepr.json(x));
            json.expect("strict " + hex(x), row.get(2).asText(), () -> PyFloatRepr.jsonStrict(x));
        }
        json.done(600);
    }

    @Test
    void round() {
        Check r0 = new Check("round(x)");
        JsonNode rows = doc.get("round0");
        for (int i = 0; i < pool.length; i++) {
            double x = pool[i];
            r0.expect(hex(x), rows.get(i).asText(), () -> "i:" + PyRound.round(x));
        }
        r0.done(16_000);
        Check rn = new Check("round(x, n)");
        for (JsonNode row : doc.get("round_n")) {
            double x = floatOf(row.get(0).asText());
            int n = row.get(1).asInt();
            rn.expect(hex(x) + " n=" + n, row.get(2).asText(), () -> encode(PyNum.of(PyRound.round(x, n))));
        }
        rn.done(20_000);
        Check ri = new Check("round(i, n)");
        for (JsonNode row : doc.get("round_int")) {
            long i = ((PyInt) numOf(row.get(0).asText())).value();
            int n = row.get(1).asInt();
            ri.expect(i + " n=" + n, row.get(2).asText(), () -> "i:" + PyRound.round(i, n));
        }
        ri.done(3_000);
    }

    @Test
    void format() {
        Check ff = new Check("format(float)");
        for (JsonNode row : doc.get("format_float")) {
            double x = floatOf(row.get(0).asText());
            String spec = row.get(1).asText();
            ff.expect(hex(x) + " '" + spec + "'", row.get(2).asText(), () -> PyFormat.format(x, spec));
        }
        ff.done(15_000);
        Check fi = new Check("format(int)");
        for (JsonNode row : doc.get("format_int")) {
            PyNum i = numOf(row.get(0).asText());
            String spec = row.get(1).asText();
            fi.expect(i.repr() + " '" + spec + "'", row.get(2).asText(), () -> PyFormat.format(i, spec));
        }
        fi.done(9_000);
    }

    @Test
    void median() {
        Check c = new Check("median");
        for (JsonNode row : doc.get("median")) {
            List<PyNum> data = numsOf(row.get(0));
            String expected = row.get(1).asText();
            String ctx = "n=" + data.size() + " " + encodeAll(data.subList(0, Math.min(6, data.size())));
            c.expect(ctx, expected, () -> encode(PyStatistics.median(data)));
            if (allFloats(data)) {
                c.expect("double[] " + ctx, expected,
                        () -> encode(PyNum.of(PyStatistics.median(doubles(data)))));
            }
        }
        c.done(450);
    }

    @Test
    void quantiles() {
        Check c = new Check("quantiles");
        for (JsonNode row : doc.get("quantiles")) {
            List<PyNum> data = numsOf(row.get(0));
            int n = row.get(1).asInt();
            PyStatistics.Method method = PyStatistics.Method.valueOf(row.get(2).asText().toUpperCase());
            JsonNode exp = row.get(3);
            String expected = exp.isTextual() ? exp.asText() : expectedAll(exp);
            String ctx = "len=" + data.size() + " n=" + n + " " + method;
            c.expect(ctx, expected, () -> encodeAll(PyStatistics.quantiles(data, n, method)));
            if (allFloats(data)) {
                c.expect("double[] " + ctx, expected, () -> {
                    List<PyNum> out = new ArrayList<>();
                    for (double d : PyStatistics.quantiles(doubles(data), n, method)) {
                        out.add(PyNum.of(d));
                    }
                    return encodeAll(out);
                });
            }
        }
        c.done(960);
    }

    @Test
    void sort() {
        Check c = new Check("sort");
        for (JsonNode row : doc.get("sort")) {
            List<PyNum> data = numsOf(row.get(0));
            boolean reverse = row.get(1).asBoolean();
            String expected = expectedAll(row.get(2));
            String ctx = "len=" + data.size() + " reverse=" + reverse;
            c.expect(ctx, expected, () -> encodeAll(PyTimSort.sorted(data, reverse)));
            if (allFloats(data) && !reverse) {
                c.expect("double[] " + ctx, expected, () -> {
                    List<PyNum> out = new ArrayList<>();
                    for (double d : PyTimSort.sorted(doubles(data))) {
                        out.add(PyNum.of(d));
                    }
                    return encodeAll(out);
                });
            }
            if (allFloats(data)) {
                // Keyed form: sort indices by the float at that index; must permute identically.
                List<Integer> idx = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) {
                    idx.add(i);
                }
                c.expect("key " + ctx, expected, () -> {
                    List<Integer> order = PyTimSort.sorted(idx, data::get, PyTimSort.NUM_LT, reverse);
                    List<PyNum> out = new ArrayList<>();
                    order.forEach(i -> out.add(data.get(i)));
                    return encodeAll(out);
                });
            }
        }
        c.done(300);
    }

    @Test
    void sumMinMax() {
        Check s = new Check("sum");
        for (JsonNode row : doc.get("sum")) {
            List<PyNum> data = numsOf(row.get(0));
            String expected = row.get(1).asText();
            String ctx = encodeAll(data.subList(0, Math.min(6, data.size())));
            s.expect(ctx, expected, () -> encode(PyBuiltins.sum(data)));
            if (allFloats(data) && !data.isEmpty()) {
                s.expect("double[] " + ctx, expected, () -> encode(PyNum.of(PyBuiltins.sum(doubles(data)))));
            }
        }
        s.done(176);
        Check m = new Check("min/max");
        for (JsonNode row : doc.get("minmax")) {
            List<PyNum> data = numsOf(row.get(0));
            String ctx = encodeAll(data.subList(0, Math.min(6, data.size())));
            m.expect("min " + ctx, row.get(1).asText(), () -> encode(PyBuiltins.min(data)));
            m.expect("max " + ctx, row.get(2).asText(), () -> encode(PyBuiltins.max(data)));
            if (allFloats(data)) {
                m.expect("min double[] " + ctx, row.get(1).asText(),
                        () -> encode(PyNum.of(PyBuiltins.min(doubles(data)))));
                m.expect("max double[] " + ctx, row.get(2).asText(),
                        () -> encode(PyNum.of(PyBuiltins.max(doubles(data)))));
            }
        }
        m.done(352);
    }

    @Test
    void division() {
        Check di = new Check("int // % /");
        for (JsonNode row : doc.get("div_int")) {
            long a = ((PyInt) numOf(row.get(0).asText())).value();
            long b = ((PyInt) numOf(row.get(1).asText())).value();
            String ctx = a + " op " + b;
            di.expect("// " + ctx, row.get(2).asText(), () -> "i:" + PyMath.floorDiv(a, b));
            di.expect("% " + ctx, row.get(3).asText(), () -> "i:" + PyMath.mod(a, b));
            di.expect("/ " + ctx, row.get(4).asText(), () -> encode(PyNum.of(PyMath.trueDivide(a, b))));
            di.expect("PyArith // " + ctx, row.get(2).asText(),
                    () -> encode(PyArith.floorDiv(PyNum.of(a), PyNum.of(b))));
            di.expect("PyArith % " + ctx, row.get(3).asText(), () -> encode(PyArith.mod(PyNum.of(a), PyNum.of(b))));
            di.expect("PyArith / " + ctx, row.get(4).asText(),
                    () -> encode(PyArith.trueDiv(PyNum.of(a), PyNum.of(b))));
        }
        di.done(9_000);
        Check df = new Check("float // %");
        for (JsonNode row : doc.get("div_float")) {
            double a = floatOf(row.get(0).asText());
            double b = floatOf(row.get(1).asText());
            String ctx = hex(a) + " op " + hex(b);
            df.expect("// " + ctx, row.get(2).asText(), () -> encode(PyArith.floorDiv(PyNum.of(a), PyNum.of(b))));
            df.expect("% " + ctx, row.get(3).asText(), () -> encode(PyArith.mod(PyNum.of(a), PyNum.of(b))));
        }
        df.done(5_000);
        Check fc = new Check("floor/ceil/int");
        JsonNode rows = doc.get("float_to_int");
        for (int i = 0; i < pool.length; i++) {
            double x = pool[i];
            JsonNode row = rows.get(i);
            fc.expect("floor " + hex(x), row.get(0).asText(), () -> "i:" + PyMath.floor(x));
            fc.expect("ceil " + hex(x), row.get(1).asText(), () -> "i:" + PyMath.ceil(x));
            fc.expect("int " + hex(x), row.get(2).asText(), () -> "i:" + PyConvert.toInt(x));
        }
        fc.done(48_000);
    }

    @Test
    void parse() {
        Check pf = new Check("float(str)");
        for (JsonNode row : doc.get("parse_float")) {
            String s = row.get(0).asText();
            pf.expect("'" + s + "'", row.get(1).asText(), () -> encode(PyNum.of(PyConvert.parseFloat(s))));
        }
        pf.done(1_500);
        Check pi = new Check("int(str, base)");
        for (JsonNode row : doc.get("parse_int")) {
            String s = row.get(0).asText();
            int base = row.get(1).asInt();
            pi.expect("'" + s + "' base " + base, row.get(2).asText(), () -> "i:" + PyConvert.parseInt(s, base));
        }
        pi.done(30);
    }

    @Test
    void fromTimestamp() {
        Check c = new Check("fromtimestamp");
        for (JsonNode row : doc.get("fromtimestamp")) {
            double t = floatOf(row.get(0).asText());
            c.expect(hex(t) + " (" + t + ")", row.get(1).asText(), () -> "i:" + PyTime.fromTimestampMicros(t));
        }
        c.done(2_500);
    }
}
