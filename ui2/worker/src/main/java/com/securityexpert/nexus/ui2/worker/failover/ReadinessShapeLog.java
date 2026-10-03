package com.securityexpert.nexus.ui2.worker.failover;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

/** Per-run diagnostics retain only bounded shapes, never raw responses. */
final class ReadinessShapeLog {
    private static final System.Logger LOG=System.getLogger(ReadinessShapeLog.class.getName());
    private static final int MAX_BYTES=2048;
    private static final Pattern TOKENS=Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(?:Cluster Mode|High Availability|Active|ACTIVE|Standby|STANDBY|Down|DOWN|UP|Non-Monitored|\\((?:S|HA|LS|LM|P)(?:,[ \t]*(?:S|HA|LS|LM|P))*\\)|READY|\\(local\\)|ID|State|Name)(?![\\p{L}\\p{N}_])");
    private final String vendor;
    private final Map<Integer,String> shapes=new HashMap<>();
    private final Set<Integer> logged=new HashSet<>();

    ReadinessShapeLog(String vendor) { this.vendor=vendor; }

    private boolean bannerLogged;

    void logBannerStripped() {
        if(bannerLogged) return;
        bannerLogged=true;
        LOG.log(System.Logger.Level.INFO,"[READINESS_SHAPE] vendor="+vendor+" bannerStripped=true");
    }

    void capture(int check,String output) {
        if (logged.contains(check)) return;
        String shape="palo_alto".equals(vendor)?xmlShape(output):textShape(output);
        shapes.merge(check,shape,(a,b) -> a.equals(b)?a:bounded(a+"\n"+b));
    }

    String logUnknown(int check,String status) {
        if (!"UNKNOWN".equals(status) || !logged.add(check)) return null;
        String message=bounded("[READINESS_SHAPE] vendor="+vendor+" check="+check
            +" shape="+shapes.getOrDefault(check,""));
        LOG.log(System.Logger.Level.INFO,message);
        shapes.remove(check);
        return message;
    }

    String logTables(String status,Set<String> a,Set<String> b) {
        if ((!"FAIL".equals(status) && !"UNKNOWN".equals(status)) || !logged.add(2)) return null;
        String message=bounded("[READINESS_SHAPE] vendor="+vendor+" check=2"
            +" a="+tableShape(a)+" b="+tableShape(b)+" shape="+shapes.getOrDefault(2,""));
        LOG.log(System.Logger.Level.INFO,message);
        shapes.remove(2);
        return message;
    }

    private static String tableShape(Set<String> rows) {
        // Only numeric table coordinates are approved here; addresses never enter the log.
        var members=new java.util.TreeSet<String>();
        var interfaces=new java.util.TreeSet<String>();
        for(String row:rows) {
            String[] columns=row.split("\\|",3);
            if(columns.length==3 && columns[0].matches("[0-9]+")) {
                members.add(columns[0]);
                if(columns[1].matches("[0-9]+")) interfaces.add(columns[1]);
            }
        }
        return "{entries="+rows.size()+",members="+members+",interfaces="+interfaces+"}";
    }

    static String textShape(String output) {
        if (output==null) return "";
        String firstLines=output.lines().limit(25).collect(Collectors.joining("\n"));
        StringBuilder shape=new StringBuilder();
        var tokens=TOKENS.matcher(firstLines);
        int end=0;
        while (tokens.find()) {
            mask(firstLines.substring(end,tokens.start()),shape);
            shape.append(tokens.group());
            end=tokens.end();
        }
        mask(firstLines.substring(end),shape);
        return bounded(shape.toString());
    }

    private static void mask(String value,StringBuilder shape) {
        value.codePoints().forEach(c -> shape.appendCodePoint(Character.isLetter(c)?'a':Character.isDigit(c)?'9':
            (Character.isISOControl(c) && c!='\n' && c!='\t') || Character.getType(c)==Character.FORMAT?'?':c));
    }

    static String xmlShape(String output) {
        if (output==null || output.isBlank()) return "";
        try {
            var factory=XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
            factory.setXMLResolver((publicId,systemId,base,namespace) -> {
                throw new javax.xml.stream.XMLStreamException("External XML resolution refused");
            });
            var reader=factory.createXMLStreamReader(new StringReader(output));
            StringBuilder shape=new StringBuilder();
            try {
                while (reader.hasNext()) {
                    int event=reader.next();
                    if (reader.getLocation().getLineNumber()>25) break;
                    if (event==XMLStreamConstants.DTD) return "<unparseable>";
                    if (event==XMLStreamConstants.START_ELEMENT || event==XMLStreamConstants.END_ELEMENT) {
                        shape.append(event==XMLStreamConstants.END_ELEMENT?"</":"<");
                        if (!reader.getPrefix().isEmpty()) shape.append(reader.getPrefix()).append(':');
                        shape.append(reader.getLocalName()).append('>');
                        if (shape.length()>=MAX_BYTES) break;
                    }
                }
            } finally { reader.close(); }
            return bounded(shape.toString());
        } catch (Exception invalid) { return "<unparseable>"; }
    }

    private static String bounded(String text) {
        String result=text.lines().limit(25).collect(Collectors.joining("\n"));
        if (result.length()>MAX_BYTES) result=result.substring(0,MAX_BYTES);
        while (result.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            result=result.substring(0,result.offsetByCodePoints(result.length(),-1));
        return result;
    }
}
