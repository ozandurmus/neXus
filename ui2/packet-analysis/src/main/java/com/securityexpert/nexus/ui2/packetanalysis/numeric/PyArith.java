package com.securityexpert.nexus.ui2.packetanalysis.numeric;

/**
 * Python binary operators and rich comparisons on {@link PyNum}, with CPython's int/float coercion: int op int stays
 * an int (except true division), anything involving a float is computed in binary64 after converting the int with
 * correct rounding. Comparisons between an int and a float are exact, as in CPython.
 */
public final class PyArith {

    private PyArith() {
    }

    public static PyNum add(PyNum a, PyNum b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            return new PyInt(Math.addExact(x.value(), y.value()));
        }
        return new PyFloat(a.toDouble() + b.toDouble());
    }

    public static PyNum sub(PyNum a, PyNum b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            return new PyInt(Math.subtractExact(x.value(), y.value()));
        }
        return new PyFloat(a.toDouble() - b.toDouble());
    }

    public static PyNum mul(PyNum a, PyNum b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            return new PyInt(Math.multiplyExact(x.value(), y.value()));
        }
        return new PyFloat(a.toDouble() * b.toDouble());
    }

    /** {@code a / b}: always a float; division by zero raises. */
    public static PyFloat trueDiv(PyNum a, PyNum b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            return new PyFloat(PyMath.trueDivide(x.value(), y.value()));
        }
        return new PyFloat(PyMath.trueDivide(a.toDouble(), b.toDouble()));
    }

    /** {@code a // b}. */
    public static PyNum floorDiv(PyNum a, PyNum b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            return new PyInt(PyMath.floorDiv(x.value(), y.value()));
        }
        double divisor = b.toDouble();
        if (divisor == 0.0) {
            throw new ArithmeticException("float floor division by zero");
        }
        return new PyFloat(PyMath.floorDiv(a.toDouble(), divisor));
    }

    /** {@code a % b}. */
    public static PyNum mod(PyNum a, PyNum b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            return new PyInt(PyMath.mod(x.value(), y.value()));
        }
        return new PyFloat(PyMath.mod(a.toDouble(), b.toDouble()));
    }

    public static PyNum neg(PyNum a) {
        if (a instanceof PyInt x) {
            return new PyInt(Math.negateExact(x.value()));
        }
        return new PyFloat(-a.toDouble());
    }

    /** {@code abs(a)}; for floats this clears the sign bit (so {@code abs(-0.0) == 0.0}, {@code abs(nan)} is NaN). */
    public static PyNum abs(PyNum a) {
        if (a instanceof PyInt x) {
            return new PyInt(Math.absExact(x.value()));
        }
        return new PyFloat(Math.abs(a.toDouble()));
    }

    /** Three-way exact comparison, or {@link PyMath#UNORDERED} when a NaN is involved. */
    public static int compare(PyNum a, PyNum b) {
        if (a instanceof PyInt x) {
            if (b instanceof PyInt y) {
                return Long.compare(x.value(), y.value());
            }
            return PyMath.compare(x.value(), b.toDouble());
        }
        double da = a.toDouble();
        if (b instanceof PyInt y) {
            int c = PyMath.compare(y.value(), da);
            return c == PyMath.UNORDERED ? c : -c;
        }
        double db = b.toDouble();
        if (Double.isNaN(da) || Double.isNaN(db)) {
            return PyMath.UNORDERED;
        }
        return da < db ? -1 : da > db ? 1 : 0;
    }

    public static boolean lt(PyNum a, PyNum b) {
        return compare(a, b) == -1;
    }

    public static boolean le(PyNum a, PyNum b) {
        int c = compare(a, b);
        return c == -1 || c == 0;
    }

    public static boolean gt(PyNum a, PyNum b) {
        return compare(a, b) == 1;
    }

    public static boolean ge(PyNum a, PyNum b) {
        int c = compare(a, b);
        return c == 1 || c == 0;
    }

    /** {@code a == b} (so {@code 0 == -0.0} is true and {@code nan == nan} is false). */
    public static boolean eq(PyNum a, PyNum b) {
        return compare(a, b) == 0;
    }
}
