package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The reference model-validation (lax mode) coercions used by the topology model: a value present in the input is
 * validated and coerced (an int for a float field becomes a float, an integral float for an int field becomes an
 * int, bools from 0/1 and the usual words); a missing value takes the default <em>unvalidated</em>, so an int
 * default stays an int. Errors are {@link IllegalArgumentException}s naming the field.
 */
final class Fields {

    private static final Set<String> TRUE_WORDS = Set.of("1", "on", "t", "true", "y", "yes");
    private static final Set<String> FALSE_WORDS = Set.of("0", "off", "f", "false", "n", "no");

    private Fields() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object data, String model) {
        if (data instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(model + ": Input should be a valid dictionary or instance of " + model);
    }

    static String str(Map<String, Object> d, String key, String dflt, boolean required, int min, int max) {
        if (!d.containsKey(key)) {
            if (required) {
                throw new IllegalArgumentException(key + ": Field required");
            }
            return dflt;
        }
        return checkStr(key, d.get(key), min, max);
    }

    static String optStr(Map<String, Object> d, String key, int max) {
        Object v = d.get(key);
        return v == null ? null : checkStr(key, v, 0, max);
    }

    private static String checkStr(String key, Object v, int min, int max) {
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(key + ": Input should be a valid string");
        }
        int n = Py.len(s);
        if (n < min) {
            throw new IllegalArgumentException(key + ": String should have at least " + min + " character"
                    + (min == 1 ? "" : "s"));
        }
        if (n > max) {
            throw new IllegalArgumentException(key + ": String should have at most " + max + " characters");
        }
        return s;
    }

    static String literal(Map<String, Object> d, String key, String dflt, Set<String> allowed, boolean nullable) {
        if (!d.containsKey(key)) {
            return dflt;
        }
        Object v = d.get(key);
        if (v == null && nullable) {
            return null;
        }
        if (v instanceof String s && allowed.contains(s)) {
            return s;
        }
        throw new IllegalArgumentException(key + ": Input should be one of " + allowed);
    }

    static boolean bool(Map<String, Object> d, String key, boolean dflt) {
        if (!d.containsKey(key)) {
            return dflt;
        }
        Object v = d.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof PyInt i && (i.value() == 0 || i.value() == 1)) {
            return i.value() == 1;
        }
        if (v instanceof PyFloat f && (f.value() == 0.0 || f.value() == 1.0)) {
            return f.value() == 1.0;
        }
        if (v instanceof String s) {
            String w = s.strip().toLowerCase(Locale.ROOT);
            if (TRUE_WORDS.contains(w)) {
                return true;
            }
            if (FALSE_WORDS.contains(w)) {
                return false;
            }
        }
        throw new IllegalArgumentException(key + ": Input should be a valid boolean");
    }

    static PyInt optInt(Map<String, Object> d, String key) {
        Object v = d.get(key);
        return v == null ? null : toInt(key, v);
    }

    static PyInt intField(Map<String, Object> d, String key, long dflt, long ge, long le) {
        if (!d.containsKey(key)) {
            return new PyInt(dflt);
        }
        PyInt v = toInt(key, d.get(key));
        if (v.value() < ge || v.value() > le) {
            throw new IllegalArgumentException(key + ": Input should be between " + ge + " and " + le);
        }
        return v;
    }

    static PyInt toInt(String key, Object v) {
        if (v instanceof PyInt i) {
            return i;
        }
        if (v instanceof Boolean b) {
            return new PyInt(b ? 1 : 0);
        }
        if (v instanceof PyFloat f && Double.isFinite(f.value()) && f.value() == Math.rint(f.value())
                && Math.abs(f.value()) < 0x1p63) {
            return new PyInt((long) f.value());
        }
        if (v instanceof String s) {
            try {
                return new PyInt(PyConvert.parseInt(s.strip()));
            } catch (RuntimeException e) {
                // fall through to the validation error
            }
        }
        throw new IllegalArgumentException(key + ": Input should be a valid integer");
    }

    /** A float field: present values become floats, a missing one keeps the (possibly int) default. */
    static PyNum flt(Map<String, Object> d, String key, PyNum dflt) {
        if (!d.containsKey(key)) {
            return dflt;
        }
        return toFloat(key, d.get(key));
    }

    static PyFloat toFloat(String key, Object v) {
        if (v instanceof PyFloat f) {
            return f;
        }
        if (v instanceof PyInt i) {
            return new PyFloat(i.toDouble());
        }
        if (v instanceof Boolean b) {
            return new PyFloat(b ? 1.0 : 0.0);
        }
        if (v instanceof String s) {
            try {
                return new PyFloat(PyConvert.parseFloat(s));
            } catch (RuntimeException e) {
                // fall through to the validation error
            }
        }
        throw new IllegalArgumentException(key + ": Input should be a valid number");
    }

    /** A float field with bounds and {@code allow_inf_nan=False} when {@code finite}. */
    static PyNum boundedFloat(Map<String, Object> d, String key, PyNum dflt, Double ge, Double le, boolean finite) {
        if (!d.containsKey(key)) {
            return dflt;
        }
        PyFloat f = toFloat(key, d.get(key));
        double x = f.value();
        if (finite && !Double.isFinite(x)) {
            throw new IllegalArgumentException(key + ": Input should be a finite number");
        }
        if (ge != null && !(x >= ge)) {
            throw new IllegalArgumentException(key + ": Input should be greater than or equal to " + ge);
        }
        if (le != null && !(x <= le)) {
            throw new IllegalArgumentException(key + ": Input should be less than or equal to " + le);
        }
        return f;
    }
}
