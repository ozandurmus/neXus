package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * CPython 3.12 {@code format(value, spec)} — the f-string {@code {x:spec}} — for ints and floats.
 *
 * <p>Supported spec: {@code [[fill]align][sign][#][0][width][,|_][.precision][type]} with float types
 * {@code f F e E g G %} or none, and int types {@code d x X o b} or none (an int with a float type is converted with
 * {@code float(i)} first, as CPython does). Unused corners ({@code n}, {@code c}, {@code z}, {@code #} on floats,
 * zero padding combined with grouping, a precision with no type) throw {@link UnsupportedOperationException} rather
 * than guess.
 *
 * <p>Digits come from the exact binary value rounded half-to-even (dtoa modes 2/3), never from the shortest repr:
 * {@code f"{0.125:.2f}"} is {@code 0.12} and {@code f"{1.005:.2f}"} is {@code 1.00}, where
 * {@code String.format("%.2f")} prints {@code 0.13} and {@code 1.01}. No {@link java.util.Locale} is involved, so a
 * Turkish default locale cannot turn the decimal point into a comma.
 */
public final class PyFormat {

    private PyFormat() {
    }

    /** The parsed format-spec mini-language. */
    record Spec(char fill, char align, char sign, boolean alt, boolean zero, int width, char grouping, int precision,
            char type) {

        static Spec parse(String s) {
            int i = 0;
            int n = s.length();
            char fill = ' ';
            char align = 0;
            if (n >= 2 && isAlign(s.charAt(1))) {
                fill = s.charAt(0);
                align = s.charAt(1);
                i = 2;
            } else if (n >= 1 && isAlign(s.charAt(0))) {
                align = s.charAt(0);
                i = 1;
            }
            char sign = '-';
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-' || s.charAt(i) == ' ')) {
                sign = s.charAt(i++);
            }
            if (i < n && s.charAt(i) == 'z') {
                throw new UnsupportedOperationException("format spec 'z' is not used by PacketBreaker: " + s);
            }
            boolean alt = false;
            if (i < n && s.charAt(i) == '#') {
                alt = true;
                i++;
            }
            boolean zero = false;
            if (i < n && s.charAt(i) == '0') {
                zero = true;
                i++;
            }
            int width = -1;
            int start = i;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                width = Integer.parseInt(s.substring(start, i));
            }
            char grouping = 0;
            if (i < n && (s.charAt(i) == ',' || s.charAt(i) == '_')) {
                grouping = s.charAt(i++);
            }
            int precision = -1;
            if (i < n && s.charAt(i) == '.') {
                i++;
                start = i;
                while (i < n && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("Format specifier missing precision");
                }
                precision = Integer.parseInt(s.substring(start, i));
            }
            char type = 0;
            if (i < n) {
                type = s.charAt(i++);
            }
            if (i != n) {
                throw new IllegalArgumentException("Invalid format specifier '" + s + "'");
            }
            if (zero && align == 0) {
                fill = '0';
                align = '=';
            }
            return new Spec(fill, align, sign, alt, zero, width, grouping, precision, type);
        }

        private static boolean isAlign(char c) {
            return c == '<' || c == '>' || c == '=' || c == '^';
        }
    }

    /** {@code format(x, spec)} for a float. */
    public static String format(double x, String spec) {
        Spec s = Spec.parse(spec);
        return formatFloat(x, s, spec);
    }

    /** {@code format(i, spec)} for an int (or bool used as an int). */
    public static String format(long i, String spec) {
        Spec s = Spec.parse(spec);
        switch (s.type) {
            case 'e', 'E', 'f', 'F', 'g', 'G', '%' -> {
                return formatFloat((double) i, s, spec);
            }
            case 0, 'd', 'x', 'X', 'o', 'b' -> {
                return formatInt(i, s, spec);
            }
            default -> throw new UnsupportedOperationException("int format type not supported: " + spec);
        }
    }

    /** {@code format(x, spec)} for a Python number. */
    public static String format(PyNum x, String spec) {
        return x instanceof PyInt i ? format(i.value(), spec) : format(x.toDouble(), spec);
    }

    private static String formatInt(long value, Spec s, String spec) {
        if (s.precision >= 0) {
            throw new IllegalArgumentException("Precision not allowed in integer format specifier");
        }
        int radix = switch (s.type) {
            case 'x', 'X' -> 16;
            case 'o' -> 8;
            case 'b' -> 2;
            default -> 10;
        };
        if (s.grouping == ',' && radix != 10) {
            throw new IllegalArgumentException("Cannot specify ',' with '" + s.type + "'.");
        }
        boolean negative = value < 0;
        String digits = Long.toUnsignedString(negative ? -value : value, radix);
        if (s.type == 'X') {
            digits = digits.toUpperCase(java.util.Locale.ROOT);
        }
        if (s.grouping != 0) {
            digits = group(digits, s.grouping, radix == 10 ? 3 : 4);
        }
        String prefix = "";
        if (s.alt) {
            prefix = switch (s.type) {
                case 'x' -> "0x";
                case 'X' -> "0X";
                case 'o' -> "0o";
                case 'b' -> "0b";
                default -> "";
            };
        }
        // The base prefix sits with the sign, ahead of any '=' / zero padding: f"{1:#06x}" is 0x0001.
        return pad(signChar(negative, s) + prefix, digits, s, spec);
    }

    private static String formatFloat(double x, Spec s, String spec) {
        if (s.alt) {
            throw new UnsupportedOperationException("'#' on floats is not used by PacketBreaker: " + spec);
        }
        boolean negative = !Double.isNaN(x) && Double.doubleToRawLongBits(x) < 0;
        char type = s.type;
        boolean upper = type == 'F' || type == 'E' || type == 'G';
        String body;
        String suffix = "";
        if (type == '%') {
            x = x * 100;
            negative = !Double.isNaN(x) && Double.doubleToRawLongBits(x) < 0;
            suffix = "%";
        }
        double ax = Math.abs(x);
        if (Double.isNaN(x)) {
            body = upper ? "NAN" : "nan";
        } else if (Double.isInfinite(x)) {
            body = upper ? "INF" : "inf";
        } else {
            body = switch (type) {
                case 'f', 'F', '%' -> fixed(ax, s.precision < 0 ? 6 : s.precision);
                case 'e', 'E' -> scientific(ax, s.precision < 0 ? 6 : s.precision, upper);
                case 'g', 'G' -> general(ax, s.precision < 0 ? 6 : s.precision, upper);
                case 0 -> {
                    if (s.precision >= 0) {
                        throw new UnsupportedOperationException("precision without a type is not used: " + spec);
                    }
                    yield PyFloatRepr.repr(ax);
                }
                default -> throw new UnsupportedOperationException("float format type not supported: " + spec);
            };
        }
        if (s.grouping != 0 && Character.isDigit(body.charAt(0))) {
            int end = 0;
            while (end < body.length() && Character.isDigit(body.charAt(end))) {
                end++;
            }
            body = group(body.substring(0, end), s.grouping, 3) + body.substring(end);
        }
        return pad(signChar(negative, s), body + suffix, s, spec);
    }

    /** dtoa mode 3: exactly {@code precision} digits after the point, half-even on the exact value. */
    static String fixed(double ax, int precision) {
        return new BigDecimal(ax).setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String scientific(double ax, int precision, boolean upper) {
        String digits;
        int exp;
        if (ax == 0.0) {
            digits = "0".repeat(precision + 1);
            exp = 0;
        } else {
            BigDecimal r = new BigDecimal(ax).round(new MathContext(precision + 1, RoundingMode.HALF_EVEN));
            PyFloatRepr.Decimal d = PyFloatRepr.fromBigDecimal(r);
            digits = d.digits() + "0".repeat(precision + 1 - d.digits().length());
            exp = d.decpt() - 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(digits.charAt(0));
        if (precision > 0) {
            sb.append('.').append(digits, 1, digits.length());
        }
        PyFloatRepr.appendExponent(sb, exp);
        String out = sb.toString();
        return upper ? out.toUpperCase(java.util.Locale.ROOT) : out;
    }

    private static String general(double ax, int precision, boolean upper) {
        int p = precision == 0 ? 1 : precision;
        String out;
        if (ax == 0.0) {
            out = "0";
        } else {
            BigDecimal r = new BigDecimal(ax).round(new MathContext(p, RoundingMode.HALF_EVEN));
            PyFloatRepr.Decimal d = PyFloatRepr.fromBigDecimal(r);
            out = PyFloatRepr.layout(false, d.digits(), d.decpt(), p, false);
        }
        return upper ? out.toUpperCase(java.util.Locale.ROOT) : out;
    }

    private static String signChar(boolean negative, Spec s) {
        if (negative) {
            return "-";
        }
        return s.sign == '+' ? "+" : s.sign == ' ' ? " " : "";
    }

    private static String group(String digits, char sep, int every) {
        StringBuilder sb = new StringBuilder(digits.length() + digits.length() / every);
        int first = digits.length() % every;
        if (first == 0) {
            first = every;
        }
        sb.append(digits, 0, Math.min(first, digits.length()));
        for (int i = first; i < digits.length(); i += every) {
            sb.append(sep).append(digits, i, i + every);
        }
        return sb.toString();
    }

    private static String pad(String sign, String body, Spec s, String spec) {
        int len = sign.length() + body.length();
        if (s.width <= len) {
            return sign + body;
        }
        if (s.zero && s.grouping != 0) {
            throw new UnsupportedOperationException("zero padding with grouping is not used: " + spec);
        }
        String fill = String.valueOf(s.fill).repeat(s.width - len);
        char align = s.align == 0 ? '>' : s.align;
        return switch (align) {
            case '<' -> sign + body + fill;
            case '=' -> sign + fill + body;
            case '^' -> {
                int left = (s.width - len) / 2;
                yield fill.substring(0, left) + sign + body + fill.substring(left);
            }
            default -> fill + sign + body;
        };
    }
}
