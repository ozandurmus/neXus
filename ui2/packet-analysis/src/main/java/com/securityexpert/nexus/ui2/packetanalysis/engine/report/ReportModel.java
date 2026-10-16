package com.securityexpert.nexus.ui2.packetanalysis.engine.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds the engine's report tree to the typed {@link CoreSections.Report} (strict: every key must be a
 * component, in component order, with the declared int/float/string type; a missing key is legal only for
 * {@link Maybe} components) and serializes it through Jackson to the reference JSON text
 * ({@code json.dumps(report, indent=2)}, written by {@link PyJsonWriter}).
 */
public final class ReportModel {
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private ReportModel() {
    }

    public static CoreSections.Report bind(Map<String, Object> report) {
        return (CoreSections.Report) bindRecord(CoreSections.Report.class, report, "report");
    }

    public static JsonNode tree(CoreSections.Report report) {
        return MAPPER.valueToTree(report);
    }

    public static String toJson(Map<String, Object> report) {
        return PyJsonWriter.dumps(tree(bind(report)), true);
    }

    static String jsonName(String component) {
        return PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE.translate(component);
    }

    private static Object bindRecord(Class<?> type, Object value, String path) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + ": expected an object for " + type.getSimpleName());
        }
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        List<String> expectedOrder = new ArrayList<>();
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            types[i] = c.getType();
            String key = jsonName(c.getName());
            boolean optional = c.getType() == Maybe.class;
            if (map.containsKey(key)) {
                expectedOrder.add(key);
                Object v = convert(c.getGenericType(), map.get(key), path + "." + key);
                args[i] = v;
            } else if (optional) {
                args[i] = Maybe.absent();
            } else {
                throw new IllegalArgumentException(path + ": missing key " + key);
            }
        }
        List<String> actual = new ArrayList<>();
        for (Object k : map.keySet()) {
            actual.add(String.valueOf(k));
        }
        if (!actual.equals(expectedOrder)) {
            throw new IllegalArgumentException(path + ": keys " + actual + " do not match " + type.getSimpleName() + " " + expectedOrder);
        }
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(types);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object convert(Type type, Object value, String path) {
        if (type instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if (raw == Maybe.class) {
                return Maybe.of(convert(args[0], value, path));
            }
            if (value == null) {
                return null;
            }
            if (raw == List.class) {
                if (!(value instanceof List<?> list)) {
                    throw new IllegalArgumentException(path + ": expected a list");
                }
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    out.add(convert(args[0], list.get(i), path + "[" + i + "]"));
                }
                return out;
            }
            if (raw == Map.class) {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException(path + ": expected an object");
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.put(String.valueOf(e.getKey()), convert(args[1], e.getValue(), path + "." + e.getKey()));
                }
                return out;
            }
            throw new IllegalArgumentException(path + ": unsupported type " + type);
        }
        Class<?> cls = (Class<?>) type;
        if (value == null || cls == Object.class) {
            return value;
        }
        if (cls.isRecord()) {
            return bindRecord(cls, value, path);
        }
        if (cls.isSealed()) {
            // A union of record shapes (e.g. the findings of different detectors): the one whose keys bind.
            List<String> errors = new ArrayList<>();
            for (Class<?> option : cls.getPermittedSubclasses()) {
                try {
                    return bindRecord(option, value, path);
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
            throw new IllegalArgumentException(path + ": no " + cls.getSimpleName() + " shape binds: " + errors);
        }
        if (cls == Number.class) {
            if (value instanceof Long || value instanceof Double) {
                return value;
            }
        } else if (cls.isInstance(value)) {
            return value;
        }
        throw new IllegalArgumentException(path + ": " + value.getClass().getSimpleName() + " is not " + cls.getSimpleName());
    }
}
