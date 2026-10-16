package com.securityexpert.nexus.ui2.packetanalysis.topology;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A capture point of the topology ({@code topology/models.py Point}).
 *
 * <p>Mutable on purpose: the reference assembly edits points in place through several maps (side, device, clock
 * domain, coordinates), and attribute assignment there is not validated. Construction through {@link #of} and
 * {@link #validate} runs the field and model validators; direct field writes do not, exactly like the reference.
 * {@code x}/{@code y} are {@link PyNum}: validated values are floats, assigned ones keep their int type and so dump
 * as {@code 220} rather than {@code 220.0}.
 */
public final class Point {

    static final Set<String> KINDS = Set.of("Client", "Firewall", "IPS", "Router/Switch", "Load Balancer", "Proxy",
            "WAF", "Server", "Generic");
    static final Set<String> SIDES = Set.of("ingress", "egress", "both");
    static final Set<String> VENDORS = Set.of("none", "checkpoint", "f5", "paloalto", "fortinet");
    static final Set<String> LAYERS = Set.of("inner", "outer");
    static final Set<String> TRANSLATIONS = Set.of("none", "nat", "full_proxy", "seq_randomization");
    static final Set<String> TRANSFORMS = Set.of("none", "proxy", "alg", "ssl_inspection");
    private static final Pattern ID = Pattern.compile("[\\w-]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<String> CHECKPOINT_STAGES = List.of("i", "I", "o", "O", "e", "E", "oe", "OE");
    private static final List<String> PALOALTO_STAGES = List.of("receive", "firewall", "transmit", "drop");

    public String id;
    public String label;
    public String device;
    public String kind = "Generic";
    public String side = "both";
    public String captureId;
    public String clockDomain;
    public boolean hostCapture;
    public boolean stageCapture;
    public Long iface;
    public String vendor = "none";
    public String vendorStage;
    public String vendorInterface;
    public boolean inspectionComplete;
    public String matchingLayer;
    public String sourceCidr;
    public String translation = "none";
    public String payloadTransform = "none";
    public PyNum x = new PyInt(0);
    public PyNum y = new PyInt(0);

    private Point() {
    }

    /** {@code Point(id=..., label=..., device=..., capture_id=..., x=..., y=...)} with validation. */
    public static Point of(String id, String label, String device, String captureId, PyNum x, PyNum y) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("label", label);
        d.put("device", device);
        d.put("capture_id", captureId);
        d.put("x", x);
        d.put("y", y);
        return validate(d);
    }

    /** {@code Point.model_validate(data)}. */
    public static Point validate(Object data) {
        Map<String, Object> d = Fields.object(data, "Point");
        Point p = new Point();
        p.id = Fields.str(d, "id", null, true, 1, 80);
        if (!ID.matcher(p.id).matches()) {
            throw new IllegalArgumentException("id: String should match pattern '^[\\w-]+$'");
        }
        p.label = Fields.str(d, "label", null, true, 1, 100);
        p.device = Fields.str(d, "device", null, true, 1, 100);
        p.kind = Fields.literal(d, "kind", "Generic", KINDS, false);
        p.side = Fields.literal(d, "side", "both", SIDES, false);
        p.captureId = Fields.str(d, "capture_id", null, true, 0, Integer.MAX_VALUE);
        p.clockDomain = Fields.optStr(d, "clock_domain", 100);
        p.hostCapture = Fields.bool(d, "host_capture", false);
        p.stageCapture = Fields.bool(d, "stage_capture", false);
        PyInt iface = Fields.optInt(d, "interface");
        if (iface != null && iface.value() < 0) {
            throw new IllegalArgumentException("interface: Input should be greater than or equal to 0");
        }
        p.iface = iface == null ? null : iface.value();
        p.vendor = Fields.literal(d, "vendor", "none", VENDORS, false);
        p.vendorStage = Fields.optStr(d, "vendor_stage", Integer.MAX_VALUE);
        p.vendorInterface = Fields.optStr(d, "vendor_interface", 100);
        p.inspectionComplete = Fields.bool(d, "inspection_complete", false);
        p.matchingLayer = Fields.literal(d, "matching_layer", null, LAYERS, true);
        p.sourceCidr = Fields.optStr(d, "source_cidr", Integer.MAX_VALUE);
        p.translation = Fields.literal(d, "translation", "none", TRANSLATIONS, false);
        p.payloadTransform = Fields.literal(d, "payload_transform", "none", TRANSFORMS, false);
        p.x = Fields.flt(d, "x", new PyInt(0));
        p.y = Fields.flt(d, "y", new PyInt(0));
        p.modelValidator();
        return p;
    }

    private void modelValidator() {
        if ("checkpoint".equals(vendor) && (vendorStage == null || !CHECKPOINT_STAGES.contains(vendorStage))) {
            throw new IllegalArgumentException("Value error, Choose a Check Point inspection stage");
        }
        if ("paloalto".equals(vendor) && (vendorStage == null || !PALOALTO_STAGES.contains(vendorStage))) {
            throw new IllegalArgumentException("Value error, Choose a Palo Alto capture stage");
        }
        if ("paloalto".equals(vendor) && ("receive".equals(vendorStage) || "transmit".equals(vendorStage))) {
            side = "receive".equals(vendorStage) ? "ingress" : "egress";
        }
        if ("f5".equals(vendor)) {
            if (!"client".equals(vendorStage) && !"server".equals(vendorStage)) {
                throw new IllegalArgumentException("Value error, Choose the F5 client or server leg");
            }
            translation = "full_proxy";
        }
        if (sourceCidr != null && !sourceCidr.isEmpty()) {
            IpNetworks.network(sourceCidr);
        }
        if (!Double.isFinite(x.toDouble()) || !Double.isFinite(y.toDouble())) {
            throw new IllegalArgumentException("Value error, Coordinates must be finite");
        }
    }

    /** {@code model_copy(deep=True)}. */
    public Point copy() {
        Point p = new Point();
        p.id = id;
        p.label = label;
        p.device = device;
        p.kind = kind;
        p.side = side;
        p.captureId = captureId;
        p.clockDomain = clockDomain;
        p.hostCapture = hostCapture;
        p.stageCapture = stageCapture;
        p.iface = iface;
        p.vendor = vendor;
        p.vendorStage = vendorStage;
        p.vendorInterface = vendorInterface;
        p.inspectionComplete = inspectionComplete;
        p.matchingLayer = matchingLayer;
        p.sourceCidr = sourceCidr;
        p.translation = translation;
        p.payloadTransform = payloadTransform;
        p.x = x;
        p.y = y;
        return p;
    }

    /** {@code model_dump()} in field order. */
    public Map<String, Object> dump() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("device", device);
        m.put("kind", kind);
        m.put("side", side);
        m.put("capture_id", captureId);
        m.put("clock_domain", clockDomain);
        m.put("host_capture", hostCapture);
        m.put("stage_capture", stageCapture);
        m.put("interface", iface == null ? null : new PyInt(iface));
        m.put("vendor", vendor);
        m.put("vendor_stage", vendorStage);
        m.put("vendor_interface", vendorInterface);
        m.put("inspection_complete", inspectionComplete);
        m.put("matching_layer", matchingLayer);
        m.put("source_cidr", sourceCidr);
        m.put("translation", translation);
        m.put("payload_transform", payloadTransform);
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    static PyNum asFloat(PyNum n) {
        return n instanceof PyFloat ? n : new PyFloat(n.toDouble());
    }
}
