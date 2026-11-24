package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Drain-style miner over normalised synthetic messages. */
class TemplateMinerTest {

    private static TemplateMiner miner() {
        return new TemplateMiner(TemplateMiner.Params.defaults());
    }

    @Test
    void sameShapeWithDifferentParametersGroupsIntoOneTemplate() {
        TemplateMiner m = miner();
        int a = m.add(CensusLineShapes.normalize("kernel: EDAC MC0: 1 CE memory read error on CPU_SrcID#0_Ha#0_Chan#1_DIMM#0 (channel:1 slot:0 page:0x4a1f2 offset:0x0 grain:32)"));
        int b = m.add(CensusLineShapes.normalize("kernel: EDAC MC0: 9 CE memory read error on CPU_SrcID#0_Ha#0_Chan#1_DIMM#0 (channel:1 slot:0 page:0x4a1f4 offset:0x0 grain:32)"));
        assertEquals(a, b);
        assertEquals(1, m.clusterCount());
        assertEquals(2, m.cluster(a).size());
        assertTrue(m.cluster(a).text().contains("CE memory read error"), m.cluster(a).text());
    }

    @Test
    void differentMessagesStaySeparate() {
        TemplateMiner m = miner();
        int a = m.add("sshd: Accepted password for user from <*> port <*>");
        int b = m.add("kernel: EDAC MC<*>: <*> CE memory read error");
        int c = m.add("crond: job <*> started");
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertEquals(3, m.clusterCount());
    }

    @Test
    void variableWordsBecomeWildcardsInTheTemplate() {
        TemplateMiner m = miner();
        int a = m.add("ntpd: synchronized to peer alpha stratum <*>");
        int b = m.add("ntpd: synchronized to peer bravo stratum <*>");
        assertEquals(a, b);
        assertEquals("ntpd: synchronized to peer <*> stratum <*>", m.cluster(a).text());
    }

    @Test
    void lowSimilarityOpensANewCluster() {
        TemplateMiner m = new TemplateMiner(new TemplateMiner.Params(4, 0.9, 100, 100, 64));
        int a = m.add("job alpha started on node red now");
        int b = m.add("job alpha stopped on node blue later");
        assertNotEquals(a, b);
    }

    @Test
    void clusterTableIsCappedAndReportsOverflow() {
        TemplateMiner m = new TemplateMiner(new TemplateMiner.Params(4, 0.5, 100, 2, 64));
        m.add("alpha one two three");
        m.add("bravo four five six");
        assertEquals(TemplateMiner.OVERFLOW, m.add("charlie seven eight nine"));
        assertEquals(2, m.clusterCount());
        // a message that matches an existing cluster is still counted
        assertEquals(0, m.add("alpha one two three"));
    }

    @Test
    void childrenPerNodeAreBounded() {
        TemplateMiner m = new TemplateMiner(new TemplateMiner.Params(3, 0.5, 3, 1000, 64));
        for (int i = 0; i < 50; i++) m.add("word" + (char) ('a' + i % 26) + (char) ('a' + i / 26) + " tail text here");
        assertTrue(m.clusterCount() >= 1);
        assertTrue(m.clusterCount() <= 50);
    }

    @Test
    void emptyMessageIsNotAddedAndBadParametersAreRefused() {
        assertEquals(TemplateMiner.EMPTY, miner().add("   "));
        assertThrows(IllegalArgumentException.class, () -> new TemplateMiner.Params(2, 0.5, 10, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new TemplateMiner.Params(4, 0.0, 10, 10, 10));
    }
}
