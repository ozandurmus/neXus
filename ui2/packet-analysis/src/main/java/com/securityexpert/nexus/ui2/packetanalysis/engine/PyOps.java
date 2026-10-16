package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyBuiltins;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyStatistics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reference expression semantics on report-tree values: a reference int is a {@link Long}, a float a
 * {@link Double}, {@code None} is null. Arithmetic goes through the numeric layer ({@link PyArith}), so int/float
 * results, true division, comparisons and formatting are the reference ones.
 */
final class PyOps {
    private PyOps() {
    }

    static PyNum num(Object v) {
        return v == null ? null : Segments.num(v);
    }

    static Object obj(PyNum n) {
        if (n == null) {
            return null;
        }
        return n instanceof PyInt i ? (Object) i.value() : (Object) ((PyFloat) n).value();
    }

    static Object add(Object a, Object b) {
        return obj(PyArith.add(need(a), need(b)));
    }

    static Object sub(Object a, Object b) {
        return obj(PyArith.sub(need(a), need(b)));
    }

    static Object mul(Object a, Object b) {
        return obj(PyArith.mul(need(a), need(b)));
    }

    /** True division {@code a / b}: always a float. */
    static double div(Object a, Object b) {
        return PyArith.trueDiv(need(a), need(b)).value();
    }

    static Object abs(Object a) {
        return obj(PyArith.abs(need(a)));
    }

    static int compare(Object a, Object b) {
        return PyArith.compare(need(a), need(b));
    }

    static boolean lt(Object a, Object b) {
        return PyArith.lt(need(a), need(b));
    }

    static boolean le(Object a, Object b) {
        return PyArith.le(need(a), need(b));
    }

    static boolean gt(Object a, Object b) {
        return PyArith.gt(need(a), need(b));
    }

    static boolean ge(Object a, Object b) {
        return PyArith.ge(need(a), need(b));
    }

    /** {@code a == b} for numbers. */
    static boolean numEq(Object a, Object b) {
        return PyArith.eq(need(a), need(b));
    }

    /** {@code max(a, b)}: {@code a} unless {@code b > a}. */
    static Object max(Object a, Object b) {
        return gt(b, a) ? b : a;
    }

    /** {@code min(a, b)}: {@code a} unless {@code b < a}. */
    static Object min(Object a, Object b) {
        return lt(b, a) ? b : a;
    }

    static double dbl(Object v) {
        return need(v).toDouble();
    }

    private static PyNum need(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("unsupported operand type(s): 'NoneType'");
        }
        return Segments.num(v);
    }

    /** {@code format(x, spec)}. */
    static String fmt(Object v, String spec) {
        return PyFormat.format(need(v), spec);
    }

    /** {@code str(x)} as an f-string field without a spec. */
    static String str(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Long || v instanceof Double) {
            return Segments.num(v).repr();
        }
        if (v instanceof PyNum n) {
            return n.repr();
        }
        return com.securityexpert.nexus.ui2.packetanalysis.topology.Py.repr(toPy(v));
    }

    private static Object toPy(Object v) {
        if (v instanceof Long || v instanceof Double) {
            return Segments.num(v);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) {
                out.add(toPy(x));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey(), toPy(e.getValue()));
            }
            return out;
        }
        return v;
    }

    static boolean truthy(Object v) {
        return Evidence.truthy(v);
    }

    /** {@code statistics.median(values)} (ints stay ints for odd lengths). */
    static Object median(Collection<?> values) {
        List<PyNum> nums = new ArrayList<>();
        for (Object v : values) {
            nums.add(need(v));
        }
        return obj(PyStatistics.median(nums));
    }

    /** {@code sum(values)} (3.12 semantics, int start 0). */
    static Object sum(Collection<?> values) {
        List<PyNum> nums = new ArrayList<>();
        for (Object v : values) {
            nums.add(need(v));
        }
        return obj(PyBuiltins.sum(nums));
    }

    /** {@code max(values, key=...)}: the first element with the largest key. */
    static <T> T maxBy(List<T> values, java.util.Comparator<? super T> order) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("max() arg is an empty sequence");
        }
        T best = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (order.compare(values.get(i), best) > 0) {
                best = values.get(i);
            }
        }
        return best;
    }

    /** {@code min(values, key=...)}: the first element with the smallest key. */
    static <T> T minBy(List<T> values, java.util.Comparator<? super T> order) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("min() arg is an empty sequence");
        }
        T best = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (order.compare(values.get(i), best) < 0) {
                best = values.get(i);
            }
        }
        return best;
    }

    /** A comparator over a numeric key (reference int/float comparison). */
    static <T> java.util.Comparator<T> byNum(Function<T, Object> key) {
        return (x, y) -> compare(key.apply(x), key.apply(y));
    }

    static Map<String, Object> dict(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<>();
        for (Object x : items) {
            out.add(x);
        }
        return out;
    }

    /** Python string comparison (code point order). */
    static int compareStr(String a, String b) {
        return com.securityexpert.nexus.ui2.packetanalysis.topology.Py.compareStr(a, b);
    }

    /** {@code str.capitalize()} for ASCII text. */
    static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Object> lst(Object v) {
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object v) {
        return (List<Map<String, Object>>) v;
    }

    /** {@code d.get(key, fallback)}. */
    static Object get(Map<String, Object> d, String key, Object fallback) {
        return d != null && d.containsKey(key) ? d.get(key) : fallback;
    }
}
