package com.securityexpert.nexus.ui2.packetanalysis.topology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The reference {@code pathlib} pieces the topology stage uses: {@code .name}, {@code .stem} and path order. */
final class PyPaths {

    /** {@code sorted(paths)} order: component lists compared element by element, by code point. */
    static final Comparator<String> PATH_ORDER = (a, b) -> {
        List<String> pa = parts(a);
        List<String> pb = parts(b);
        for (int i = 0; i < Math.min(pa.size(), pb.size()); i++) {
            int c = Py.compareStr(pa.get(i), pb.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.size(), pb.size());
    };

    private PyPaths() {
    }

    /** The {@code str(path).split("/")} components of an absolute POSIX path. */
    static List<String> parts(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/", -1)) {
            out.add(s);
        }
        return out;
    }

    /** {@code PurePosixPath(text).name}: the last non-empty, non-"." component. */
    static String name(String text) {
        String last = "";
        for (String s : text.split("/", -1)) {
            if (!s.isEmpty() && !s.equals(".")) {
                last = s;
            }
        }
        return last;
    }
}
