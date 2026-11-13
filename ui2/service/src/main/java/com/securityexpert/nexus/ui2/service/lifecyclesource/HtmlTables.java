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

    private HtmlTables() {
    }

    public static Document read(byte[] document) {
        if (document == null || document.length == 0 || document.length > MAX_DOCUMENT_CHARS) return new Document("", List.of(), "");
        String html = DROP.matcher(new String(document, StandardCharsets.UTF_8)).replaceAll(" ");
        Matcher title = TITLE.matcher(html);
        String titleText = title.find() ? text(title.group(1)) : "";
        List<int[]> headingSpans = new ArrayList<>();
        Matcher headings = HEADING.matcher(html);
        while (headings.find()) headingSpans.add(new int[]{headings.start(1), headings.end(1)});
        List<Table> tables = new ArrayList<>();
        Matcher table = TABLE.matcher(html);
        int next = 0;
        String heading = "";
        while (table.find() && tables.size() < MAX_TABLES) {
            while (next < headingSpans.size() && headingSpans.get(next)[1] <= table.start()) {
                heading = text(html.substring(headingSpans.get(next)[0], headingSpans.get(next)[1]));
                next++;
            }
            Matcher id = ID.matcher(table.group(1));
            List<List<String>> rows = new ArrayList<>();
            Matcher row = ROW.matcher(table.group(2));
            while (row.find() && rows.size() < MAX_ROWS) {
                List<String> cells = new ArrayList<>();
                Matcher cell = CELL.matcher(row.group(1));
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
