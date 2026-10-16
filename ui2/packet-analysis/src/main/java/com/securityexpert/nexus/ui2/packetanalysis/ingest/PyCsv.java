package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloatRepr;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * The reference {@code csv} module, excel dialect ({@code quotechar='"'}, {@code doublequote=True}, no escape char,
 * {@code QUOTE_MINIMAL}, {@code strict=False}, {@code lineterminator="\r\n"}), with a configurable delimiter.
 *
 * <p>{@link CsvReader} is the {@code _csv.c} reader state machine fed line by line from a text stream opened with
 * universal newlines ({@code \r\n} and lone {@code \r} read as {@code \n}), exactly as the reference reads tshark's
 * stdout. {@link #writeRow} is {@code csv.writer.writerow}, with the reference {@code str()} of each value.
 */
public final class PyCsv {

    /** {@code csv.field_size_limit(16 * 1024 * 1024)} as set by the reference ingest. */
    public static final int FIELD_LIMIT = 16 * 1024 * 1024;

    private PyCsv() {
    }

    /** {@code csv.writer(f).writerow(row)} with excel defaults and the given delimiter. */
    public static void writeRow(Writer out, Object[] row, char delimiter) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) {
                line.append(delimiter);
            }
            String field = str(row[i]);
            boolean quote = false;
            for (int k = 0; k < field.length(); k++) {
                char c = field.charAt(k);
                if (c == delimiter || c == '"' || c == '\r' || c == '\n') {
                    quote = true;
                    break;
                }
            }
            // A record that is a single empty field is written as "" so it reads back as one field.
            if (!quote && field.isEmpty() && row.length == 1) {
                quote = true;
            }
            if (quote) {
                line.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                line.append(field);
            }
        }
        line.append("\r\n");
        out.write(line.toString());
    }

    /** The text the reference csv writer stores for a value: None empty, bool True/False, float repr. */
    static String str(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Double d) {
            return PyFloatRepr.repr(d);
        }
        return v.toString();
    }

    /** {@code csv.reader(stream, delimiter=...)} over a stream decoded with universal newlines. */
    public static final class CsvReader {
        private static final int EOL = -2;

        private enum State {
            START_RECORD, START_FIELD, IN_FIELD, IN_QUOTED_FIELD, QUOTE_IN_QUOTED_FIELD, EAT_CRNL
        }

        private final Reader in;
        private final char delimiter;
        private final char[] buf = new char[1 << 16];
        private int bufLen;
        private int bufPos;
        private boolean eof;

        private State state;
        private List<String> fields;
        private final StringBuilder field = new StringBuilder();
        private final StringBuilder line = new StringBuilder();

        public CsvReader(Reader in, char delimiter) {
            this.in = in;
            this.delimiter = delimiter;
        }

        /** The next record, an empty list for an empty line, or {@code null} at end of input. */
        public List<String> next() {
            state = State.START_RECORD;
            fields = new ArrayList<>();
            field.setLength(0);
            do {
                if (!readLine()) {
                    if (field.length() != 0 || state == State.IN_QUOTED_FIELD) {
                        saveField();
                        break;
                    }
                    return null;
                }
                for (int i = 0; i < line.length(); i++) {
                    process(line.charAt(i));
                }
                process(EOL);
            } while (state != State.START_RECORD);
            List<String> result = fields;
            fields = null;
            return result;
        }

        private void saveField() {
            fields.add(field.toString());
            field.setLength(0);
        }

        private void addChar(int c) {
            if (field.length() >= FIELD_LIMIT) {
                throw new IllegalArgumentException("field larger than field limit (" + FIELD_LIMIT + ")");
            }
            field.append((char) c);
        }

        private void process(int c) {
            switch (state) {
                case START_RECORD:
                    if (c == EOL) {
                        return;
                    } else if (c == '\n' || c == '\r') {
                        state = State.EAT_CRNL;
                        return;
                    }
                    state = State.START_FIELD;
                    startField(c);
                    return;
                case START_FIELD:
                    startField(c);
                    return;
                case IN_FIELD:
                    if (c == '\n' || c == '\r' || c == EOL) {
                        saveField();
                        state = c == EOL ? State.START_RECORD : State.EAT_CRNL;
                    } else if (c == delimiter) {
                        saveField();
                        state = State.START_FIELD;
                    } else {
                        addChar(c);
                    }
                    return;
                case IN_QUOTED_FIELD:
                    if (c == EOL) {
                        return;
                    } else if (c == '"') {
                        state = State.QUOTE_IN_QUOTED_FIELD;
                    } else {
                        addChar(c);
                    }
                    return;
                case QUOTE_IN_QUOTED_FIELD:
                    if (c == '"') {
                        addChar(c);
                        state = State.IN_QUOTED_FIELD;
                    } else if (c == delimiter) {
                        saveField();
                        state = State.START_FIELD;
                    } else if (c == '\n' || c == '\r' || c == EOL) {
                        saveField();
                        state = c == EOL ? State.START_RECORD : State.EAT_CRNL;
                    } else {
                        addChar(c);
                        state = State.IN_FIELD;
                    }
                    return;
                case EAT_CRNL:
                    if (c == '\n' || c == '\r') {
                        return;
                    } else if (c == EOL) {
                        state = State.START_RECORD;
                        return;
                    }
                    throw new IllegalArgumentException("new-line character seen in unquoted field - do you need"
                            + " to open the file with newline=''?");
                default:
                    throw new IllegalStateException(state.name());
            }
        }

        private void startField(int c) {
            if (c == '\n' || c == '\r' || c == EOL) {
                saveField();
                state = c == EOL ? State.START_RECORD : State.EAT_CRNL;
            } else if (c == '"') {
                state = State.IN_QUOTED_FIELD;
            } else if (c == delimiter) {
                saveField();
            } else {
                addChar(c);
                state = State.IN_FIELD;
            }
        }

        /** Reads one universal-newline line (ending in \n unless at EOF) into {@link #line}. */
        private boolean readLine() {
            line.setLength(0);
            while (true) {
                int c = read();
                if (c < 0) {
                    return line.length() > 0;
                }
                line.append((char) c);
                if (c == '\n') {
                    return true;
                }
            }
        }

        /** One translated char: \r\n and a lone \r become \n. */
        private int read() {
            int c = raw();
            if (c == '\r') {
                int d = raw();
                if (d >= 0 && d != '\n') {
                    bufPos--;
                }
                return '\n';
            }
            return c;
        }

        private int raw() {
            if (bufPos >= bufLen) {
                if (eof) {
                    return -1;
                }
                // Keep the last char so a pushback after a refill stays valid.
                try {
                    char last = bufLen > 0 ? buf[bufLen - 1] : 0;
                    int n = in.read(buf, 1, buf.length - 1);
                    if (n < 0) {
                        eof = true;
                        bufPos = bufLen = 0;
                        return -1;
                    }
                    buf[0] = last;
                    bufLen = n + 1;
                    bufPos = 1;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return buf[bufPos++];
        }
    }
}
