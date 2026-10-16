package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.Map;

/** A declared clock correction ({@code topology/models.py Override}). */
public record ClockOverride(PyNum offsetMs, PyNum driftPpm) {

    /** {@code Override.model_validate(data)}. */
    public static ClockOverride validate(Object data) {
        Map<String, Object> d = Fields.object(data, "Override");
        if (!d.containsKey("offset_ms")) {
            throw new IllegalArgumentException("offset_ms: Field required");
        }
        PyNum offset = Fields.boundedFloat(d, "offset_ms", null, null, null, true);
        PyNum drift = Fields.boundedFloat(d, "drift_ppm", new PyInt(0), -5000.0, 5000.0, true);
        return new ClockOverride(offset, drift);
    }

    public Map<String, Object> dump() {
        return Py.dict("offset_ms", offsetMs, "drift_ppm", driftPpm);
    }
}
