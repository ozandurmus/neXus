package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference built-ins over the dynamic value model ({@link PyJson}): {@code repr}/{@code str}, truthiness,
 * {@code ==}, ordering comparisons, {@code dict(...)} literals and code-point string order.
 */
public final class Py {

    /** {@code sorted()} order of str: by code point, not UTF-16 unit. */
    public static final Comparator<String> STR_ORDER = Py::compareStr;

    private Py() {
    }

    /** {@code dict(k1=v1, k2=v2, ...)}: an insertion-ordered map. */
    public static Map<String, Object> dict(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("odd key/value list");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    public static PyInt i(long v) {
        return new PyInt(v);
    }

    public static PyFloat f(double v) {
        return new PyFloat(v);
    }

    public static int compareStr(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(i);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
        }
        return Integer.compare(a.length(), b.length());
    }

    /** {@code sorted(strings)}. */
    public static List<String> sortedStr(Collection<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(STR_ORDER);
        return out;
    }

    /** {@code bool(x)}. */
    public static boolean truthy(Object x) {
        if (x == null) {
            return false;
        }
        if (x instanceof Boolean b) {
            return b;
        }
        if (x instanceof PyInt i) {
            return i.value() != 0;
        }
        if (x instanceof PyFloat f) {
            return f.value() != 0.0;
        }
        if (x instanceof String s) {
            return !s.isEmpty();
        }
        if (x instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (x instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (x instanceof PyTuple t) {
            return !t.items().isEmpty();
        }
        return true;
    }

    /** {@code a or b}. */
    public static Object or(Object a, Object b) {
        return truthy(a) ? a : b;
    }

    /** {@code int(x or 0)} for an int-valued JSON or SQL value. */
    public static long intOr0(Object x) {
        if (!truthy(x)) {
            return 0;
        }
        return toInt(x);
    }

    /** {@code int(x)} for a number (floats truncate toward zero). */
    public static long toInt(Object x) {
        if (x instanceof PyInt i) {
            return i.value();
        }
        if (x instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (x instanceof PyFloat f) {
            return com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath.trunc(f.value());
        }
        if (x instanceof String s) {
            return com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert.parseInt(s);
        }
        throw new IllegalArgumentException("int() argument must be a string or a real number, not "
                + typeName(x));
    }

    /** A number as {@link PyNum} ({@code bool} is an int); anything else raises the reference TypeError. */
    public static PyNum num(Object x) {
        if (x instanceof PyNum n) {
            return n;
        }
        if (x instanceof Boolean b) {
            return new PyInt(b ? 1 : 0);
        }
        throw new IllegalArgumentException("'<' not supported between instances of '" + typeName(x) + "'");
    }

    public static boolean lt(Object a, Object b) {
        return PyArith.lt(num(a), num(b));
    }

    public static boolean le(Object a, Object b) {
        return PyArith.le(num(a), num(b));
    }

    public static boolean gt(Object a, Object b) {
        return PyArith.gt(num(a), num(b));
    }

    public static boolean ge(Object a, Object b) {
        return PyArith.ge(num(a), num(b));
    }

    /** {@code a == b} with the reference semantics: {@code 1 == 1.0 == True}, deep for lists and dicts. */
    public static boolean eq(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        boolean an = a instanceof PyNum || a instanceof Boolean;
        boolean bn = b instanceof PyNum || b instanceof Boolean;
        if (an || bn) {
            return an && bn && PyArith.eq(num(a), num(b));
        }
        if (a instanceof String || b instanceof String) {
            return a.equals(b);
        }
        if (a instanceof PyTuple ta) {
            return b instanceof PyTuple tb && listEq(ta.items(), tb.items());
        }
        if (a instanceof List<?> la) {
            return b instanceof List<?> lb && !(b instanceof PyTuple) && listEq(la, lb);
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !eq(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean listEq(List<?> a, List<?> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!eq(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code x in items} by {@link #eq}. */
    public static boolean contains(Collection<?> items, Object x) {
        for (Object item : items) {
            if (eq(item, x)) {
                return true;
            }
        }
        return false;
    }

    /** {@code str(x)}: strings as is, everything else as {@link #repr}. */
    public static String str(Object x) {
        return x instanceof String s ? s : repr(x);
    }

    /** {@code repr(x)}. */
    public static String repr(Object x) {
        StringBuilder out = new StringBuilder();
        repr(out, x);
        return out.toString();
    }

    private static void repr(StringBuilder out, Object x) {
        if (x == null) {
            out.append("None");
        } else if (x instanceof Boolean b) {
            out.append(b ? "True" : "False");
        } else if (x instanceof PyNum n) {
            out.append(n.repr());
        } else if (x instanceof String s) {
            reprStr(out, s);
        } else if (x instanceof PyTuple t) {
            out.append('(');
            join(out, t.items());
            if (t.items().size() == 1) {
                out.append(',');
            }
            out.append(')');
        } else if (x instanceof List<?> l) {
            out.append('[');
            join(out, l);
            out.append(']');
        } else if (x instanceof Map<?, ?> m) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                repr(out, e.getKey());
                out.append(": ");
                repr(out, e.getValue());
            }
            out.append('}');
        } else {
            out.append(x);
        }
    }

    private static void join(StringBuilder out, List<?> items) {
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            repr(out, item);
        }
    }

    private static void reprStr(StringBuilder out, String s) {
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        out.append(quote);
        int i = 0;
        while (i < s.length()) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == quote || c == '\\') {
                out.append('\\').appendCodePoint(c);
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c < 0x20 || c == 0x7f) {
                out.append(String.format(java.util.Locale.ROOT, "\\x%02x", c));
            } else if (c < 0x7f) {
                out.appendCodePoint(c);
            } else if (printable(c)) {
                out.appendCodePoint(c);
            } else if (c <= 0xff) {
                out.append(String.format(java.util.Locale.ROOT, "\\x%02x", c));
            } else if (c <= 0xffff) {
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", c));
            } else {
                out.append(String.format(java.util.Locale.ROOT, "\\U%08x", c));
            }
        }
        out.append(quote);
    }

    /** {@code str.isprintable()} for one non-ASCII code point. */
    private static boolean printable(int c) {
        switch (Character.getType(c)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.SURROGATE:
            case Character.PRIVATE_USE:
            case Character.UNASSIGNED:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.SPACE_SEPARATOR:
                return false;
            default:
                return true;
        }
    }

    /** {@code s[:n]} by code point. */
    public static String prefix(String s, int n) {
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** {@code len(s)} by code point. */
    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    static String typeName(Object x) {
        if (x == null) {
            return "NoneType";
        }
        if (x instanceof String) {
            return "str";
        }
        if (x instanceof PyInt) {
            return "int";
        }
        if (x instanceof PyFloat) {
            return "float";
        }
        if (x instanceof Boolean) {
            return "bool";
        }
        if (x instanceof Map) {
            return "dict";
        }
        if (x instanceof List) {
            return "list";
        }
        return x.getClass().getSimpleName();
    }
}
