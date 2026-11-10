package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * Palo Alto {@code request license info} and {@code request support info} (docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md
 * section 2). License: {@code result/licenses/entry} with {@code feature}, {@code expires} and {@code expired}, one
 * {@code SUBSCRIPTION} each; {@code serial}, {@code authcode} and {@code description} are never read. Support:
 * {@code result/SupportInfoResponse/Support} with {@code ExpiryDate} and {@code SupportLevel}, one
 * {@code SUPPORT_CONTRACT}; the contact block, links and description are never read.
 */
public final class PaloAltoLicenseParser {

    public static final String LICENSE_KIND = "pan_license_info";
    public static final String SUPPORT_KIND = "pan_support_info";
    private static final String EVIDENCE = "DIRECT_DEVICE";

    private PaloAltoLicenseParser() {
    }

    /** Never throws. */
    public static LicenseParse parseLicenseInfo(String raw) {
        try {
            return licenses(raw);
        } catch (java.io.IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException | RuntimeException
                | StackOverflowError e) {
            return failed(raw);
        }
    }

    /** Never throws. */
    public static LicenseParse parseSupportInfo(String raw) {
        try {
            return support(raw);
        } catch (java.io.IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException | RuntimeException
                | StackOverflowError e) {
            return failed(raw);
        }
    }

    private static LicenseParse failed(String raw) {
        return raw == null || raw.isBlank() ? LicenseParse.of(raw, List.of(), 0) : LicenseParse.unparsed();
    }

    private static boolean success(Element root) {
        return root != null && root.getTagName().equals("response") && "success".equalsIgnoreCase(root.getAttribute("status"));
    }

    private static LicenseParse licenses(String raw) throws java.io.IOException, org.xml.sax.SAXException,
            javax.xml.parsers.ParserConfigurationException {
        if (raw == null || raw.isBlank()) {
            return LicenseParse.of(raw, List.of(), 0);
        }
        Element root = SecureXml.parse(raw);
        Element container = success(root) ? SecureXml.child(SecureXml.child(root, "result"), "licenses") : null;
        if (container == null) {
            return LicenseParse.unparsed();
        }
        List<LicenseItem> items = new ArrayList<>();
        int skipped = 0;
        for (Element entry : SecureXml.children(container, "entry")) {
            String label = LifecycleText.label(SecureXml.text(entry, "feature"));
            if (label == null) {
                skipped++;
                continue;
            }
            String expires = SecureXml.text(entry, "expires");
            boolean expired = SecureXml.text(entry, "expired").equalsIgnoreCase("yes");
            items.add(item(LICENSE_KIND, "SUBSCRIPTION", label, expires, expired));
        }
        return result(items, skipped);
    }

    private static LicenseParse support(String raw) throws java.io.IOException, org.xml.sax.SAXException,
            javax.xml.parsers.ParserConfigurationException {
        if (raw == null || raw.isBlank()) {
            return LicenseParse.of(raw, List.of(), 0);
        }
        Element root = SecureXml.parse(raw);
        Element result = success(root) ? SecureXml.child(root, "result") : null;
        Element support = SecureXml.child(SecureXml.child(result, "SupportInfoResponse"), "Support");
        if (support == null) {
            return LicenseParse.unparsed();
        }
        String label = LifecycleText.label(SecureXml.text(support, "SupportLevel"));
        if (label == null) {
            label = SecureXml.text(support, "SupportLevel").isBlank() ? "Support" : null;
        }
        if (label == null) {
            return new LicenseParse(ParseOutcome.UNPARSED, List.of(), 1);
        }
        return new LicenseParse(ParseOutcome.COLLECTED,
                List.of(item(SUPPORT_KIND, "SUPPORT_CONTRACT", label, SecureXml.text(support, "ExpiryDate"), false)), 0);
    }

    private static LicenseParse result(List<LicenseItem> items, int skipped) {
        if (items.isEmpty()) {
            return skipped > 0 ? new LicenseParse(ParseOutcome.UNPARSED, List.of(), skipped) : LicenseParse.empty(0);
        }
        return new LicenseParse(ParseOutcome.COLLECTED, items, skipped);
    }

    private static LicenseItem item(String kind, String category, String label, String when, boolean expired) {
        LocalDate date = null;
        String state;
        if (LifecycleText.isNever(when)) {
            state = "PERMANENT";
        } else {
            date = LifecycleText.panDate(when);
            state = expired ? "EXPIRED" : date != null ? "VALID" : "UNKNOWN";
        }
        return new LicenseItem(kind, category, label, state, date, null, null, EVIDENCE, null);
    }
}
