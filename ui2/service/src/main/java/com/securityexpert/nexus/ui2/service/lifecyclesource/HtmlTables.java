package com.securityexpert.nexus.ui2.service.lifecyclesource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bounded reader of the plain HTML tables of a vendor lifecycle page (contract rule 7: untrusted input). It reads text
 * only: no script runs, no entity is resolved from outside, no link is followed, nothing is rendered. Each table comes with
 * the text of the nearest heading before it and its {@code id} attribute; each cell is its text with tags removed, the
 * common character references decoded and white space collapsed. A nested table is read as part of its parent's cell
 * text. {@link Document#text()} is the text of the first 400 000 characters only (enough for a bulletin header). Never
 * throws; a document over the caps yields no tables.
 */
public final class HtmlTables {

    public record Table(String heading, String id, List<List<String>> rows) { }

    public record Document(String title, List<Table> tables, String text) { }

    static final int MAX_DOCUMENT_CHARS = 16 * 1024 * 1024;
    static final int MAX_TABLES = 200;
    static final int MAX_ROWS = 5_000;
    static final int MAX_CELL_CHARS = 2_000;

    private static final Pattern TABLE = Pattern.compile("<table\\b([^>]*)>(.*?)</table\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>(.*?)(?=<tr\\b|</tbody|</thead|</tfoot|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<t[dh]\\b[^>]*>(.*?)(?=<t[dh]\\b|</tr|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("\\bid\\s*=\\s*\"([A-Za-z0-9_-]{1,80})\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP = Pattern.compile("<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>|<!--.*?-->", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]{0,2000}>");
    private static final Pattern REFERENCE = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z]{2,8});");

    /** Wall-clock budget for the regular expressions of one {@link #read}: a crafted page cannot hold a thread longer. */
    static final long MAX_PARSE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);

    private HtmlTables() {
    }

    /** Thrown from {@link Deadline#charAt} when the parse budget is spent; {@link #read} turns it into "no tables". */
    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super("html parse budget exceeded", null, false, false);
        }
    }

    /**
     * A {@link CharSequence} that stops a runaway regular expression: the lazy block patterns above are quadratic on a
     * page full of unclosed tags (untrusted input, contract rule 7), so every matcher reads through this view and the
     * clock is checked every 4096 reads. All views of one parse share one deadline; linear pages never come near it.
     */
    private static final class Deadline implements CharSequence {
        private final CharSequence text;
        private final long expiresAt;
        private int reads;

        Deadline(CharSequence text, long expiresAt) {
            this.text = text;
            this.expiresAt = expiresAt;
        }

        @Override public int length() {
            return text.length();
        }

        @Override public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() - expiresAt > 0) throw new BudgetExceeded();
            return text.charAt(index);
        }

        @Override public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override public String toString() {
            return text.toString();
        }
    }

    public static Document read(byte[] document) {
        return read(document, MAX_PARSE_NANOS);
    }

    static Document read(byte[] document, long budgetNanos) {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_CHARS) return new Document("", List.of(), "");
        try {
            return parse(new String(document, StandardCharsets.UTF_8), budgetNanos);
        } catch (BudgetExceeded exceeded) {
            return new Document("", List.of(), "");
        }
    }

    private static Document parse(String source, long budgetNanos) {
        long expiresAt = System.nanoTime() + budgetNanos;
        String html = DROP.matcher(new Deadline(source, expiresAt)).replaceAll(" ");
        Matcher title = TITLE.matcher(new Deadline(html, expiresAt));
        String titleText = title.find() ? text(title.group(1)) : "";
        List<int[]> headingSpans = new ArrayList<>();
        Matcher headings = HEADING.matcher(new Deadline(html, expiresAt));
        while (headings.find()) headingSpans.add(new int[]{headings.start(1), headings.end(1)});
        List<Table> tables = new ArrayList<>();
        Matcher table = TABLE.matcher(new Deadline(html, expiresAt));
        int next = 0;
        String heading = "";
        while (table.find() && tables.size() < MAX_TABLES) {
            while (next < headingSpans.size() && headingSpans.get(next)[1] <= table.start()) {
                heading = text(html.substring(headingSpans.get(next)[0], headingSpans.get(next)[1]));
                next++;
            }
            Matcher id = ID.matcher(table.group(1));
            List<List<String>> rows = new ArrayList<>();
            Matcher row = ROW.matcher(new Deadline(table.group(2), expiresAt));
            while (row.find() && rows.size() < MAX_ROWS) {
                List<String> cells = new ArrayList<>();
                Matcher cell = CELL.matcher(new Deadline(row.group(1), expiresAt));
                while (cell.find()) cells.add(text(cell.group(1)));
                rows.add(List.copyOf(cells));
            }
            tables.add(new Table(heading, id.find() ? id.group(1) : null, List.copyOf(rows)));
        }
        return new Document(titleText, List.copyOf(tables), text(html.length() > 400_000 ? html.substring(0, 400_000) : html));
    }

    /** The text of a fragment: tags removed, references decoded, white space collapsed, capped. */
    static String text(String fragment) {
        String plain = TAG.matcher(fragment).replaceAll(" ");
        Matcher ref = REFERENCE.matcher(plain);
        StringBuilder out = new StringBuilder();
        while (ref.find()) ref.appendReplacement(out, Matcher.quoteReplacement(decode(ref.group(1), ref.group())));
        ref.appendTail(out);
        String collapsed = out.toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
        return collapsed.length() > MAX_CELL_CHARS ? collapsed.substring(0, MAX_CELL_CHARS) : collapsed;
    }

    private static String decode(String body, String original) {
        try {
            if (body.startsWith("#x") || body.startsWith("#X")) return codePoint(Integer.parseInt(body.substring(2), 16), original);
            if (body.startsWith("#")) return codePoint(Integer.parseInt(body.substring(1)), original);
        } catch (NumberFormatException e) {
            return original;
        }
        return switch (body.toLowerCase(Locale.ROOT)) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            case "ndash" -> "–";
            case "mdash" -> "—";
            case "rsquo", "lsquo" -> "'";
            case "times" -> "×";
            case "trade" -> "™";
            case "reg" -> "®";
            default -> original;
        };
    }

    private static String codePoint(int value, String original) {
        if (value < 0x20 && value != 0x09 && value != 0x0a && value != 0x0d) return " ";
        return Character.isValidCodePoint(value) ? new String(Character.toChars(value)) : original;
    }
}
