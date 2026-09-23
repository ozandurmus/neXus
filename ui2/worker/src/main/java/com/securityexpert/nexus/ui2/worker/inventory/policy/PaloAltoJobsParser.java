package com.securityexpert.nexus.ui2.worker.inventory.policy;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * {@code <show><jobs><all/></jobs></show>}: the latest finished, successful commit job is when the running policy was
 * last installed (a local commit or a Panorama push). EXPECTED SHAPE, UNVERIFIED until the Product Owner's sample is
 * recorded: {@code <job>} with {@code <type>} (Commit / CommitAll / ...), {@code <status>FIN}, {@code <result>OK},
 * {@code <tfin>yyyy/MM/dd HH:mm:ss}. The user element is never read. DOCTYPE refused.
 */
public final class PaloAltoJobsParser {

    private static final DateTimeFormatter TFIN = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH);
    static final ZoneId FIREWALL_ZONE = ZoneId.of("Europe/Istanbul");

    private PaloAltoJobsParser() {
    }

    public static PolicyInstallRead parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return PolicyInstallRead.NONE;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(new InputSource(new StringReader(xml)));
            NodeList jobs = doc.getElementsByTagName("job");
            String bestText = null;
            LocalDateTime best = null;
            String bestType = null;
            for (int i = 0; i < jobs.getLength(); i++) {
                Element job = (Element) jobs.item(i);
                String type = text(job, "type");
                if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("commit")) {
                    continue;
                }
                if (!"FIN".equalsIgnoreCase(text(job, "status")) || !"OK".equalsIgnoreCase(text(job, "result"))) {
                    continue;
                }
                String tfin = text(job, "tfin");
                if (tfin == null) {
                    continue;
                }
                try {
                    LocalDateTime t = LocalDateTime.parse(tfin.strip(), TFIN);
                    if (best == null || t.isAfter(best)) {
                        best = t;
                        bestText = tfin.strip();
                        bestType = type;
                    }
                } catch (DateTimeParseException ignored) {
                    // not this job
                }
            }
            if (best == null) {
                return new PolicyInstallRead(Optional.empty(), Optional.empty(), Optional.empty(), "pan_show_jobs_all");
            }
            // PAN-OS has no single policy name; the job type (local Commit or a Panorama push) goes with the source
            return new PolicyInstallRead(Optional.empty(), Optional.of(bestText),
                    Optional.of(best.atZone(FIREWALL_ZONE).toInstant()), "pan_show_jobs_all:" + bestType);
        } catch (Exception e) {
            return PolicyInstallRead.NONE;
        }
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent();
    }
}
