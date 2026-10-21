package com.securityexpert.nexus.ui2.sanitizer.format;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.poi.openxml4j.util.ZipSecureFile;

import com.securityexpert.nexus.ui2.sanitizer.core.HostHeaders;
import com.securityexpert.nexus.ui2.sanitizer.core.SerialRule;

/**
 * XLSX, part by part as streams (DATA_SANITIZER_CONTRACT.md §1 "CSV / XLSX: cell by cell with the text rules; sheet
 * structure, formulas' literal values and formatting kept"). The package is opened through POI's
 * {@link ZipSecureFile} (zip-bomb inflation and entry-size limits) and written back entry by entry in the original
 * order: styles, themes, drawings, images and every structural attribute are copied unchanged, so formatting,
 * charts and layout are exactly the input's. Only text is rewritten -- shared strings, inline strings, cached
 * formula string results, string literals inside formulas and defined names, comments, chart caches, pivot cache
 * values, document properties, external hyperlink targets, table column names -- and always through StAX with DTDs
 * and external entities disabled, so a million-row sheet streams in bounded memory.
 *
 * <p>A header cell such as {@code hostname} or {@code device} (first row of a sheet) marks its column: bare
 * single-word values below it, shared or inline, are host names.</p>
 *
 * <p><b>Serial columns (2026-10-21).</b> A header cell that names a serial ({@link SerialRule#isSerialKey}:
 * {@code serial}, {@code Serial Number}, {@code serial_no}, {@code SN}, {@code S/N}, {@code seri no} ...) marks its
 * column: every value below it -- shared, inline, a formula's cached result, and a number or a date, which the text
 * rules never see -- becomes the inventory's one-way {@code SN-} token ({@link Value#serialContext()}). A number or
 * date cell cannot hold a token, so when sanitizing it is rewritten as an inline-string cell
 * ({@code t="inlineStr"}, {@code <is><t>}) or, when it has a formula, as a formula with a string result
 * ({@code t="str"}); its style, formula and every other attribute are kept, so the workbook stays valid. Boolean and
 * error cells stay. A revert leaves the {@code SN-} tokens and the string cells as they are (the vault has no serial
 * kind, like the inventory screens).</p>
 */
public final class XlsxFormat {

    public static final String EMBEDDED_OBJECTS_REMOVED = "embedded_objects_removed";
    static final int MAX_ZIP_ENTRIES = 4096;
    static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final Pattern FORMULA_STRING = Pattern.compile("\"((?:[^\"]|\"\")*)\"");
    private static final Set<String> NEVER_REWRITTEN_SUFFIXES = Set.of("styles+xml", "theme+xml", "calcChain+xml",
            "relationships+xml", "content-types");

    private XlsxFormat() {
    }

    /** True when the zip at {@code file} is an OOXML spreadsheet package. */
    public static boolean isWorkbook(Path file) {
        try (ZipSecureFile zip = new ZipSecureFile(file.toFile())) {
            Map<String, String> types = contentTypes(zip);
            String main = types.get("/xl/workbook.xml");
            return main != null && (main.contains("spreadsheetml.sheet.main") || main.contains("sheet.macroEnabled.main")
                    || main.contains("spreadsheetml.template.main"));
        } catch (IOException | RuntimeException | XMLStreamException notAWorkbook) {
            return false;
        }
    }

    /** @return the number of text values seen */
    public static long process(Path input, OutputStream out, ValueRewriter rewriter) throws IOException {
        return process(input, out, rewriter, name -> { }, null);
    }

    public static long process(Path input, OutputStream out, ValueRewriter rewriter,
            java.util.function.Consumer<String> learner) throws IOException {
        return process(input, out, rewriter, learner, null);
    }

    /**
     * @param learner receives every value found under a host-like header before any part is rewritten, so a host
     *                name that a formula or a note mentions elsewhere in the workbook is replaced there too
     * @param counts  optional counts to record {@link #EMBEDDED_OBJECTS_REMOVED}
     */
    public static long process(Path input, OutputStream out, ValueRewriter rewriter,
            java.util.function.Consumer<String> learner,
            com.securityexpert.nexus.ui2.sanitizer.core.Counts counts) throws IOException {
        return process(input, out, rewriter, learner, counts, counts != null);
    }

    /**
     * @param sanitizing true on the sanitize side: number and date cells under a serial header are rewritten as
     *                   string cells so they can carry their {@code SN-} token; the revert side never changes a
     *                   cell's type
     */
    public static long process(Path input, OutputStream out, ValueRewriter rewriter,
            java.util.function.Consumer<String> learner,
            com.securityexpert.nexus.ui2.sanitizer.core.Counts counts, boolean sanitizing) throws IOException {
        try (ZipSecureFile zip = new ZipSecureFile(input.toFile())) {
            List<ZipArchiveEntry> allEntries = Collections.list(zip.getEntries());
            if (allEntries.size() > MAX_ZIP_ENTRIES) {
                throw new UnsupportedInputException("XLSX_TOO_MANY_ENTRIES", "workbook exceeds maximum zip entry count");
            }
            Map<String, String> types = contentTypes(zip);
            Map<String, ZipArchiveEntry> byName = new HashMap<>();
            for (ZipArchiveEntry e : allEntries) byName.put("/" + e.getName(), e);
            String sst = null;
            List<String> sheets = new ArrayList<>();
            for (Map.Entry<String, String> t : types.entrySet()) {
                if (t.getValue().endsWith("sharedStrings+xml")) sst = t.getKey();
                if (t.getValue().endsWith("worksheet+xml")) sheets.add(t.getKey());
            }
            Map<String, Set<Integer>> hostColumns = new HashMap<>();
            BitSet hostShared = new BitSet();
            Map<String, Set<Integer>> serialColumns = new HashMap<>();
            BitSet serialShared = new BitSet();
            headerAnalysis(zip, byName, sst, sheets, hostColumns, hostShared, serialColumns, serialShared, learner);

            long[] seen = {0};
            long[] totalUncompressed = {0};
            ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(out, 1 << 16));
            Iterator<ZipArchiveEntry> entries = Collections.list(zip.getEntriesInPhysicalOrder()).iterator();
            while (entries.hasNext()) {
                ZipArchiveEntry entry = entries.next();
                String name = "/" + entry.getName();
                String type = contentType(types, name);
                boolean isRels = name.endsWith(".rels");
                boolean isVml = name.endsWith(".vml") || (type != null && type.endsWith("vmlDrawing"));
                boolean isXml = (type != null && type.endsWith("xml")) || name.endsWith(".xml");
                if (!entry.isDirectory() && !isRels && !isVml && !isXml) {
                    if (counts != null) {
                        counts.add(EMBEDDED_OBJECTS_REMOVED);
                    }
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                zipOut.putNextEntry(copy);
                try (InputStream in = new BoundedArchiveStream(zip.getInputStream(entry), totalUncompressed)) {
                    if (entry.isDirectory()) {
                        // nothing to copy
                    } else if (isRels) {
                        rewritePart(in, zipOut, new RelsPolicy(), rewriter, seen);
                    } else if (isVml) {
                        rewritePart(in, zipOut, new GenericPolicy(), rewriter, seen);
                    } else if (type == null || NEVER_REWRITTEN_SUFFIXES.stream().noneMatch(type::endsWith)) {
                        Set<Integer> serialCols = serialColumns.getOrDefault(name, Set.of());
                        PartPolicy policy = name.equals(sst) ? new SharedStringsPolicy(hostShared, serialShared)
                                : sheets.contains(name) ? new SheetPolicy(hostColumns.getOrDefault(name, Set.of()), serialCols)
                                : name.equals("/xl/workbook.xml") ? new WorkbookPolicy()
                                : new GenericPolicy();
                        boolean convert = sanitizing && sheets.contains(name) && !serialCols.isEmpty();
                        rewritePart(in, zipOut, policy, rewriter, seen, convert ? serialCols : null);
                    } else {
                        in.transferTo(zipOut);
                    }
                }
                zipOut.closeEntry();
            }
            zipOut.finish();
            zipOut.flush();
            return seen[0];
        } catch (XMLStreamException malformed) {
            throw new UnsupportedInputException("XLSX_MALFORMED", "a workbook part is not well-formed XML");
        }
    }

    // ---- content types and header analysis -----------------------------------------------------------------

    private static Map<String, String> contentTypes(ZipSecureFile zip) throws IOException, XMLStreamException {
        ZipArchiveEntry entry = zip.getEntry("[Content_Types].xml");
        if (entry == null) throw new UnsupportedInputException("XLSX_NOT_A_WORKBOOK", "no [Content_Types].xml part");
        Map<String, String> types = new HashMap<>();
        Map<String, String> defaults = new HashMap<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLEventReader reader = inputs().createXMLEventReader(in);
            while (reader.hasNext()) {
                XMLEvent e = reader.nextEvent();
                if (!e.isStartElement()) continue;
                StartElement s = e.asStartElement();
                String local = s.getName().getLocalPart();
                if (local.equals("Override")) types.put(attr(s, "PartName"), attr(s, "ContentType"));
                if (local.equals("Default")) defaults.put(attr(s, "Extension").toLowerCase(Locale.ROOT), attr(s, "ContentType"));
            }
        }
        for (ZipArchiveEntry e : Collections.list(zip.getEntries())) {
            String name = "/" + e.getName();
            if (types.containsKey(name)) continue;
            int dot = name.lastIndexOf('.');
            String type = dot < 0 ? null : defaults.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (type != null) types.put(name, type);
        }
        types.put("/[Content_Types].xml", "content-types");
        return types;
    }

    private static String contentType(Map<String, String> types, String name) {
        return types.get(name);
    }

    /**
     * Pass A: every sheet's first-row cells; pass B: the shared strings those cells point at; pass C: the shared
     * string indexes used below a host-like or a serial-like header; pass D: the host values, learned.
     */
    private static void headerAnalysis(ZipSecureFile zip, Map<String, ZipArchiveEntry> byName, String sst,
            List<String> sheets, Map<String, Set<Integer>> hostColumns, BitSet hostShared,
            Map<String, Set<Integer>> serialColumns, BitSet serialShared,
            java.util.function.Consumer<String> learner) throws IOException, XMLStreamException {
        Map<String, Map<Integer, Object>> headers = new HashMap<>();
        Set<Integer> wanted = new HashSet<>();
        for (String sheet : sheets) {
            ZipArchiveEntry e = byName.get(sheet);
            if (e == null) continue;
            Map<Integer, Object> header = new HashMap<>();
            try (InputStream in = zip.getInputStream(e)) {
                XMLEventReader r = inputs().createXMLEventReader(in);
                CellCursor cursor = new CellCursor();
                int col = -1;
                String type = null;
                StringBuilder text = null;
                boolean inRow = false;
                while (r.hasNext()) {
                    XMLEvent ev = r.nextEvent();
                    if (ev.isStartElement()) {
                        String local = ev.asStartElement().getName().getLocalPart();
                        if (local.equals("row")) {
                            inRow = true;
                            cursor.row();
                        } else if (local.equals("c") && inRow) {
                            col = cursor.cell(attr(ev.asStartElement(), "r"));
                            type = attr(ev.asStartElement(), "t");
                        } else if ((local.equals("v") || local.equals("t")) && col >= 0) {
                            text = new StringBuilder();
                        }
                    } else if (ev.isCharacters() && text != null) {
                        text.append(ev.asCharacters().getData());
                    } else if (ev.isEndElement()) {
                        String local = ev.asEndElement().getName().getLocalPart();
                        if ((local.equals("v") || local.equals("t")) && text != null && col >= 0) {
                            if ("s".equals(type) && local.equals("v")) {
                                try {
                                    int index = Integer.parseInt(text.toString().strip());
                                    header.put(col, index);
                                    wanted.add(index);
                                } catch (NumberFormatException ignored) {
                                    // not a shared-string index
                                }
                            } else {
                                header.merge(col, text.toString(), (a, b) -> a instanceof String sa ? sa + b : b);
                            }
                            text = null;
                        } else if (local.equals("row")) {
                            break;
                        }
                    }
                }
            }
            headers.put(sheet, header);
        }
        Map<Integer, String> shared = new HashMap<>();
        if (sst != null && !wanted.isEmpty() && byName.get(sst) != null) {
            try (InputStream in = zip.getInputStream(byName.get(sst))) {
                XMLEventReader r = inputs().createXMLEventReader(in);
                int index = -1;
                StringBuilder text = null;
                boolean inT = false;
                while (r.hasNext()) {
                    XMLEvent ev = r.nextEvent();
                    if (ev.isStartElement()) {
                        String local = ev.asStartElement().getName().getLocalPart();
                        if (local.equals("si")) {
                            index++;
                            text = wanted.contains(index) ? new StringBuilder() : null;
                        } else if (local.equals("t")) inT = true;
                    } else if (ev.isCharacters() && inT && text != null) {
                        text.append(ev.asCharacters().getData());
                    } else if (ev.isEndElement()) {
                        String local = ev.asEndElement().getName().getLocalPart();
                        if (local.equals("t")) inT = false;
                        if (local.equals("si") && text != null) shared.put(index, text.toString());
                    }
                }
            }
        }
        for (String sheet : sheets) {
            Set<Integer> cols = new HashSet<>();
            Set<Integer> serials = new HashSet<>();
            headers.getOrDefault(sheet, Map.of()).forEach((col, value) -> {
                String text = value instanceof Integer i ? shared.get(i) : (String) value;
                // A serial header wins: its column is masked as serials, never learned as host names.
                if (SerialRule.isSerialKey(text)) serials.add(col);
                else if (HostHeaders.isHostHeader(text)) cols.add(col);
            });
            if (cols.isEmpty() && serials.isEmpty()) continue;
            if (!cols.isEmpty()) hostColumns.put(sheet, cols);
            if (!serials.isEmpty()) serialColumns.put(sheet, serials);
            ZipArchiveEntry e = byName.get(sheet);
            try (InputStream in = zip.getInputStream(e)) {
                XMLEventReader r = inputs().createXMLEventReader(in);
                CellCursor cursor = new CellCursor();
                int rows = 0;
                boolean hostCell = false;
                boolean serialCell = false;
                boolean sharedCell = false;
                boolean inlineCell = false;
                StringBuilder text = null;
                StringBuilder inline = null;
                while (r.hasNext()) {
                    XMLEvent ev = r.nextEvent();
                    if (ev.isStartElement()) {
                        StartElement s = ev.asStartElement();
                        String local = s.getName().getLocalPart();
                        if (local.equals("row")) {
                            rows++;
                            cursor.row();
                        } else if (local.equals("c")) {
                            int col = cursor.cell(attr(s, "r"));
                            hostCell = rows > 1 && cols.contains(col);
                            serialCell = rows > 1 && serials.contains(col);
                            sharedCell = "s".equals(attr(s, "t"));
                            inlineCell = "inlineStr".equals(attr(s, "t"));
                        } else if (local.equals("v") && (hostCell || serialCell) && sharedCell) text = new StringBuilder();
                        else if (local.equals("t") && hostCell && inlineCell) inline = new StringBuilder();
                    } else if (ev.isCharacters() && text != null) {
                        text.append(ev.asCharacters().getData());
                    } else if (ev.isCharacters() && inline != null) {
                        inline.append(ev.asCharacters().getData());
                    } else if (ev.isEndElement() && ev.asEndElement().getName().getLocalPart().equals("v") && text != null) {
                        try {
                            int index = Integer.parseInt(text.toString().strip());
                            if (serialCell) serialShared.set(index);
                            else hostShared.set(index);
                        } catch (NumberFormatException ignored) {
                            // not an index
                        }
                        text = null;
                    } else if (ev.isEndElement() && ev.asEndElement().getName().getLocalPart().equals("t") && inline != null) {
                        learner.accept(inline.toString().strip());
                        inline = null;
                    }
                }
            }
        }
        // Pass D: the shared strings that sit under a host header, learned before anything is rewritten.
        if (sst != null && !hostShared.isEmpty() && byName.get(sst) != null) {
            try (InputStream in = zip.getInputStream(byName.get(sst))) {
                XMLEventReader r = inputs().createXMLEventReader(in);
                int index = -1;
                StringBuilder text = null;
                boolean inT = false;
                while (r.hasNext()) {
                    XMLEvent ev = r.nextEvent();
                    if (ev.isStartElement()) {
                        String local = ev.asStartElement().getName().getLocalPart();
                        if (local.equals("si")) {
                            index++;
                            text = hostShared.get(index) && !serialShared.get(index) ? new StringBuilder() : null;
                        } else if (local.equals("t")) inT = true;
                    } else if (ev.isCharacters() && inT && text != null) {
                        text.append(ev.asCharacters().getData());
                    } else if (ev.isEndElement()) {
                        String local = ev.asEndElement().getName().getLocalPart();
                        if (local.equals("t")) inT = false;
                        if (local.equals("si") && text != null) learner.accept(text.toString().strip());
                    }
                }
            }
        }
    }

    /**
     * The zero-based column of each cell of a row: its {@code r} reference, or, when a writer omits {@code r}
     * (optional in SpreadsheetML), the column after the previous cell.
     */
    static final class CellCursor {
        private int column = -1;

        void row() {
            column = -1;
        }

        int cell(String ref) {
            int c = column(ref);
            column = c >= 0 ? c : column + 1;
            return column;
        }
    }

    /** "AB12" -> 27 (zero-based columns); -1 when absent. */
    static int column(String ref) {
        if (ref == null) return -1;
        int col = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? -1 : col - 1;
    }

    // ---- part rewriting ------------------------------------------------------------------------------------

    /** Which text and attributes of a part go through the rules. */
    private abstract static class PartPolicy {
        /** Called for every start element (the stack holds it already). */
        void start(StartElement element, List<String> stack) {
        }

        void end(String local, List<String> stack) {
        }

        /** {@code null}: copy; otherwise how this text node is rewritten. */
        abstract TextMode text(List<String> stack);

        boolean hostContext(List<String> stack) {
            return false;
        }

        /** True when this text node is a whole value under a serial header: it becomes its {@code SN-} token. */
        boolean serialContext(List<String> stack) {
            return false;
        }

        Set<String> attributes(StartElement element) {
            return Set.of();
        }
    }

    private enum TextMode { PLAIN, FORMULA }

    private static final class GenericPolicy extends PartPolicy {
        private static final Set<String> TEXT_ATTRIBUTES = Set.of(
                "v", "name", "displayName", "userId", "connection", "command", "serverCommand", "url",
                "ref", "location", "target", "Target", "value", "author", "address", "host", "server",
                "sourceFile", "ddeService", "ddeTopic");

        @Override
        TextMode text(List<String> stack) {
            return stack.isEmpty() ? null : TextMode.PLAIN;
        }

        @Override
        Set<String> attributes(StartElement element) {
            return TEXT_ATTRIBUTES;
        }
    }

    private static final class RelsPolicy extends PartPolicy {
        @Override
        TextMode text(List<String> stack) {
            return null;
        }

        @Override
        Set<String> attributes(StartElement element) {
            return element.getName().getLocalPart().equals("Relationship") && "External".equals(attr(element, "TargetMode"))
                    ? Set.of("Target") : Set.of();
        }
    }

    private static final class WorkbookPolicy extends PartPolicy {
        @Override
        TextMode text(List<String> stack) {
            return !stack.isEmpty() && stack.get(stack.size() - 1).equals("definedName") ? TextMode.FORMULA : null;
        }

        @Override
        Set<String> attributes(StartElement element) {
            return Set.of("comment", "name");
        }
    }

    private static final class SharedStringsPolicy extends PartPolicy {
        private final BitSet host;
        private final BitSet serial;
        private int index = -1;

        SharedStringsPolicy(BitSet host, BitSet serial) {
            this.host = host;
            this.serial = serial;
        }

        @Override
        void start(StartElement element, List<String> stack) {
            if (element.getName().getLocalPart().equals("si")) index++;
        }

        @Override
        TextMode text(List<String> stack) {
            return !stack.isEmpty() && stack.get(stack.size() - 1).equals("t") ? TextMode.PLAIN : null;
        }

        @Override
        boolean hostContext(List<String> stack) {
            return index >= 0 && host.get(index);
        }

        @Override
        boolean serialContext(List<String> stack) {
            return index >= 0 && serial.get(index);
        }
    }

    private static final class SheetPolicy extends PartPolicy {
        private final Set<Integer> hostColumns;
        private final Set<Integer> serialColumns;
        private final CellCursor cursor = new CellCursor();
        private int rows;
        private int column = -1;
        private String type;

        SheetPolicy(Set<Integer> hostColumns, Set<Integer> serialColumns) {
            this.hostColumns = hostColumns;
            this.serialColumns = serialColumns;
        }

        @Override
        void start(StartElement element, List<String> stack) {
            String local = element.getName().getLocalPart();
            if (local.equals("row")) {
                rows++;
                cursor.row();
            }
            if (local.equals("c")) {
                column = cursor.cell(attr(element, "r"));
                type = attr(element, "t");
            }
        }

        @Override
        TextMode text(List<String> stack) {
            if (stack.isEmpty()) return null;
            String local = stack.get(stack.size() - 1);
            boolean inCell = stack.contains("c");
            if (local.equals("f") && inCell) return TextMode.FORMULA;
            if (local.equals("t") && (stack.contains("is") || inCell)) return TextMode.PLAIN;
            if (local.equals("v") && inCell && ("str".equals(type) || "inlineStr".equals(type) || "e".equals(type))) return TextMode.PLAIN;
            if (local.equals("formula1") || local.equals("formula2") || local.equals("formula")) return TextMode.FORMULA;
            if (local.equals("oddHeader") || local.equals("oddFooter") || local.equals("evenHeader")
                    || local.equals("evenFooter") || local.equals("firstHeader") || local.equals("firstFooter")) return TextMode.PLAIN;
            return null;
        }

        @Override
        boolean hostContext(List<String> stack) {
            return rows > 1 && hostColumns.contains(column);
        }

        /** A string value of a cell below a serial header: an inline string or a formula's string result. */
        @Override
        boolean serialContext(List<String> stack) {
            if (rows <= 1 || !serialColumns.contains(column) || !stack.contains("c") || stack.isEmpty()) return false;
            String local = stack.get(stack.size() - 1);
            return (local.equals("t") && "inlineStr".equals(type)) || (local.equals("v") && "str".equals(type));
        }

        @Override
        Set<String> attributes(StartElement element) {
            return element.getName().getLocalPart().equals("hyperlink") ? Set.of("display", "location", "tooltip") : Set.of();
        }
    }

    /** One buffered output item: an event to copy, or a template whose slots await the batch's answer. */
    private record Item(XMLEvent event, Slot slot) {
    }

    private record Slot(StartElement element, List<String> attributeNames, String[] formulaParts, int[] valueIndexes,
            TextMode mode, int textIndex) {
    }

    private static void rewritePart(InputStream in, OutputStream out, PartPolicy policy, ValueRewriter rewriter,
            long[] seen) throws IOException, XMLStreamException {
        rewritePart(in, out, policy, rewriter, seen, null);
    }

    /** @param serialCells the serial columns whose number and date cells become string cells first; null: none */
    private static void rewritePart(InputStream in, OutputStream out, PartPolicy policy, ValueRewriter rewriter,
            long[] seen, Set<Integer> serialCells) throws IOException, XMLStreamException {
        XMLEventReader reader = inputs().createXMLEventReader(in);
        if (serialCells != null) reader = new SerialCells(reader, serialCells);
        XMLEventWriter writer = XMLOutputFactory.newFactory().createXMLEventWriter(new NonClosingStream(out), "UTF-8");
        XMLEventFactory events = XMLEventFactory.newFactory();
        List<String> stack = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        List<Value> batch = new ArrayList<>();
        long chars = 0;
        while (reader.hasNext()) {
            XMLEvent e = reader.nextEvent();
            if (e.isStartElement()) {
                StartElement s = e.asStartElement();
                stack.add(s.getName().getLocalPart());
                policy.start(s, stack);
                Set<String> names = policy.attributes(s);
                List<String> present = new ArrayList<>();
                for (String n : names) if (attr(s, n) != null) present.add(n);
                if (present.isEmpty()) {
                    items.add(new Item(e, null));
                } else {
                    int[] indexes = new int[present.size()];
                    for (int i = 0; i < present.size(); i++) {
                        indexes[i] = batch.size();
                        String v = attr(s, present.get(i));
                        batch.add(new Value(v, false));
                        chars += v.length();
                    }
                    items.add(new Item(null, new Slot(s, present, null, indexes, null, -1)));
                }
            } else if (e.isEndElement()) {
                policy.end(e.asEndElement().getName().getLocalPart(), stack);
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                items.add(new Item(e, null));
            } else if (e.isCharacters() && !e.asCharacters().isWhiteSpace() || e.isCharacters() && policy.text(stack) != null) {
                Characters c = e.asCharacters();
                TextMode mode = policy.text(stack);
                if (mode == null || c.getData().isEmpty()) {
                    items.add(new Item(e, null));
                } else if (mode == TextMode.PLAIN) {
                    items.add(new Item(null, new Slot(null, null, null, null, mode, batch.size())));
                    batch.add(new Value(c.getData(), policy.hostContext(stack), policy.serialContext(stack)));
                    chars += c.getData().length();
                    seen[0]++;
                } else {
                    // Formula: only the contents of its string literals are text; references stay as they are.
                    List<String> parts = new ArrayList<>();
                    List<Integer> idx = new ArrayList<>();
                    Matcher m = FORMULA_STRING.matcher(c.getData());
                    int last = 0;
                    while (m.find()) {
                        parts.add(c.getData().substring(last, m.start()) + "\"");
                        idx.add(batch.size());
                        String literal = m.group(1).replace("\"\"", "\"");
                        batch.add(new Value(literal, false));
                        chars += literal.length();
                        parts.add(null);
                        last = m.end() - 1;
                    }
                    parts.add(c.getData().substring(last));
                    if (idx.isEmpty()) {
                        items.add(new Item(e, null));
                    } else {
                        items.add(new Item(null, new Slot(null, null, parts.toArray(new String[0]),
                                idx.stream().mapToInt(Integer::intValue).toArray(), mode, -1)));
                        seen[0] += idx.size();
                    }
                }
            } else {
                items.add(new Item(e, null));
            }
            if (batch.size() >= ValueRewriter.BATCH_VALUES || chars >= ValueRewriter.BATCH_CHARS
                    || items.size() >= 4096) {
                flush(items, batch, rewriter, writer, events);
                chars = 0;
            }
        }
        flush(items, batch, rewriter, writer, events);
        writer.flush();
        writer.close();
    }

    private static void flush(List<Item> items, List<Value> batch, ValueRewriter rewriter, XMLEventWriter writer,
            XMLEventFactory events) throws XMLStreamException {
        List<String> out = batch.isEmpty() ? List.of() : rewriter.rewrite(batch);
        for (Item item : items) {
            if (item.event() != null) {
                writer.add(item.event());
                continue;
            }
            Slot slot = item.slot();
            if (slot.element() != null) {
                StartElement s = slot.element();
                List<Attribute> attributes = new ArrayList<>();
                for (Iterator<Attribute> it = s.getAttributes(); it.hasNext(); ) {
                    Attribute a = it.next();
                    int k = slot.attributeNames().indexOf(a.getName().getLocalPart());
                    attributes.add(k >= 0 && a.getName().getPrefix().isEmpty()
                            ? events.createAttribute(a.getName(), out.get(slot.valueIndexes()[k])) : a);
                }
                writer.add(events.createStartElement(s.getName(), attributes.iterator(), s.getNamespaces()));
            } else if (slot.mode() == TextMode.PLAIN) {
                writer.add(events.createCharacters(out.get(slot.textIndex())));
            } else {
                StringBuilder formula = new StringBuilder();
                int k = 0;
                for (String part : slot.formulaParts()) {
                    if (part == null) formula.append(out.get(slot.valueIndexes()[k++]).replace("\"", "\"\""));
                    else formula.append(part);
                }
                writer.add(events.createCharacters(formula.toString()));
            }
        }
        items.clear();
        batch.clear();
    }

    /**
     * A sheet's events with every number or date cell below a serial header turned into a string cell, so the
     * rules can give it its {@code SN-} token: {@code <c r s><v>N</v></c>} becomes
     * {@code <c r s t="inlineStr"><is><t>N</t></is></c>}, and a formula cell {@code <c><f>..</f><v>N</v></c>} becomes
     * {@code <c t="str"><f>..</f><v>N</v></c>}. Every other attribute and child ({@code extLst}) is kept; a cell
     * without a value, a shared or inline string, a boolean and an error cell pass unchanged. One cell is buffered
     * at a time.
     */
    private static final class SerialCells implements XMLEventReader {
        private static final Set<String> CONVERTED_TYPES = Set.of("n", "d");
        private final XMLEventReader in;
        private final Set<Integer> columns;
        private final XMLEventFactory events = XMLEventFactory.newFactory();
        private final ArrayDeque<XMLEvent> queue = new ArrayDeque<>();
        private final CellCursor cursor = new CellCursor();
        private int rows;

        SerialCells(XMLEventReader in, Set<Integer> columns) {
            this.in = in;
            this.columns = columns;
        }

        @Override
        public boolean hasNext() {
            return !queue.isEmpty() || in.hasNext();
        }

        @Override
        public XMLEvent nextEvent() throws XMLStreamException {
            if (queue.isEmpty()) fill();
            return queue.poll();
        }

        @Override
        public Object next() {
            try {
                return nextEvent();
            } catch (XMLStreamException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public XMLEvent peek() throws XMLStreamException {
            if (queue.isEmpty() && in.hasNext()) fill();
            return queue.peek();
        }

        @Override
        public String getElementText() {
            throw new UnsupportedOperationException();
        }

        @Override
        public XMLEvent nextTag() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getProperty(String name) {
            return in.getProperty(name);
        }

        @Override
        public void close() throws XMLStreamException {
            in.close();
        }

        private void fill() throws XMLStreamException {
            XMLEvent e = in.nextEvent();
            if (!e.isStartElement()) {
                queue.add(e);
                return;
            }
            StartElement s = e.asStartElement();
            String local = s.getName().getLocalPart();
            if (local.equals("row")) {
                rows++;
                cursor.row();
            }
            if (!local.equals("c")) {
                queue.add(e);
                return;
            }
            int column = cursor.cell(attr(s, "r"));
            String type = attr(s, "t");
            if (rows <= 1 || !columns.contains(column) || !(type == null || CONVERTED_TYPES.contains(type))) {
                queue.add(e);
                return;
            }
            // Buffer the cell: its formula, its value and any other child, up to its end tag.
            List<XMLEvent> formula = new ArrayList<>();
            List<XMLEvent> other = new ArrayList<>();
            List<XMLEvent> all = new ArrayList<>();
            all.add(e);
            StringBuilder value = null;
            String child = null;
            int depth = 0;
            XMLEvent end = null;
            while (in.hasNext()) {
                XMLEvent ev = in.nextEvent();
                all.add(ev);
                if (ev.isStartElement()) {
                    depth++;
                    if (depth == 1) child = ev.asStartElement().getName().getLocalPart();
                    if (depth == 1 && child.equals("v")) {
                        value = new StringBuilder();
                        continue;
                    }
                } else if (ev.isEndElement()) {
                    if (depth == 0) {
                        end = ev;
                        break;
                    }
                    depth--;
                    if (depth == 0 && "v".equals(child)) {
                        child = null;
                        continue;
                    }
                } else if (ev.isCharacters() && depth == 1 && "v".equals(child)) {
                    value.append(ev.asCharacters().getData());
                    continue;
                } else if (depth == 0) {
                    continue; // whitespace between children
                }
                if ("f".equals(child)) formula.add(ev);
                else if (child != null && !child.equals("v")) other.add(ev);
                if (depth == 0 && ev.isEndElement()) child = null;
            }
            if (end == null || value == null || value.toString().isBlank()) {
                queue.addAll(all); // nothing to mask (or a truncated part: the writer reports it)
                return;
            }
            String ns = s.getName().getNamespaceURI();
            String prefix = s.getName().getPrefix();
            List<Attribute> attributes = new ArrayList<>();
            for (Iterator<Attribute> it = s.getAttributes(); it.hasNext(); ) {
                Attribute a = it.next();
                if (!(a.getName().getLocalPart().equals("t") && a.getName().getNamespaceURI().isEmpty())) attributes.add(a);
            }
            boolean hasFormula = !formula.isEmpty();
            attributes.add(events.createAttribute("t", hasFormula ? "str" : "inlineStr"));
            queue.add(events.createStartElement(s.getName(), attributes.iterator(), s.getNamespaces()));
            if (hasFormula) {
                queue.addAll(formula);
                queue.add(events.createStartElement(new QName(ns, "v", prefix), Collections.emptyIterator(), Collections.emptyIterator()));
                queue.add(events.createCharacters(value.toString()));
                queue.add(events.createEndElement(new QName(ns, "v", prefix), Collections.emptyIterator()));
            } else {
                queue.add(events.createStartElement(new QName(ns, "is", prefix), Collections.emptyIterator(), Collections.emptyIterator()));
                queue.add(events.createStartElement(new QName(ns, "t", prefix), Collections.emptyIterator(), Collections.emptyIterator()));
                queue.add(events.createCharacters(value.toString()));
                queue.add(events.createEndElement(new QName(ns, "t", prefix), Collections.emptyIterator()));
                queue.add(events.createEndElement(new QName(ns, "is", prefix), Collections.emptyIterator()));
            }
            queue.addAll(other);
            queue.add(end);
        }
    }

    private static String attr(StartElement element, String name) {
        Attribute a = element.getAttributeByName(new QName(name));
        return a == null ? null : a.getValue();
    }

    static XMLInputFactory inputs() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }

    private static final class BoundedArchiveStream extends java.io.FilterInputStream {
        private final long[] total;

        BoundedArchiveStream(InputStream in, long[] total) {
            super(in);
            this.total = total;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && ++total[0] > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw new UnsupportedInputException("XLSX_UNCOMPRESSED_TOO_LARGE",
                        "workbook uncompressed size exceeds limit");
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                total[0] += n;
                if (total[0] > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw new UnsupportedInputException("XLSX_UNCOMPRESSED_TOO_LARGE",
                            "workbook uncompressed size exceeds limit");
                }
            }
            return n;
        }
    }

    /** The zip stream stays open across parts: closing an XML writer must not close it. */
    private static final class NonClosingStream extends java.io.FilterOutputStream {
        NonClosingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }

    static String mainNamespace() {
        return MAIN;
    }
}
