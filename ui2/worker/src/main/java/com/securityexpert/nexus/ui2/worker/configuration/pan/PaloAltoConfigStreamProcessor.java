package com.securityexpert.nexus.ui2.worker.configuration.pan;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationOverride;

/**
 * Turns one {@code effective-running} (or {@code merged}) response into the
 * 14G CG-6/CG-7a/CG-7b outputs -- the category index and the {@code
 * src=local} override list -- using a StAX pull parser directly over the
 * transport's {@link InputStream} (14G CG-5: never a full-document {@code
 * String}; AC-2's own bounded-memory requirement). Memory use is bounded by
 * the number of distinct (context, category, source) tuples the document
 * contains, never by the document's byte size: the parser holds only a
 * shallow element-name path stack plus a counts map, and never buffers an
 * element's subtree.
 *
 * <p>Shape counted (14G CG-6, one level flattened -- see the class-level
 * note in {@code docs/design/PAN_CONFIGURATION_MEASUREMENT_FINDINGS_2026_09_14.md}
 * for the real vendor shape): every {@code vsys/entry[@name]} anywhere in
 * the document is one vsys context; each of its immediate child elements
 * (address, address-group, rulebase, zone, profiles, ...) is a category;
 * each category's own {@code entry} children are counted, tallied per
 * {@code src} attribute (CG-7a: {@code tpl}/{@code dg}/{@code shared}/
 * {@code local}; an entry with no {@code src} attribute is treated as
 * {@code shared}). {@code shared}, {@code deviceconfig}, {@code network}
 * and {@code mgt-config} are counted the same way as their own top-level
 * contexts. A category's own deeper sub-structure (e.g. {@code
 * rulebase/security/rules}) is flattened into that one category's count,
 * not modeled as its own nested category -- CG-6's index is a
 * count-per-category view, not a full tree.</p>
 */
public final class PaloAltoConfigStreamProcessor {

    private static final String SRC_LOCAL = "local";
    private static final String SRC_DEFAULT = "shared";
    private static final Set<String> TOP_LEVEL_CONTEXT_NAMES = Set.of("shared", "deviceconfig", "network", "mgt-config");

    private PaloAltoConfigStreamProcessor() {
    }

    public record Processed(List<ConfigurationIndexEntry> index, List<ConfigurationOverride> overrides,
            boolean hasLocalOverride) {
    }

    private record CountKey(String context, String category, String source) {
    }

    public static Processed process(InputStream xmlStream) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(xmlStream);

        Map<CountKey, Integer> counts = new LinkedHashMap<>();
        List<ConfigurationOverride> overrides = new ArrayList<>();
        List<String> path = new ArrayList<>();

        String currentVsys = null;
        int vsysEntryPathLen = -1;
        String currentTopLevelContext = null;
        int topLevelContextPathLen = -1;
        String currentCategory = null;
        int categoryPathLen = -1;

        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    path.add(name);

                    if (endsWith(path, "vsys", "entry")) {
                        currentVsys = attribute(reader, "name").orElse("vsys-unknown");
                        vsysEntryPathLen = path.size();
                        currentCategory = null;
                        categoryPathLen = -1;
                    } else if (TOP_LEVEL_CONTEXT_NAMES.contains(name) && currentCategory == null) {
                        currentTopLevelContext = name;
                        topLevelContextPathLen = path.size();
                        currentVsys = null;
                        vsysEntryPathLen = -1;
                    } else if (currentVsys != null && categoryPathLen < 0 && path.size() == vsysEntryPathLen + 1
                            && !"entry".equals(name)) {
                        currentCategory = name;
                        categoryPathLen = path.size();
                    } else if (currentTopLevelContext != null && categoryPathLen < 0
                            && path.size() == topLevelContextPathLen + 1 && !"entry".equals(name)) {
                        currentCategory = name;
                        categoryPathLen = path.size();
                    } else if ("entry".equals(name) && currentCategory != null && path.size() == categoryPathLen + 1) {
                        String context = currentVsys != null ? currentVsys : currentTopLevelContext;
                        String source = attribute(reader, "src").orElse(SRC_DEFAULT);
                        counts.merge(new CountKey(context, currentCategory, source), 1, Integer::sum);
                        if (SRC_LOCAL.equals(source)) {
                            String entryName = attribute(reader, "name").orElse("(unnamed)");
                            overrides.add(new ConfigurationOverride(context, currentCategory,
                                    currentCategory + "/" + entryName, Optional.empty()));
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    path.remove(path.size() - 1);
                    if (categoryPathLen >= 0 && path.size() < categoryPathLen) {
                        currentCategory = null;
                        categoryPathLen = -1;
                    }
                    if (vsysEntryPathLen >= 0 && path.size() < vsysEntryPathLen) {
                        currentVsys = null;
                        vsysEntryPathLen = -1;
                    }
                    if (topLevelContextPathLen >= 0 && path.size() < topLevelContextPathLen) {
                        currentTopLevelContext = null;
                        topLevelContextPathLen = -1;
                    }
                }
            }
        } finally {
            reader.close();
        }

        List<ConfigurationIndexEntry> index = counts.entrySet().stream()
                .map(e -> new ConfigurationIndexEntry(e.getKey().context(), e.getKey().category(),
                        Optional.of(e.getKey().source()), e.getValue()))
                .toList();

        return new Processed(index, List.copyOf(overrides), !overrides.isEmpty());
    }

    private static Optional<String> attribute(XMLStreamReader reader, String localName) {
        return Optional.ofNullable(reader.getAttributeValue(null, localName));
    }

    private static boolean endsWith(List<String> path, String... suffix) {
        if (path.size() < suffix.length) {
            return false;
        }
        int offset = path.size() - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (!path.get(offset + i).equals(suffix[i])) {
                return false;
            }
        }
        return true;
    }
}
