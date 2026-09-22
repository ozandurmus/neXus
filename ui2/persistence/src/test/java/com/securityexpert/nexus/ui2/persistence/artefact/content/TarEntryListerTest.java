package com.securityexpert.nexus.ui2.persistence.artefact.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Entry;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.EntryType;

class TarEntryListerTest {

    /** A minimal ustar writer -- the test's own fixture builder, so the lister is proven against real headers. */
    private static final class TarWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        TarWriter file(String name, byte[] content) throws IOException {
            header(name, content.length, '0');
            out.write(content);
            pad(content.length);
            return this;
        }

        TarWriter dir(String name) throws IOException {
            header(name, 0, '5');
            return this;
        }

        TarWriter longNameFile(String longName, byte[] content) throws IOException {
            byte[] nameBytes = (longName + "\0").getBytes(StandardCharsets.UTF_8);
            header("././@LongLink", nameBytes.length, 'L');
            out.write(nameBytes);
            pad(nameBytes.length);
            header(longName.substring(0, 100), content.length, '0');
            out.write(content);
            pad(content.length);
            return this;
        }

        private void header(String name, long size, char type) throws IOException {
            byte[] h = new byte[512];
            put(h, 0, name);
            put(h, 100, "0000644");
            put(h, 108, "0000000");
            put(h, 116, "0000000");
            put(h, 124, String.format("%011o", size));
            put(h, 136, "00000000000");
            Arrays.fill(h, 148, 156, (byte) ' ');
            h[156] = (byte) type;
            put(h, 257, "ustar");
            put(h, 263, "00");
            int sum = 0;
            for (byte b : h) {
                sum += b & 0xff;
            }
            put(h, 148, String.format("%06o", sum) + "\0 ");
            out.write(h);
        }

        private static void put(byte[] h, int offset, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, h, offset, bytes.length);
        }

        private void pad(long size) throws IOException {
            long padding = (512 - (size % 512)) % 512;
            out.write(new byte[(int) padding]);
        }

        byte[] gzipped() throws IOException {
            out.write(new byte[1024]); // end-of-archive
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(gz)) {
                zip.write(out.toByteArray());
            }
            return gz.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void listsFilesDirectoriesAndDigestsWithoutRetainingContent() throws Exception {
        byte[] fwdir = "fw settings".getBytes(StandardCharsets.UTF_8);
        byte[] shadow = "root:$6$secret".getBytes(StandardCharsets.UTF_8);
        byte[] archive = new TarWriter().dir("etc/").file("etc/shadow", shadow).file("opt/CPsuite/fw1/conf/objects.C", fwdir)
                .gzipped();

        List<Entry> entries = TarEntryLister.list(new ByteArrayInputStream(archive));

        assertEquals(3, entries.size());
        assertEquals(new Entry("etc/", EntryType.DIR, 0, java.util.Optional.empty()), entries.get(0));
        assertEquals("etc/shadow", entries.get(1).path());
        assertEquals(EntryType.FILE, entries.get(1).type());
        assertEquals(shadow.length, entries.get(1).bytes());
        assertEquals(sha256(shadow), entries.get(1).sha256().orElseThrow());
        assertEquals(sha256(fwdir), entries.get(2).sha256().orElseThrow());
        // The listing carries the name and a digest of the secret-bearing file, never its bytes.
        assertTrue(entries.stream().noneMatch(e -> e.toString().contains("secret")));
    }

    @Test
    void resolvesGnuLongNames() throws Exception {
        String longName = "opt/CPsuite-R81.20/fw1/conf/" + "x".repeat(120) + "/objects_5_0.C";
        byte[] archive = new TarWriter().longNameFile(longName, "abc".getBytes(StandardCharsets.UTF_8)).gzipped();

        List<Entry> entries = TarEntryLister.list(new ByteArrayInputStream(archive));

        assertEquals(1, entries.size());
        assertEquals(longName, entries.get(0).path());
        assertEquals(3, entries.get(0).bytes());
    }

    @Test
    void refusesBytesThatAreNotATarArchive() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(gz)) {
            zip.write("<config><system/></config>".getBytes(StandardCharsets.UTF_8));
            zip.write(new byte[600]);
        }

        assertThrows(IOException.class, () -> TarEntryLister.list(new ByteArrayInputStream(gz.toByteArray())));
    }
}
