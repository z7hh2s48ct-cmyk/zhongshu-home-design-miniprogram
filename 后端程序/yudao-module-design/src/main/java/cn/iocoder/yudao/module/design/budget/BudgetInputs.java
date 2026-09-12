package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;

/** T10-03: only input normalization/derivation; not a pricing engine or another persistence service. */
public final class BudgetInputs {

    public static final BigDecimal MAX_AREA = new BigDecimal("1000000");
    public static final BigDecimal MAX_LENGTH = new BigDecimal("1000");
    public static final int MAX_FLOORS = 20;
    public static final int QUANTITY_SCALE = 4;
    private static final Set<String> AREAS = Set.of("footprintArea", "buildingArea", "roofArea");
    private static final Set<String> LENGTHS = Set.of("faceWidth", "depth");
    private static final Set<String> FIELDS = Set.of("regionCode", "footprintArea", "buildingArea", "roofArea",
            "faceWidth", "depth", "floorCount", "quantities");
    private static final Set<String> OVERRIDES = Set.of("regionCode", "footprintArea", "roofArea", "floorCount");
    private static final List<String> PUBLIC_FIELDS = List.of("regionCode", "footprintArea", "floorCount", "buildingArea", "roofArea");
    private static final Set<String> QUANTITIES = Set.of("DOOR_HOUSEHOLDS", "WINDOW_AREA", "WALL_PAINT_AREA",
            "CULTURE_STONE_LENGTH", "LIGHTING_WASHER_LENGTH", "LIGHTING_STRIP_LENGTH", "LIGHTING_WALL_LAMP_COUNT",
            "WATERPROOF_AREA", "INSULATED_WATERPROOF_AREA");
    private static final Set<String> COUNTS = Set.of("DOOR_HOUSEHOLDS", "LIGHTING_WALL_LAMP_COUNT");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private BudgetInputs() { }

    public record Resolved(Map<String, Object> values, Map<String, String> sources,
                           Map<String, String> quantities, List<String> missingFields, List<String> warnings) {
        public Map<String, Object> publicValues() { return publicFields(values); }
        public Map<String, String> publicSources() { return publicFields(sources); }
    }

    /** Validate the new nested contract without changing or reinterpreting unrelated legacy design inputs. */
    public static Map<String, Object> validateRequirementInputs(Map<String, Object> inputs) {
        if (inputs == null || !inputs.containsKey("budgetInputs")) return inputs;
        var copy = new LinkedHashMap<>(inputs);
        copy.put("budgetInputs", normalize(asMap(inputs.get("budgetInputs")), true));
        return copy;
    }

    public static Map<String, Object> readJson(String json) {
        Map<String, Object> parsed = json == null || json.isBlank() ? null : JsonUtils.parseObject(json, MAP_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    /** Read only explicit fields. Never extract quantities/area from free text, images or UI demo values. */
    public static Map<String, Object> fromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return Map.of();
        var fields = new LinkedHashMap<String, Object>();
        for (String field : FIELDS) {
            if (snapshot.containsKey(field)) fields.put(field, snapshot.get(field));
        }
        if (!fields.containsKey("floorCount") && "两层".equals(snapshot.get("floor"))) {
            fields.put("floorCount", 2); // The existing app's stored enum, not a guessed default.
        }
        if (snapshot.get("budgetInputs") instanceof Map<?, ?> nested) {
            for (String field : FIELDS) {
                if (nested.containsKey(field)) fields.put(field, nested.get(field));
            }
        }
        return normalize(fields, false);
    }

    /** Empty overrides restore imported values. Inputs are copied, never mutated or saved back to the project. */
    public static Resolved resolve(Map<String, Object> imported, Map<String, Object> overrides) {
        Map<String, Object> supplied = overrides == null ? Map.of() : overrides;
        if (!OVERRIDES.containsAll(supplied.keySet())) throw exception(BUDGET_INPUT_INVALID);
        var raw = new LinkedHashMap<>(normalize(imported, true));
        raw.putAll(normalize(supplied, true));
        var values = new LinkedHashMap<String, Object>();
        var sources = new LinkedHashMap<String, String>();
        for (String field : FIELDS) {
            if (!"quantities".equals(field) && raw.get(field) != null) {
                values.put(field, raw.get(field));
                sources.put(field, supplied.containsKey(field) ? "USER_OVERRIDE" : "PROJECT");
            }
        }
        var warnings = new ArrayList<String>();
        BigDecimal footprint = value(values, "footprintArea");
        if (footprint == null && value(values, "faceWidth") != null && value(values, "depth") != null
                && !supplied.containsKey("footprintArea")) {
            footprint = derivedArea(value(values, "faceWidth").multiply(value(values, "depth")), "footprintArea", warnings);
            if (footprint != null) {
                values.put("footprintArea", plain(footprint));
                sources.put("footprintArea", "DERIVED");
            }
        }
        BigDecimal expectedArea = footprint != null && values.get("floorCount") != null
                ? derivedArea(footprint.multiply(new BigDecimal(values.get("floorCount").toString())), "buildingArea", warnings) : null;
        BigDecimal actualArea = value(values, "buildingArea");
        if (actualArea == null && expectedArea != null) {
            values.put("buildingArea", plain(expectedArea));
            sources.put("buildingArea", "DERIVED");
        } else if (actualArea != null && expectedArea != null && actualArea.compareTo(expectedArea) != 0) {
            warnings.add("BUILDING_AREA_REVIEW_REQUIRED"); // Keep actual area; do not silently overwrite it.
        }
        var quantities = new LinkedHashMap<String, String>();
        if (raw.get("quantities") instanceof Map<?, ?> map) {
            map.forEach((key, quantity) -> { if (quantity != null) quantities.put(key.toString(), quantity.toString()); });
        }
        return new Resolved(Collections.unmodifiableMap(values), Collections.unmodifiableMap(sources),
                Collections.unmodifiableMap(quantities), PUBLIC_FIELDS.stream().filter(f -> !values.containsKey(f)).toList(),
                List.copyOf(warnings));
    }

    /** Existing result-version API also returns config JSON: remove internal budget quantities there too. */
    public static String publicConfigJson(String json) {
        if (json == null) return null;
        var config = new LinkedHashMap<>(readJson(json));
        config.remove("quantities");
        if (config.containsKey("budgetInputs")) {
            config.put("budgetInputs", publicFields(fromSnapshot(Collections.singletonMap("budgetInputs", config.get("budgetInputs")))));
        }
        return JsonUtils.toJsonString(config);
    }

    public static long positiveId(String id) {
        try {
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw exception(BUDGET_INPUT_INVALID);
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            throw exception(BUDGET_INPUT_INVALID);
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> inputs, boolean strict) {
        var result = new LinkedHashMap<String, Object>();
        if (inputs == null) return result;
        if (strict && !FIELDS.containsAll(inputs.keySet())) throw exception(BUDGET_INPUT_INVALID);
        for (String field : FIELDS) {
            if (!inputs.containsKey(field)) continue;
            Object input = inputs.get(field);
            try {
                if (input == null) { result.put(field, null); continue; }
                if (AREAS.contains(field) || LENGTHS.contains(field)) {
                    if (strict && !(input instanceof String)) throw exception(BUDGET_INPUT_INVALID);
                    result.put(field, plain(decimal(input, LENGTHS.contains(field) ? MAX_LENGTH : MAX_AREA, false)));
                } else if ("floorCount".equals(field)) {
                    result.put(field, decimal(input, BigDecimal.valueOf(MAX_FLOORS), true).intValueExact());
                } else if ("regionCode".equals(field)) {
                    if (!(input instanceof String text) || !text.matches("[A-Za-z0-9_-]{1,32}")) throw exception(BUDGET_INPUT_INVALID);
                    result.put(field, input);
                } else {
                    Map<String, Object> quantities = asMap(input);
                    if (!QUANTITIES.containsAll(quantities.keySet())) throw exception(BUDGET_INPUT_INVALID);
                    var canonical = new LinkedHashMap<String, String>();
                    for (var entry : quantities.entrySet()) {
                        boolean count = COUNTS.contains(entry.getKey());
                        if (strict && entry.getValue() != null && !(entry.getValue() instanceof String)) throw exception(BUDGET_INPUT_INVALID);
                        canonical.put(entry.getKey(), entry.getValue() == null ? null : plain(decimal(entry.getValue(),
                                count ? new BigDecimal("100000") : MAX_AREA, count)));
                    }
                    result.put(field, Collections.unmodifiableMap(canonical));
                }
            } catch (ServiceException ex) {
                if (strict) throw ex;
                result.put(field, null); // Invalid saved data is missing, never zero or a guessed fallback.
            }
        }
        return result;
    }

    private static BigDecimal decimal(Object input, BigDecimal max, boolean integer) {
        if (!(input instanceof String || input instanceof Number)) throw exception(BUDGET_INPUT_INVALID);
        String text = input.toString();
        if (text.length() > 20 || !text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?")) throw exception(BUDGET_INPUT_INVALID);
        BigDecimal value = new BigDecimal(text);
        if (value.signum() <= 0 || value.compareTo(max) > 0 || (integer && value.stripTrailingZeros().scale() > 0)) {
            throw exception(BUDGET_INPUT_INVALID);
        }
        return value;
    }

    private static BigDecimal derivedArea(BigDecimal value, String field, List<String> warnings) {
        BigDecimal rounded = value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0 || rounded.compareTo(MAX_AREA) > 0) {
            warnings.add("DERIVED_" + field + "_OUT_OF_RANGE");
            return null;
        }
        return rounded;
    }

    private static BigDecimal value(Map<String, Object> values, String field) {
        return values.get(field) == null ? null : new BigDecimal(values.get(field).toString());
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    private static Map<String, Object> asMap(Object input) {
        if (!(input instanceof Map<?, ?> map)) throw exception(BUDGET_INPUT_INVALID);
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> { if (!(key instanceof String)) throw exception(BUDGET_INPUT_INVALID); copy.put((String) key, value); });
        return copy;
    }

    private static <T> Map<String, T> publicFields(Map<String, T> values) {
        var result = new LinkedHashMap<String, T>();
        for (String field : PUBLIC_FIELDS) if (values.containsKey(field)) result.put(field, values.get(field));
        return Collections.unmodifiableMap(result);
    }
}
