package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference {@code clock.ClockModel} (a mutable dataclass). Numeric fields keep the reference int/float
 * distinction ({@code ClockModel(0, 0, epoch, 0, ...)} serializes {@code 0}, the defaults {@code 0.0}).
 */
public final class ClockModel {
    public PyNum offset;
    public PyNum drift = new PyFloat(0.0);
    public PyNum epoch = new PyFloat(0.0);
    public PyNum uncertainty;
    public String confidence = "unknown";
    public String reason = "Bidirectional matched packets are required";
    public long samples;
    public List<Map<String, Object>> evidence = new ArrayList<>();
    public String domain;
    public String sourceDomain;
    public PyNum timestampJitter = new PyFloat(0.0);

    public ClockModel() {
    }

    /** Positional constructor of the reference: offset, drift, epoch, uncertainty, confidence, reason, samples. */
    public static ClockModel of(PyNum offset, PyNum drift, PyNum epoch, PyNum uncertainty, String confidence,
            String reason, long samples) {
        ClockModel m = new ClockModel();
        m.offset = offset;
        m.drift = drift;
        m.epoch = epoch;
        m.uncertainty = uncertainty;
        m.confidence = confidence;
        m.reason = reason;
        m.samples = samples;
        return m;
    }

    /** {@code dataclasses.replace(self)}: a shallow copy (the evidence list object is shared, as in the reference). */
    public ClockModel copy() {
        ClockModel m = new ClockModel();
        m.offset = offset;
        m.drift = drift;
        m.epoch = epoch;
        m.uncertainty = uncertainty;
        m.confidence = confidence;
        m.reason = reason;
        m.samples = samples;
        m.evidence = evidence;
        m.domain = domain;
        m.sourceDomain = sourceDomain;
        m.timestampJitter = timestampJitter;
        return m;
    }

    /** {@code epoch + (timestamp - epoch - offset) / (1 + drift)}; null without an offset. */
    public Double correct(double timestamp) {
        if (offset == null) {
            return null;
        }
        PyNum t = new PyFloat(timestamp);
        PyNum num = PyArith.sub(PyArith.sub(t, epoch), offset);
        PyNum den = PyArith.add(new PyInt(1), drift);
        return ((PyFloat) PyArith.add(epoch, PyArith.trueDiv(num, den))).value();
    }

    public static double value(PyNum n) {
        return n instanceof PyInt i ? (double) i.value() : ((PyFloat) n).value();
    }

    public static Object json(PyNum n) {
        if (n == null) {
            return null;
        }
        return n instanceof PyInt i ? (Object) i.value() : (Object) ((PyFloat) n).value();
    }

    /** {@code {**asdict(self), "drift_ppm": self.drift * 1e6}}. */
    public Map<String, Object> json() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offset", json(offset));
        out.put("drift", json(drift));
        out.put("epoch", json(epoch));
        out.put("uncertainty", json(uncertainty));
        out.put("confidence", confidence);
        out.put("reason", reason);
        out.put("samples", samples);
        out.put("evidence", Evidence.deepCopy(evidence));
        out.put("domain", domain);
        out.put("source_domain", sourceDomain);
        out.put("timestamp_jitter", json(timestampJitter));
        out.put("drift_ppm", json(PyArith.mul(drift, new PyFloat(1e6))));
        return out;
    }
}
