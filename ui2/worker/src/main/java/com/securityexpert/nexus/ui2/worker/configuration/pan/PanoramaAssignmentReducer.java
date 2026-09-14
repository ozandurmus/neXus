package com.securityexpert.nexus.ui2.worker.configuration.pan;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 14G CG-7's own reducer: Panorama's {@code xpath=/config} response (84.8
 * MB measured) reduced, on stream, to an assignment index only -- device
 * serial -> template stack, the stack's templates, device groups -- plus
 * (CG-7c) the set of category/element paths each template and device group
 * itself defines, so a device's own {@code src=local} override list can be
 * cross-checked against what its assigned templates/device-groups declare.
 * The full document is never stored (CG-7: "document not stored") and
 * never materialized as one {@code String} -- this reducer holds only the
 * assignment map and the per-template/device-group defined-path sets,
 * bounded by object counts, not document size.
 *
 * <p>No live Panorama target exists yet (14F DR-4); this reducer is
 * fixture-driven only at this movement (WORKER.md scope).</p>
 */
public final class PanoramaAssignmentReducer {

    private PanoramaAssignmentReducer() {
    }

    public record Assignment(String deviceSerial, Optional<String> templateStack, List<String> templates,
            List<String> deviceGroups) {
    }

    /** {@code definedPaths} keys are {@code "template:<name>"} or {@code "device-group:<name>"}; values are {@code "category/entryName"} paths. */
    public record Reduced(Map<String, Assignment> assignmentsBySerial, Map<String, Set<String>> definedPaths) {
    }

    public static Reduced reduce(InputStream xmlStream) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(xmlStream);

        Map<String, MutableAssignment> assignments = new LinkedHashMap<>();
        Map<String, Set<String>> definedPaths = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();

        String currentTemplateName = null;
        String currentStackName = null;
        String currentDeviceGroupName = null;
        List<String> currentStackTemplates = new ArrayList<>();
        String definedPathsKey = null;
        int definedPathsContainerLen = -1;
        String currentCategory = null;
        int categoryPathLen = -1;
        StringBuilder textBuffer = null;

        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    path.add(name);

                    if (endsWith(path, "template", "entry")) {
                        currentTemplateName = attribute(reader, "name").orElse("template-unknown");
                        definedPathsKey = "template:" + currentTemplateName;
                        definedPathsContainerLen = path.size();
                        definedPaths.putIfAbsent(definedPathsKey, new LinkedHashSet<>());
                    } else if (endsWith(path, "template-stack", "entry")) {
                        currentStackName = attribute(reader, "name").orElse("stack-unknown");
                        currentStackTemplates = new ArrayList<>();
                    } else if (endsWith(path, "device-group", "entry")) {
                        currentDeviceGroupName = attribute(reader, "name").orElse("device-group-unknown");
                        definedPathsKey = "device-group:" + currentDeviceGroupName;
                        definedPathsContainerLen = path.size();
                        definedPaths.putIfAbsent(definedPathsKey, new LinkedHashSet<>());
                    } else if (endsWith(path, "templates", "member")) {
                        textBuffer = new StringBuilder();
                    } else if (currentStackName != null && endsWith(path, "template-stack", "entry", "devices", "entry")) {
                        String serial = attribute(reader, "name").orElse(null);
                        if (serial != null) {
                            MutableAssignment assignment = assignments.computeIfAbsent(serial, MutableAssignment::new);
                            assignment.setTemplateStack(currentStackName);
                            assignment.templates = List.copyOf(currentStackTemplates);
                        }
                    } else if (currentDeviceGroupName != null
                            && endsWith(path, "device-group", "entry", "devices", "entry")) {
                        String serial = attribute(reader, "name").orElse(null);
                        if (serial != null) {
                            assignments.computeIfAbsent(serial, MutableAssignment::new).deviceGroups
                                    .add(currentDeviceGroupName);
                        }
                    } else if (definedPathsKey != null && categoryPathLen < 0 && path.size() == definedPathsContainerLen + 1
                            && !"devices".equals(name) && !"entry".equals(name)) {
                        currentCategory = name;
                        categoryPathLen = path.size();
                    } else if ("entry".equals(name) && currentCategory != null && path.size() == categoryPathLen + 1) {
                        String entryName = attribute(reader, "name").orElse("(unnamed)");
                        definedPaths.get(definedPathsKey).add(currentCategory + "/" + entryName);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && textBuffer != null) {
                    textBuffer.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = path.remove(path.size() - 1);
                    if ("member".equals(name) && textBuffer != null) {
                        currentStackTemplates.add(textBuffer.toString().strip());
                        textBuffer = null;
                    }
                    if (categoryPathLen >= 0 && path.size() < categoryPathLen) {
                        currentCategory = null;
                        categoryPathLen = -1;
                    }
                    if (definedPathsContainerLen >= 0 && path.size() < definedPathsContainerLen) {
                        definedPathsKey = null;
                        definedPathsContainerLen = -1;
                    }
                    if ("entry".equals(name) && endsWithAfterPop(path, "template")) {
                        currentTemplateName = null;
                    }
                    if ("entry".equals(name) && endsWithAfterPop(path, "template-stack")) {
                        currentStackName = null;
                        currentStackTemplates = new ArrayList<>();
                    }
                    if ("entry".equals(name) && endsWithAfterPop(path, "device-group")) {
                        currentDeviceGroupName = null;
                    }
                }
            }
        } finally {
            reader.close();
        }

        Map<String, Assignment> result = new LinkedHashMap<>();
        assignments.forEach((serial, mutable) -> result.put(serial, mutable.toAssignment()));
        return new Reduced(Map.copyOf(result), Map.copyOf(definedPaths));
    }

    private static boolean endsWithAfterPop(List<String> path, String expectedParent) {
        return !path.isEmpty() && path.get(path.size() - 1).equals(expectedParent);
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

    private static final class MutableAssignment {
        final String deviceSerial;
        Optional<String> templateStack = Optional.empty();
        List<String> templates = List.of();
        List<String> deviceGroups = new ArrayList<>();

        MutableAssignment(String deviceSerial) {
            this.deviceSerial = deviceSerial;
        }

        void setTemplateStack(String stack) {
            this.templateStack = Optional.ofNullable(stack);
        }

        Assignment toAssignment() {
            return new Assignment(deviceSerial, templateStack, templates, List.copyOf(deviceGroups));
        }
    }
}
