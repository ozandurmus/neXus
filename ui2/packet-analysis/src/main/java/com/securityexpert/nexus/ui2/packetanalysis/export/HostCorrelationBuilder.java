package com.securityexpert.nexus.ui2.packetanalysis.export;

import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.HostColumn;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.HostData;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.HostFile;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.ProgramRow;
import com.securityexpert.nexus.ui2.packetanalysis.export.ExpertReportData.Timeline;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.AlignedTelemetry;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.Alignment;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.ParsedTelemetry;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.Series;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryFormat;
import com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryParser;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Reads the host telemetry attached to a case and places it on the capture timeline's second grid. Every file is parsed
 * by content, aligned with the zone, clock offset and start the user confirmed (UTC, flagged as an assumption, when
 * nothing was confirmed), and reported even when it cannot be used: a file is never dropped silently. Host, interface,
 * process and program names pass through the privacy masker; message text is never read into the report at all.
 */
final class HostCorrelationBuilder {
    static final String B = "B";
    static final String C = "C";
    static final String D = "D";
    static final String E = "E";
    static final String F = "F";
    private static final int TOP_INTERFACES = 4;
    private static final int TOP_DEVICES = 6;
    private static final int TOP_PROGRAMS = 8;
    private static final long MAX_GRID_CELLS = 4_000_000L;
    static final String INT = "#,##0";
    static final String ONE = "#,##0.0";
    static final String TWO = "#,##0.00";
    static final String SIGNED = "+#,##0;-#,##0;0";

    private record Spec(String key, String section, String title, String format) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("vmstat.r", B, "vmstat run queue (r)", INT),
            new Spec("vmstat.b", B, "vmstat uykuda süreç (b)", INT),
            new Spec("vmstat.us", B, "vmstat user %", ONE),
            new Spec("vmstat.sy", B, "vmstat sys %", ONE),
            new Spec("vmstat.id", B, "vmstat idle %", ONE),
            new Spec("vmstat.wa", B, "vmstat iowait %", ONE),
            new Spec("vmstat.in", B, "vmstat interrupt/sn", INT),
            new Spec("vmstat.cs", B, "vmstat context switch/sn", INT),
            new Spec("sar.cpu.usr", B, "sar %usr", ONE),
            new Spec("sar.cpu.sys", B, "sar %sys", ONE),
            new Spec("sar.cpu.iowait", B, "sar %iowait", ONE),
            new Spec("sar.runq", B, "sar runq-sz", INT),
            new Spec("sar.plist", B, "sar plist-sz", INT),
            new Spec("sar.ldavg1", B, "sar ldavg-1", TWO),
            new Spec("sar.proc/s", B, "sar proc/s", INT),
            new Spec("sar.cswch/s", B, "sar cswch/s", INT),
            new Spec("sar.active/s", C, "sar active/s", INT),
            new Spec("sar.passive/s", C, "sar passive/s", INT),
            new Spec("sar.iseg/s", C, "sar iseg/s", INT),
            new Spec("sar.oseg/s", C, "sar oseg/s", INT),
            new Spec("sar.retrans/s", C, "sar retrans/s", INT),
            new Spec("sar.atmptf/s", C, "sar atmptf/s", INT),
            new Spec("sar.estres/s", C, "sar estres/s", INT),
            new Spec("sar.isegerr/s", C, "sar isegerr/s", INT),
            new Spec("sar.orsts/s", C, "sar orsts/s", INT),
            new Spec("softnet.dropped", D, "softnet Δ dropped", INT),
            new Spec("softnet.time_squeeze", D, "softnet Δ time_squeeze", INT),
            new Spec("pidstat.rows", E, "pidstat satır sayısı", INT),
            new Spec("pidstat.rows.delta", E, "pidstat Δ satır (önceki örneğe göre)", SIGNED),
            new Spec("syslog.count", F, "syslog kayıt/sn", ONE));

    private final ExpertReportData d;
    private final ExpertReportOptions options;
    private final UnaryOperator<String> name;
    private final HostData host = new HostData();
    private int gridColumnBudget = Integer.MAX_VALUE;
    private boolean truncated;

    private HostCorrelationBuilder(ExpertReportData d, ExpertReportOptions options, UnaryOperator<String> name) {
        this.d = d;
        this.options = options;
        this.name = name;
    }

    static HostData build(ExpertReportData d, ExpertReportOptions options, UnaryOperator<String> name) {
        HostCorrelationBuilder b = new HostCorrelationBuilder(d, options, name);
        b.run();
        return b.host;
    }

    private void run() {
        Timeline t = d.timeline;
        if (t != null) {
            gridColumnBudget = (int) Math.max(4, MAX_GRID_CELLS / Math.max(1, t.count));
        }
        Long reference = null;
        if (t != null) {
            reference = Math.round(t.origin * 1000.0);
        } else if (d.window != null && d.window.rangeStart() != null) {
            reference = Math.round(d.window.rangeStart() * 1000.0);
        }
        List<ExpertReportOptions.HostTelemetry> files = new ArrayList<>(options.telemetry());
        files.sort(Comparator.comparing(ExpertReportOptions.HostTelemetry::name).thenComparing(ExpertReportOptions.HostTelemetry::id));
        // Names first: the masker learns every name before any column title uses one.
        for (ExpertReportOptions.HostTelemetry f : files) {
            name.apply(f.name());
        }
        List<HostFile> described = new ArrayList<>();
        List<Object[]> placed = new ArrayList<>();
        int index = 0;
        for (ExpertReportOptions.HostTelemetry f : files) {
            String code = "H" + ++index;
            ParsedTelemetry parsed = null;
            String readFailure = null;
            try (InputStream in = f.opener().open()) {
                parsed = TelemetryParser.parse(in, new TelemetryParser.Context(reference));
            } catch (IOException | RuntimeException unreadable) {
                readFailure = "Dosya okunamadı; depolamadan açılamadı.";
            }
            Alignment alignment;
            List<String> notes = new ArrayList<>();
            try {
                alignment = Alignment.of(f.timeZone(), f.offsetSeconds(), f.anchorLocal());
            } catch (IllegalArgumentException invalid) {
                alignment = Alignment.assumedUtc();
                notes.add("Kayıtlı saat ayarı geçersizdi; UTC varsayıldı.");
            }
            if (parsed == null) {
                described.add(describe(code, f, null, null, alignment, "UNREADABLE", readFailure, notes, null));
                continue;
            }
            AlignedTelemetry aligned = AlignedTelemetry.align(parsed, alignment);
            String status = "OK";
            String statusNote = null;
            Double overlap = null;
            if (!parsed.recognised()) {
                status = "UNRECOGNIZED";
                statusNote = reasonText(parsed.reason());
            } else if (aligned.unusableReason() != null) {
                status = "NOT_ALIGNABLE";
                statusNote = reasonText(aligned.unusableReason());
            } else if (aligned.firstEpoch() == null) {
                status = "NOT_ALIGNABLE";
                statusNote = reasonText("NO_SAMPLES");
            } else if (t == null) {
                status = "NO_TIMELINE";
                statusNote = "Yakalama zaman çizelgesi bulunamadı; sunucu verisi ızgaraya yerleştirilemedi.";
            } else {
                double gridEnd = t.origin + t.count * t.width;
                overlap = Math.max(0.0, Math.min(aligned.lastEpoch() + 1, gridEnd) - Math.max(aligned.firstEpoch(), t.origin));
                if (overlap <= 0) {
                    status = "NO_OVERLAP";
                    statusNote = "Sunucu verisi ile yakalama aralığı örtüşmüyor; seçilen saat dilimi ve saat farkını gözden geçirin.";
                }
            }
            HostFile row = describe(code, f, parsed, aligned, alignment, status, statusNote, notes, overlap);
            described.add(row);
            if (row.placed()) {
                placed.add(new Object[] {row, aligned});
            }
        }
        host.files.addAll(described);
        if (t == null) {
            host.gridNote = "Yakalama zaman çizelgesi bulunamadığı için sunucu göstergeleri saniye ızgarasına yerleştirilemedi.";
        } else if (placed.isEmpty()) {
            host.gridNote = "Yakalama aralığına yerleştirilebilen sunucu dosyası yok; nedenleri yukarıdaki tabloda.";
        } else {
            for (Object[] pair : placed) {
                columns((HostFile) pair[0], (AlignedTelemetry) pair[1], t);
            }
            host.columns.sort(Comparator.comparing((HostColumn c) -> c.section));
            if (host.columns.isEmpty()) {
                host.gridNote = "Yerleştirilen dosyalarda yakalama aralığına düşen örnek yok.";
            }
        }
        limits();
    }

    // ------------------------------------------------------------------------------------------------ file table

    private HostFile describe(String code, ExpertReportOptions.HostTelemetry f, ParsedTelemetry p, AlignedTelemetry aligned,
            Alignment alignment, String status, String statusNote, List<String> notes, Double overlap) {
        String fileName = name.apply(f.name());
        boolean masking = options.masker().masking();
        String zoneText = alignment.zoneLabel() + (alignment.zoneConfirmed() ? " (kullanıcı teyit etti)"
                : " (varsayım: kullanıcı saat dilimini teyit etmedi)");
        TelemetryFormat format = p == null ? TelemetryFormat.UNKNOWN : p.format();
        String hostName = p == null || p.hostName() == null ? null : name.apply(p.hostName());
        long skipped = p == null ? 0 : p.linesSkipped();
        List<String> all = new ArrayList<>(notes);
        String skipText = null;
        if (p != null) {
            for (Map.Entry<String, Long> e : p.notes().entrySet()) {
                String text = noteText(e.getKey());
                if (text != null) {
                    all.add(text);
                }
            }
            if (aligned != null) {
                for (Map.Entry<String, Long> e : new TreeMap<>(aligned.resets()).entrySet()) {
                    all.add(resetText(e.getKey(), e.getValue()));
                }
            }
            if (p.linesSkipped() > 0) {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Long> e : p.skipReasons().entrySet()) {
                    parts.add(skipReasonText(e.getKey()) + ": " + TrFormat.integer(e.getValue()));
                }
                skipText = String.join("; ", parts);
            }
            if (p.partial()) {
                all.add("Yalnız dosyanın ilk " + TelemetryParser.SNIFF_LINES + " satırı incelendi.");
            }
            String conflict = zoneConflict(p, aligned, alignment);
            if (conflict != null) {
                all.add(conflict);
            }
        }
        String anchor = alignment.anchorLocal();
        return new HostFile(code, fileName, f.sizeBytes(), masking ? null : f.sha256(), format, hostName, zoneText,
                alignment.zoneConfirmed(), p == null ? null : p.fileZoneLabel(), alignment.offsetSeconds(), anchor,
                aligned == null ? null : aligned.firstEpoch(), aligned == null ? null : aligned.lastEpoch(), overlap,
                p == null ? 0 : p.linesTotal(), p == null ? 0 : p.linesUsed(), p == null ? 0 : p.linesIgnored(), skipped,
                skipText, status, statusNote, List.copyOf(all));
    }

    /** The file's own zone label against the zone used, when the label is an offset or UTC and the two disagree. */
    private static String zoneConflict(ParsedTelemetry p, AlignedTelemetry aligned, Alignment alignment) {
        String label = p.fileZoneLabel();
        if (label == null || aligned == null || aligned.firstEpoch() == null) {
            return null;
        }
        Integer own = labelOffset(label);
        if (own == null) {
            return null;
        }
        int used = alignment.zone().getRules().getOffset(Instant.ofEpochMilli(Math.round(aligned.firstEpoch() * 1000))).getTotalSeconds();
        if (own == used) {
            return null;
        }
        return "Dosyanın kendi saat etiketi '" + label + "' kullanılan saat dilimiyle (" + alignment.zoneLabel()
                + ") çelişiyor; işletim ekibinden teyit alın.";
    }

    private static Integer labelOffset(String label) {
        if (label.equals("UTC") || label.equals("GMT") || label.equals("Z")) {
            return 0;
        }
        if (label.matches("[+-]\\d{2}(:?\\d{2})?")) {
            String digits = label.substring(1).replace(":", "");
            int seconds = Integer.parseInt(digits.substring(0, 2)) * 3600
                    + (digits.length() == 4 ? Integer.parseInt(digits.substring(2)) * 60 : 0);
            return label.charAt(0) == '-' ? -seconds : seconds;
        }
        return null;
    }

    static String reasonText(String code) {
        return switch (code == null ? "" : code) {
            case "EMPTY" -> "Dosya boş.";
            case "NO_KNOWN_FORMAT" -> "Biçim tanınmadı: ilk " + TelemetryParser.SNIFF_LINES
                    + " satırda sar, vmstat, pidstat, qdisc/softnet_stat anlık görüntüsü ya da syslog yapısı bulunamadı.";
            case "NO_KNOWN_BLOCK" -> "sysstat çıktısı, ama raporun kullandığı bir blok (CPU, kuyruk, süreç, ağ, TCP) yok.";
            case "NO_SAMPLES" -> "Biçim tanındı, ama kullanılabilir örnek satırı çıkmadı.";
            case "NO_TIMESTAMPS" -> "Anlık görüntülerin üstünde okunabilir zaman damgası satırı yok.";
            case "NEEDS_ANCHOR_DATE" -> "Dosya tarih içermiyor: ilk örneğin yerel tarih-saatini (başlangıç) girin.";
            case "NEEDS_ANCHOR_TIME" -> "Satırlarda saat yok (vmstat -t kullanılmamış): ilk satırın yerel tarih-saatini (başlangıç) girin.";
            default -> "Dosya kullanılamadı.";
        };
    }

    static String noteText(String code) {
        return switch (code) {
            case "DATE_ASSUMED_MONTH_FIRST" -> "Başlıktaki tarih gün/ay olarak da okunabiliyordu; sysstat'ın varsayılanı olan ay/gün okundu.";
            case "FIRST_ROW_SKIPPED" -> "vmstat'ın ilk satırı (açılıştan beri ortalama) örnek sayılmadı.";
            case "INTERVAL_ASSUMED_1S" -> "Satırlarda saat yok: satırlar girilen başlangıçtan itibaren 1 sn aralıklı varsayıldı.";
            case "MIDNIGHT_ROLLOVER" -> "Gece yarısı geçişi görüldü; sonraki satırlar ertesi güne yazıldı.";
            case "YEAR_FROM_CAPTURE" -> "Satırlarda yıl yok: yıl, satırı yakalama tarihine en yakın tutan yıl olarak seçildi.";
            case "EXPLICIT_OFFSETS" -> "Bazı zaman damgaları kendi saat farkını taşıyor; onlar için seçilen saat dilimi kullanılmadı.";
            case "RESTART" -> "Dosyada sistem yeniden başlatma kaydı (RESTART) var.";
            case "SAMPLE_CAP" -> "Dosya çok büyüktü: " + TrFormat.integer(TelemetryParser.MAX_SAMPLES)
                    + " örnekten sonraki satırlar okunmadı.";
            case "INTERFACE_CAP" -> "64'ten fazla ağ arayüzü vardı; yalnız ilk 64'ü okundu.";
            case "BANNER_DATE_UNREADABLE" -> "Başlıktaki tarih okunamadı.";
            default -> null;
        };
    }

    private String resetText(String key, long count) {
        String shown = key;
        int bar = key.indexOf('|');
        if (bar >= 0) {
            shown = key.substring(0, bar + 1) + name.apply(key.substring(bar + 1));
        }
        return "Sayaç geriye gitti (" + shown + "): " + TrFormat.integer(count) + " kez; bu aralıklar artış sayılmadı.";
    }

    static String skipReasonText(String code) {
        return switch (code) {
            case "BAD_TIME" -> "okunamayan saat";
            case "BAD_NUMBER" -> "sayı olmayan değer";
            case "BAD_COLUMN_COUNT" -> "sütun sayısı uyumsuz";
            case "NO_HEADER" -> "başlıksız satır";
            case "NO_TIMESTAMP" -> "zaman damgası olmayan satır";
            case "LINE_TOO_LONG" -> "çok uzun satır";
            case "SAMPLE_CAP" -> "örnek sınırından sonraki satır";
            default -> code;
        };
    }

    // ------------------------------------------------------------------------------------------------ columns

    private void columns(HostFile file, AlignedTelemetry aligned, Timeline t) {
        ParsedTelemetry p = aligned.parsed();
        Map<String, Series> series = p.series();
        for (Spec spec : SPECS) {
            Series s = series.get(spec.key());
            if (s != null && !spec.key().startsWith("softnet.")) {
                add(spec.section(), spec.key(), file, s, aligned, t, file.code() + " " + spec.title(), spec.format());
            }
        }
        // network interfaces: the busiest few by packets
        Map<String, Double> busy = new TreeMap<>();
        for (String key : series.keySet()) {
            if (key.startsWith("sar.rxpck/s|") || key.startsWith("sar.txpck/s|")) {
                Double[] placed = aligned.place(series.get(key), t.origin, t.width, t.count);
                double sum = 0;
                for (Double v : placed) {
                    sum += v == null ? 0 : v;
                }
                busy.merge(key.substring(key.indexOf('|') + 1), sum, Double::sum);
            }
        }
        List<Map.Entry<String, Double>> interfaces = new ArrayList<>(busy.entrySet());
        interfaces.sort((a, b) -> b.getValue().compareTo(a.getValue()) != 0 ? b.getValue().compareTo(a.getValue())
                : a.getKey().compareTo(b.getKey()));
        for (Map.Entry<String, Double> e : interfaces.subList(0, Math.min(TOP_INTERFACES, interfaces.size()))) {
            for (String metric : List.of("rxpck/s", "txpck/s")) {
                Series s = series.get("sar." + metric + "|" + e.getKey());
                if (s != null) {
                    add(C, s.key(), file, s, aligned, t, file.code() + " sar " + metric + " " + name.apply(e.getKey()), INT);
                }
            }
        }
        // qdisc drop counters: the devices whose counters rose most
        Map<String, Double> rises = new TreeMap<>();
        for (String key : series.keySet()) {
            if (key.startsWith("qdisc.dropped|")) {
                Double[] placed = aligned.place(series.get(key), t.origin, t.width, t.count);
                double sum = 0;
                for (Double v : placed) {
                    sum += v == null ? 0 : v;
                }
                rises.put(key.substring(key.indexOf('|') + 1), sum);
            }
        }
        List<Map.Entry<String, Double>> devices = new ArrayList<>(rises.entrySet());
        devices.sort((a, b) -> b.getValue().compareTo(a.getValue()) != 0 ? b.getValue().compareTo(a.getValue())
                : a.getKey().compareTo(b.getKey()));
        List<Map.Entry<String, Double>> chosen = new ArrayList<>(devices.subList(0, Math.min(TOP_DEVICES, devices.size())));
        chosen.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Double> e : chosen) {
            Series s = series.get("qdisc.dropped|" + e.getKey());
            add(D, s.key(), file, s, aligned, t, file.code() + " qdisc Δ dropped " + name.apply(e.getKey()), INT);
        }
        for (Spec spec : SPECS) {
            Series s = series.get(spec.key());
            if (s != null && spec.key().startsWith("softnet.")) {
                add(spec.section(), spec.key(), file, s, aligned, t, file.code() + " " + spec.title(), spec.format());
            }
        }
        if (p.format() == TelemetryFormat.SYSLOG) {
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(p.programs().entrySet());
            ranked.sort((a, b) -> b.getValue().compareTo(a.getValue()) != 0 ? b.getValue().compareTo(a.getValue())
                    : a.getKey().compareTo(b.getKey()));
            for (Map.Entry<String, Long> e : ranked.subList(0, Math.min(TOP_PROGRAMS, ranked.size()))) {
                String shown = e.getKey().equals(TelemetryParser.NO_TAG_LABEL) || e.getKey().equals(TelemetryParser.OTHER_PROGRAMS_LABEL)
                        ? e.getKey() : name.apply(e.getKey());
                host.programs.add(new ProgramRow(file.code(), shown, e.getValue()));
            }
        }
    }

    private void add(String section, String key, HostFile file, Series s, AlignedTelemetry aligned, Timeline t, String title,
            String format) {
        Double[] values = aligned.place(s, t.origin, t.width, t.count);
        boolean any = false;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                any = true;
                if (s.key().equals("syslog.count")) {
                    values[i] = values[i] / t.width;
                }
            }
        }
        if (!any) {
            return;
        }
        if (host.columns.size() >= gridColumnBudget) {
            truncated = true;
            return;
        }
        host.columns.add(new HostColumn(section, key, file.code(), s.kind(), title, format, values));
    }

    // ------------------------------------------------------------------------------------------------ limits

    private void limits() {
        for (HostFile f : host.files) {
            if (f.format() != TelemetryFormat.UNKNOWN && !f.zoneConfirmed()) {
                host.limits.add(f.code() + " (" + f.name() + "): dosyada saat dilimi bilgisi yok ve kullanıcı saat dilimini teyit etmedi; "
                        + "saatler UTC varsayıldı. Varsayım yanlışsa sunucu olayları yakalama zaman çizelgesine göre kayar.");
            }
            if (f.offsetSeconds() != 0) {
                host.limits.add(f.code() + ": kullanıcı " + TrFormat.integer(f.offsetSeconds())
                        + " sn'lik ek saat farkı girdi; dosya saatlerine eklendi.");
            }
        }
        if (truncated) {
            host.limits.add("Çok sayıda sunucu göstergesi vardı; ızgara belleği sınırı nedeniyle bazıları sayfaya alınmadı.");
        }
        host.limits.add("Sunucu örnekleri yakalama zaman çizelgesinin " + TrFormat.number(d.timeline == null ? 1.0 : d.timeline.width, 0)
                + " sn'lik kovalarına yerleştirilir: gösterge değerleri kovadaki son örnektir, sayaçlar kovadaki artıştır, "
                + "satır sayıları toplamdır. Örnek zamanı, ölçüm aralığının sonundaki damgadır.");
        host.limits.add("Sunucu verisi kullanıcının yüklediği metin dosyalarından okunur; dosyadaki mesaj içeriği rapora alınmaz. "
                + "Sunucu ve yakalama saatlerinin birbirine göre sapması (NTP) bilinmemektedir.");
    }
}
