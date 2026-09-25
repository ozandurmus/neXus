package com.securityexpert.nexus.ui2.persistence.artefact.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

class TarWriterStreamingTest {

    @Test
    void streamedMemberIsPaddedLikeAByteArrayMember() throws IOException {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        try (TarWriter tar = new TarWriter(a)) {
            try (OutputStream o = tar.begin("x.bin", 700)) {
                o.write(new byte[700]);
            }
        }
        assertEquals(512 + 1024 + 1024, a.size());
    }

    @Test
    void shortOrLongMemberIsRefused() throws IOException {
        TarWriter tar = new TarWriter(new ByteArrayOutputStream());
        OutputStream o = tar.begin("x.bin", 10);
        o.write(new byte[5]);
        assertThrows(IOException.class, o::close);
        OutputStream p = tar.begin("y.bin", 2);
        assertThrows(IOException.class, () -> p.write(new byte[3]));
    }
}
