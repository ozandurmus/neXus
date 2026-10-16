package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** File-name direction words, device stems and default point layout. */
class NamesAndPointsTest {

    @Test
    void nameDirection() {
        assertEquals("ingress", Suggest.nameDirection("01-fw-ingress.pcap"));
        assertEquals("egress", Suggest.nameDirection("dir/02-FW-Egress.pcapng"));
        assertEquals("ingress", Suggest.nameDirection("eth0rx.pcap"));
        assertNull(Suggest.nameDirection("rx_tx.pcap"), "both words: no direction");
        assertNull(Suggest.nameDirection("inside-outside.pcap"));
        assertNull(Suggest.nameDirection("server.pcap"));
        assertEquals("egress", Suggest.nameDirection("tx.pcap.gz"), "only the last suffix is dropped");
    }

    @Test
    void deviceStem() {
        assertEquals("ark_fw", Suggest.deviceStem("ark_fw_out.pcap", "ark_fw_in.pcap"));
        assertEquals("A", Suggest.deviceStem("a_rx.pcap", "a_tx.pcap"), "a one-letter stem is upper-cased");
        assertEquals("case3_rx", Suggest.deviceStem("case3_rx.pcap", "case3_tx.pcap"),
                "no tokens left: the first file stem");
        assertEquals("edge", Suggest.deviceStem("edge-rx.pcap", "core-tx.pcap"), "no common prefix: first tokens");
    }

    @Test
    void stemFollowsPathlib() {
        assertEquals("a", Suggest.stem("a.pcap"));
        assertEquals(".pcap", Suggest.stem(".pcap"));
        assertEquals("a.", Suggest.stem("a."));
        assertEquals("b.tar", Suggest.stem("x/b.tar.gz"));
    }

    private static Map<String, Object> capture(String id, String name) {
        return dict("id", id, "name", name, "state", "ready", "inventory", dict());
    }

    @Test
    void defaultPointsUseUniqueFileStems() {
        List<Map<String, Object>> caps = List.of(capture("c1", "00-client.pcap"), capture("c2", "fw in.pcap"),
                capture("c3", "srv.pcapng"), capture("c4", "x.pcap"), capture("c5", "y.pcap"));
        Map<String, Point> pts = Groups.defaultPoints(caps, null);
        assertEquals(List.of("00-client", "fw_in", "srv", "x", "y"),
                pts.values().stream().map(p -> p.id).toList());
        Point fifth = pts.get("c5");
        assertEquals("0.0", fifth.x.repr());
        assertEquals("180.0", fifth.y.repr(), "four points per row");
    }

    @Test
    void collidingStemsFallBackToCaptureIds() {
        List<Map<String, Object>> caps = List.of(capture("c1", "tap.pcap"), capture("c2", "tap.pcapng"));
        Map<String, Point> pts = Groups.defaultPoints(caps, null);
        assertEquals("p_c1", pts.get("c1").id);
        assertEquals("tap.pcapng", pts.get("c2").label);
    }

    @Test
    void scaffoldPointsAreRenamedOnlyWhileUntouched() {
        List<Point> existing = new ArrayList<>();
        existing.add(Point.of("p_c1", "a.pcap", "a.pcap", "c1", Py.i(0), Py.i(0)));
        existing.add(Point.of("p_c2", "sub/b.pcap", "sub/b.pcap", "c2", Py.i(240), Py.i(0)));
        existing.add(Point.of("edge", "Edge", "Edge", "c3", Py.i(480), Py.i(0)));
        Map<String, Point> pts = Groups.defaultPoints(
                List.of(capture("c1", "a.pcap"), capture("c2", "b.pcap"), capture("c3", "c.pcap")), existing);
        assertEquals("a", pts.get("c1").id);
        assertEquals("a", pts.get("c1").device, "device equal to the file name follows the rename");
        assertEquals("p_c2", pts.get("c2").id, "a label with a folder is kept");
        assertEquals("edge", pts.get("c3").id, "a user-named point is kept");
        assertEquals("p_c1", existing.get(0).id, "existing points are copied, not edited");
    }

    @Test
    void stemsThatAreNotValidIdsFailLikeTheReference() {
        // "a.b.pcap" has the unique stem "a.b"; '.' is outside the point id pattern, so validation raises.
        assertThrows(IllegalArgumentException.class,
                () -> Groups.defaultPoints(List.of(capture("c1", "a.b.pcap"), capture("c2", "c.pcap")), null));
    }
}
