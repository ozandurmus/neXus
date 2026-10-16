package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vendor metadata decoded by tshark into the {@code vendor} column (the reference {@code decoded_vendor}). */
public final class VendorFields {

    private static final List<String> TRIGGERS = List.of("fw1.direction", "f5ethtrailer.flowid",
            "f5ethtrailer.ingress", "f5ethtrailer.peeraddr", "f5ethtrailer.peeraddr6", "f5ethtrailer.rstcausetxt");

    private VendorFields() {
    }

    private static boolean present(Map<String, String> fields, String k) {
        String v = fields.get(k);
        return v != null && !v.isEmpty();
    }

    /** JSON of the non-empty vendor fields plus adapter/stage, or {@code null} when no vendor field is present. */
    public static String decoded(Map<String, String> fields) {
        if (TRIGGERS.stream().noneMatch(k -> present(fields, k))) {
            return null;
        }
        Map<String, Object> decoded = new LinkedHashMap<>();
        for (String key : TsharkFields.VENDOR_FIELDS) {
            if (present(fields, key)) {
                decoded.put(key, fields.get(key));
            }
        }
        boolean vendor = false;
        for (String k : TsharkFields.concat(TsharkFields.CHECKPOINT_FIELDS, TsharkFields.F5_FIELDS)) {
            if (decoded.containsKey(k)) {
                vendor = true;
                break;
            }
        }
        if (!vendor) {
            return null;
        }
        Object direction = decoded.get("fw1.direction");
        if (direction != null && !((String) direction).isEmpty()) {
            String chain = (String) decoded.getOrDefault("fw1.chain", "");
            String both = direction + chain;
            decoded.put("adapter", "checkpoint");
            decoded.put("stage", both.equals("oe") || both.equals("OE") ? both : direction);
        } else {
            decoded.put("adapter", "f5");
        }
        return PyJson.dumps(decoded);
    }
}
