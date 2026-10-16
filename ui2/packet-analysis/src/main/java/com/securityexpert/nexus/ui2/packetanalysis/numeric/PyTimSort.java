package com.securityexpert.nexus.ui2.packetanalysis.numeric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * A line-by-line port of CPython 3.12 {@code list.sort} ({@code Objects/listobject.c}: count_run, binarysort,
 * powersort merge policy, merge_lo/merge_hi with galloping and the adaptive min_gallop).
 *
 * <p>Any stable sort gives the same result as CPython for a consistent total preorder, so for finite keys this class
 * is only needed for exactness, not for stability. It matters when the comparison is <em>inconsistent</em> — a NaN
 * among floats makes every comparison with it false — because the output then depends on the exact sequence of
 * comparisons. Java's sorts either order NaN last ({@link Arrays#sort(double[])}, which also orders -0.0 before
 * 0.0, unlike Python) or may throw "Comparison method violates its general contract". Only {@code <} is ever called,
 * exactly as CPython does.
 */
public final class PyTimSort {

    /** Python's {@code <} for the elements (or keys) being sorted. */
    @FunctionalInterface
    public interface LessThan<T> {
        boolean lt(T a, T b);
    }

    /** Python {@code <} for floats (NaN compares false both ways; -0.0 equals 0.0). */
    public static final LessThan<Double> FLOAT_LT = (a, b) -> a.doubleValue() < b.doubleValue();

    /** Python {@code <} for ints and floats mixed ({@link PyArith#lt}). */
    public static final LessThan<PyNum> NUM_LT = PyArith::lt;

    private static final int MIN_GALLOP = 7;
    private static final int MAX_MERGE_PENDING = 85;

    private PyTimSort() {
    }

    /** {@code sorted(values)} for floats. */
    public static double[] sorted(double[] values) {
        Double[] boxed = new Double[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        sort(boxed, FLOAT_LT, false);
        double[] out = new double[values.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = boxed[i];
        }
        return out;
    }

    /** {@code sorted(values, reverse=reverse)} for Python numbers. */
    public static List<PyNum> sorted(List<? extends PyNum> values, boolean reverse) {
        PyNum[] a = values.toArray(new PyNum[0]);
        sort(a, NUM_LT, reverse);
        return Arrays.asList(a);
    }

    /**
     * {@code sorted(values, key=key, reverse=reverse)}: keys are computed once, in order, then sorted together with
     * their values, as CPython does.
     */
    public static <T, K> List<T> sorted(List<? extends T> values, Function<? super T, ? extends K> key,
            LessThan<? super K> lt, boolean reverse) {
        int n = values.size();
        @SuppressWarnings("unchecked")
        Keyed<T, K>[] a = new Keyed[n];
        for (int i = 0; i < n; i++) {
            T v = values.get(i);
            a[i] = new Keyed<>(key.apply(v), v);
        }
        sort(a, (x, y) -> lt.lt(x.key, y.key), reverse);
        List<T> out = new ArrayList<>(n);
        for (Keyed<T, K> k : a) {
            out.add(k.value);
        }
        return out;
    }

    private record Keyed<T, K>(K key, T value) {
    }

    /** {@code list.sort(reverse=reverse)} in place. */
    public static <T> void sort(T[] a, LessThan<? super T> lt, boolean reverse) {
        int n = a.length;
        if (n < 2) {
            return;
        }
        // CPython reverses before and after sorting so that equal elements keep their original order.
        if (reverse) {
            reverse(a, 0, n);
        }
        new State<T>(a, lt).sortAll();
        if (reverse) {
            reverse(a, 0, n);
        }
    }

    private static void reverse(Object[] a, int lo, int hi) {
        hi--;
        while (lo < hi) {
            Object t = a[lo];
            a[lo] = a[hi];
            a[hi] = t;
            lo++;
            hi--;
        }
    }

    private static final class State<T> {
        private final Object[] a;
        private final LessThan<? super T> lt;
        private final int listlen;
        private int minGallop = MIN_GALLOP;
        private final int[] runBase = new int[MAX_MERGE_PENDING];
        private final int[] runLen = new int[MAX_MERGE_PENDING];
        private final int[] runPower = new int[MAX_MERGE_PENDING];
        private int pending;
        private Object[] tmp = new Object[0];

        State(T[] a, LessThan<? super T> lt) {
            this.a = a;
            this.lt = lt;
            this.listlen = a.length;
        }

        @SuppressWarnings("unchecked")
        private boolean less(Object x, Object y) {
            return lt.lt((T) x, (T) y);
        }

        void sortAll() {
            int lo = 0;
            int nremaining = a.length;
            int minrun = computeMinrun(nremaining);
            do {
                boolean[] descending = new boolean[1];
                int n = countRun(lo, lo + nremaining, descending);
                if (descending[0]) {
                    reverse(a, lo, lo + n);
                }
                if (n < minrun) {
                    int force = nremaining <= minrun ? nremaining : minrun;
                    binarySort(lo, lo + force, lo + n);
                    n = force;
                }
                foundNewRun(n);
                runBase[pending] = lo;
                runLen[pending] = n;
                pending++;
                lo += n;
                nremaining -= n;
            } while (nremaining != 0);
            mergeForceCollapse();
        }

        private static int computeMinrun(int n) {
            int r = 0;
            while (n >= 64) {
                r |= n & 1;
                n >>= 1;
            }
            return n + r;
        }

        /** Length of the run at lo; a strictly descending run sets descending[0]. */
        private int countRun(int lo, int hi, boolean[] descending) {
            descending[0] = false;
            ++lo;
            if (lo == hi) {
                return 1;
            }
            int n = 2;
            if (less(a[lo], a[lo - 1])) {
                descending[0] = true;
                for (lo = lo + 1; lo < hi; ++lo, ++n) {
                    if (!less(a[lo], a[lo - 1])) {
                        break;
                    }
                }
            } else {
                for (lo = lo + 1; lo < hi; ++lo, ++n) {
                    if (less(a[lo], a[lo - 1])) {
                        break;
                    }
                }
            }
            return n;
        }

        /** Binary insertion sort of [lo, hi) where [lo, start) is already sorted. */
        private void binarySort(int lo, int hi, int start) {
            if (lo == start) {
                ++start;
            }
            for (; start < hi; ++start) {
                int l = lo;
                int r = start;
                Object pivot = a[r];
                do {
                    int p = l + ((r - l) >> 1);
                    if (less(pivot, a[p])) {
                        r = p;
                    } else {
                        l = p + 1;
                    }
                } while (l < r);
                System.arraycopy(a, l, a, l + 1, start - l);
                a[l] = pivot;
            }
        }

        private static int powerloop(long s1, long n1, long n2, long n) {
            int result = 0;
            long x = 2 * s1 + n1;
            long y = x + n1 + n2;
            for (;;) {
                ++result;
                if (x >= n) {
                    x -= n;
                    y -= n;
                } else if (y >= n) {
                    break;
                }
                x <<= 1;
                y <<= 1;
            }
            return result;
        }

        private void foundNewRun(int n2) {
            if (pending > 0) {
                int s1 = runBase[pending - 1];
                int n1 = runLen[pending - 1];
                int power = powerloop(s1, n1, n2, listlen);
                while (pending > 1 && runPower[pending - 2] > power) {
                    mergeAt(pending - 2);
                }
                runPower[pending - 1] = power;
            }
        }

        private void mergeForceCollapse() {
            while (pending > 1) {
                int n = pending - 2;
                if (n > 0 && runLen[n - 1] < runLen[n + 1]) {
                    --n;
                }
                mergeAt(n);
            }
        }

        private void mergeAt(int i) {
            int baseA = runBase[i];
            int na = runLen[i];
            int baseB = runBase[i + 1];
            int nb = runLen[i + 1];
            runLen[i] = na + nb;
            if (i == pending - 3) {
                runBase[i + 1] = runBase[i + 2];
                runLen[i + 1] = runLen[i + 2];
                runPower[i + 1] = runPower[i + 2];
            }
            --pending;

            int k = gallopRight(a[baseB], a, baseA, na, 0);
            baseA += k;
            na -= k;
            if (na == 0) {
                return;
            }
            nb = gallopLeft(a[baseA + na - 1], a, baseB, nb, nb - 1);
            if (nb <= 0) {
                return;
            }
            if (na <= nb) {
                mergeLo(baseA, na, baseB, nb);
            } else {
                mergeHi(baseA, na, baseB, nb);
            }
        }

        /** First index k in arr[base, base+n) with key <= arr[base+k] (leftmost insertion point). */
        private int gallopLeft(Object key, Object[] arr, int base, int n, int hint) {
            int ofs = 1;
            int lastofs = 0;
            int h = base + hint;
            if (less(arr[h], key)) {
                int maxofs = n - hint;
                while (ofs < maxofs) {
                    if (less(arr[h + ofs], key)) {
                        lastofs = ofs;
                        ofs = (ofs << 1) + 1;
                    } else {
                        break;
                    }
                }
                if (ofs > maxofs) {
                    ofs = maxofs;
                }
                lastofs += hint;
                ofs += hint;
            } else {
                int maxofs = hint + 1;
                while (ofs < maxofs) {
                    if (less(arr[h - ofs], key)) {
                        break;
                    }
                    lastofs = ofs;
                    ofs = (ofs << 1) + 1;
                }
                if (ofs > maxofs) {
                    ofs = maxofs;
                }
                int k = lastofs;
                lastofs = hint - ofs;
                ofs = hint - k;
            }
            ++lastofs;
            while (lastofs < ofs) {
                int m = lastofs + ((ofs - lastofs) >> 1);
                if (less(arr[base + m], key)) {
                    lastofs = m + 1;
                } else {
                    ofs = m;
                }
            }
            return ofs;
        }

        /** First index k in arr[base, base+n) with key < arr[base+k] (rightmost insertion point). */
        private int gallopRight(Object key, Object[] arr, int base, int n, int hint) {
            int ofs = 1;
            int lastofs = 0;
            int h = base + hint;
            if (less(key, arr[h])) {
                int maxofs = hint + 1;
                while (ofs < maxofs) {
                    if (less(key, arr[h - ofs])) {
                        lastofs = ofs;
                        ofs = (ofs << 1) + 1;
                    } else {
                        break;
                    }
                }
                if (ofs > maxofs) {
                    ofs = maxofs;
                }
                int k = lastofs;
                lastofs = hint - ofs;
                ofs = hint - k;
            } else {
                int maxofs = n - hint;
                while (ofs < maxofs) {
                    if (less(key, arr[h + ofs])) {
                        break;
                    }
                    lastofs = ofs;
                    ofs = (ofs << 1) + 1;
                }
                if (ofs > maxofs) {
                    ofs = maxofs;
                }
                lastofs += hint;
                ofs += hint;
            }
            ++lastofs;
            while (lastofs < ofs) {
                int m = lastofs + ((ofs - lastofs) >> 1);
                if (less(key, arr[base + m])) {
                    ofs = m;
                } else {
                    lastofs = m + 1;
                }
            }
            return ofs;
        }

        private Object[] tmp(int need) {
            if (tmp.length < need) {
                tmp = new Object[need];
            }
            return tmp;
        }

        private void mergeLo(int pa, int na, int pb, int nb) {
            Object[] t = tmp(na);
            System.arraycopy(a, pa, t, 0, na);
            int dest = pa;
            int sa = 0; // cursor into t (run A)
            int sb = pb; // cursor into a (run B)

            a[dest++] = a[sb++];
            --nb;
            if (nb == 0) {
                System.arraycopy(t, sa, a, dest, na);
                return;
            }
            if (na == 1) {
                copyBLo(t, sa, sb, dest, nb);
                return;
            }
            int mg = minGallop;
            outer:
            for (;;) {
                int acount = 0;
                int bcount = 0;
                for (;;) {
                    if (less(a[sb], t[sa])) {
                        a[dest++] = a[sb++];
                        ++bcount;
                        acount = 0;
                        --nb;
                        if (nb == 0) {
                            break outer;
                        }
                        if (bcount >= mg) {
                            break;
                        }
                    } else {
                        a[dest++] = t[sa++];
                        ++acount;
                        bcount = 0;
                        --na;
                        if (na == 1) {
                            copyBLo(t, sa, sb, dest, nb);
                            return;
                        }
                        if (acount >= mg) {
                            break;
                        }
                    }
                }
                ++mg;
                do {
                    mg -= mg > 1 ? 1 : 0;
                    minGallop = mg;
                    int k = gallopRight(a[sb], t, sa, na, 0);
                    acount = k;
                    if (k != 0) {
                        System.arraycopy(t, sa, a, dest, k);
                        dest += k;
                        sa += k;
                        na -= k;
                        if (na == 1) {
                            copyBLo(t, sa, sb, dest, nb);
                            return;
                        }
                        if (na == 0) {
                            break outer;
                        }
                    }
                    a[dest++] = a[sb++];
                    --nb;
                    if (nb == 0) {
                        break outer;
                    }
                    k = gallopLeft(t[sa], a, sb, nb, 0);
                    bcount = k;
                    if (k != 0) {
                        System.arraycopy(a, sb, a, dest, k);
                        dest += k;
                        sb += k;
                        nb -= k;
                        if (nb == 0) {
                            break outer;
                        }
                    }
                    a[dest++] = t[sa++];
                    --na;
                    if (na == 1) {
                        copyBLo(t, sa, sb, dest, nb);
                        return;
                    }
                } while (acount >= MIN_GALLOP || bcount >= MIN_GALLOP);
                ++mg;
                minGallop = mg;
            }
            // Succeed
            if (na != 0) {
                System.arraycopy(t, sa, a, dest, na);
            }
        }

        /** merge_lo CopyB: the last element of A belongs after the rest of B. */
        @SuppressWarnings("unchecked")
        private void copyBLo(Object[] t, int sa, int sb, int dest, int nb) {
            System.arraycopy(a, sb, a, dest, nb);
            a[dest + nb] = t[sa];
        }

        @SuppressWarnings("unchecked")
        private void mergeHi(int pa, int na, int pb, int nb) {
            Object[] t = tmp(nb);
            System.arraycopy(a, pb, t, 0, nb);
            int basea = pa;
            int dest = pb + nb - 1;
            int sb = nb - 1; // cursor into t (run B), from the end
            int sa = pa + na - 1; // cursor into a (run A), from the end

            a[dest--] = a[sa--];
            --na;
            if (na == 0) {
                System.arraycopy(t, 0, a, dest - (nb - 1), nb);
                return;
            }
            if (nb == 1) {
                copyAHi(t, sa, sb, dest, na);
                return;
            }
            int mg = minGallop;
            outer:
            for (;;) {
                int acount = 0;
                int bcount = 0;
                for (;;) {
                    if (less(t[sb], a[sa])) {
                        a[dest--] = a[sa--];
                        ++acount;
                        bcount = 0;
                        --na;
                        if (na == 0) {
                            break outer;
                        }
                        if (acount >= mg) {
                            break;
                        }
                    } else {
                        a[dest--] = t[sb--];
                        ++bcount;
                        acount = 0;
                        --nb;
                        if (nb == 1) {
                            copyAHi(t, sa, sb, dest, na);
                            return;
                        }
                        if (bcount >= mg) {
                            break;
                        }
                    }
                }
                ++mg;
                do {
                    mg -= mg > 1 ? 1 : 0;
                    minGallop = mg;
                    int k = gallopRight(t[sb], a, basea, na, na - 1);
                    k = na - k;
                    acount = k;
                    if (k != 0) {
                        dest -= k;
                        sa -= k;
                        System.arraycopy(a, sa + 1, a, dest + 1, k);
                        na -= k;
                        if (na == 0) {
                            break outer;
                        }
                    }
                    a[dest--] = t[sb--];
                    --nb;
                    if (nb == 1) {
                        copyAHi(t, sa, sb, dest, na);
                        return;
                    }
                    k = gallopLeft(a[sa], t, 0, nb, nb - 1);
                    k = nb - k;
                    bcount = k;
                    if (k != 0) {
                        dest -= k;
                        sb -= k;
                        System.arraycopy(t, sb + 1, a, dest + 1, k);
                        nb -= k;
                        if (nb == 1) {
                            copyAHi(t, sa, sb, dest, na);
                            return;
                        }
                        if (nb == 0) {
                            break outer;
                        }
                    }
                    a[dest--] = a[sa--];
                    --na;
                    if (na == 0) {
                        break outer;
                    }
                } while (acount >= MIN_GALLOP || bcount >= MIN_GALLOP);
                ++mg;
                minGallop = mg;
            }
            // Succeed
            if (nb != 0) {
                System.arraycopy(t, 0, a, dest - (nb - 1), nb);
            }
        }

        /** merge_hi CopyA: the first element of B belongs before the rest of A. */
        @SuppressWarnings("unchecked")
        private void copyAHi(Object[] t, int sa, int sb, int dest, int na) {
            System.arraycopy(a, sa + 1 - na, a, dest + 1 - na, na);
            dest -= na;
            a[dest] = t[sb];
        }
    }
}
