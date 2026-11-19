package com.securityexpert.nexus.ui2.service.notification;

/**
 * The product name shown in notification mail (From display name, subject prefix, test digests). Read from
 * {@code UI2_MAIL_PRODUCT_NAME}; defaults to "neXus". PO 2026-11-18: mail goes out as "Overwatch" ahead of the
 * product rename. Control characters are stripped so the value can never inject a header line.
 */
public final class MailBrand {
    private MailBrand() {}

    public static String name() {
        String value = System.getenv("UI2_MAIL_PRODUCT_NAME");
        if (value == null) return "neXus";
        String clean = value.replaceAll("[\\p{Cntrl}<>\"]", "").strip();
        return clean.isEmpty() || clean.length() > 64 ? "neXus" : clean;
    }
}
