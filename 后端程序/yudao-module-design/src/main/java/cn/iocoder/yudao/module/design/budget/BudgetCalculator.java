package cn.iocoder.yudao.module.design.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_AMOUNT_LIMIT;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;

/** Pure internal calculation: callers must authorize selections and resolve effective regional prices first. */
public final class BudgetCalculator {

    public static final long MAX_UNIT_PRICE_CENTS = 100_000_000L;
    public static final long MAX_AMOUNT_CENTS = 10_000_000_000L; // Also below JavaScript's safe integer limit.
    private static final BigDecimal MAX_COUNT = new BigDecimal("100000");
    private static final Set<String> UNITS = Set.of("SQM", "METER", "PIECE", "SET", "HOUSEHOLD", "ITEM");
    private static final Set<String> SOURCES = Set.of("STANDARD", "CUSTOM_TEMPLATE", "PROJECT_CUSTOM");
    private static final List<String> BODY_ITEMS = List.of("FOUNDATION", "STRUCTURE", "ROOF");
    private static final List<String> EXTERIOR_ITEMS = List.of("DECORATION", "DOORS_WINDOWS", "WALL_PAINT",
            "CULTURE_STONE", "LIGHTING", "WATERPROOF_LIGHTNING", "INSURANCE");
    private static final Map<String, String> PROJECT_UNITS = Map.of(
            "DOOR_HOUSEHOLDS", "HOUSEHOLD", "WINDOW_AREA", "SQM", "WALL_PAINT_AREA", "SQM",
            "CULTURE_STONE_LENGTH", "METER", "LIGHTING_WASHER_LENGTH", "METER", "LIGHTING_STRIP_LENGTH", "METER",
            "LIGHTING_WALL_LAMP_COUNT", "PIECE", "WATERPROOF_AREA", "SQM", "INSULATED_WATERPROOF_AREA", "SQM");

    private BudgetCalculator() { }

    /** Decimal quantities and IDs stay strings. These internal records are NOT public App response DTOs. */
    public record LineInput(String lineId, String itemId, String itemCode, String category, String publicName,
                            String optionId, String optionLabel, String unit, String quantity, Long unitPriceCents,
                            String freeReason, String excludedReason, String source) { }

    public record Quantity(String value, String source) { }
    public record LineResult(LineInput input, String status, Long amountCents) { }
    public record ItemTotal(String itemId, String itemCode, String category, String publicName, String source,
                            String completeness, long pricedSubtotalCents, Long amountCents, List<LineResult> lines) { }
    public record Result(String completeness, List<LineResult> lines, List<ItemTotal> items,
                         Map<String, Long> categoryTotals, long pricedSubtotalCents, Long totalCents,
                         List<String> missingItemCodes, List<String> missingFields, List<String> warnings) { }

    /**
     * Quantity rules use only structured project values. A custom/admin quantity is supplied directly on LineInput.
     * FIXED_ONE must be explicitly selected as the option rule; it is never a fallback for a missing quantity.
     */
    public static Quantity resolveQuantity(BudgetInputs.Resolved inputs, String unit, String quantitySource, String quantityKey) {
        if (inputs == null || unit == null || !UNITS.contains(unit) || quantitySource == null) invalid();
        String value;
        String source;
        switch (quantitySource) {
            case "FOOTPRINT_AREA", "BUILDING_AREA", "ROOF_AREA" -> {
                requireAreaUnit(unit);
                requireNoKey(quantityKey);
                String field = switch (quantitySource) {
                    case "FOOTPRINT_AREA" -> "footprintArea";
                    case "BUILDING_AREA" -> "buildingArea";
                    default -> "roofArea";
                };
                Object saved = inputs.values().get(field);
                value = saved == null ? null : saved.toString();
                source = inputs.sources().get(field);
            }
            case "WINDOW_AREA" -> {
                requireAreaUnit(unit);
                requireNoKey(quantityKey);
                value = inputs.quantities().get("WINDOW_AREA");
                source = "PROJECT";
                if (value == null && inputs.values().get("buildingArea") != null) {
                    BigDecimal area = quantity(inputs.values().get("buildingArea").toString(), "SQM");
                    BigDecimal converted = area.multiply(new BigDecimal("0.25"))
                            .setScale(BudgetInputs.QUANTITY_SCALE, RoundingMode.HALF_UP);
                    // Tiny source areas can round to zero; zero is still not a valid engineering quantity.
                    value = converted.signum() == 0 ? null : plain(converted);
                    source = "DERIVED";
                }
            }
            case "PROJECT_QUANTITY" -> {
                if (quantityKey == null || !unit.equals(PROJECT_UNITS.get(quantityKey))) invalid();
                value = inputs.quantities().get(quantityKey);
                source = "PROJECT";
            }
            case "FIXED_ONE" -> {
                requireNoKey(quantityKey);
                if (!Set.of("ITEM", "SET").contains(unit)) invalid();
                value = "1";
                source = "FIXED_ONE";
            }
            default -> throw exception(BUDGET_INPUT_INVALID);
        }
        return value == null ? new Quantity(null, null) : new Quantity(plain(quantity(value, unit)), source);
    }

    public static Result calculate(BudgetInputs.Resolved inputs, List<LineInput> suppliedLines) {
        if (inputs == null || suppliedLines == null) throw exception(BUDGET_INPUT_INVALID);
        var lines = new ArrayList<LineResult>();
        var groups = new LinkedHashMap<String, List<LineResult>>();
        var lineIds = new HashSet<String>();
        var optionIds = new HashSet<String>();
        var standardIds = new LinkedHashMap<String, String>();
        long body = 0;
        long exterior = 0;
        boolean complete = !suppliedLines.isEmpty() && inputs.missingFields().isEmpty() && inputs.warnings().isEmpty();
        for (LineInput input : suppliedLines) {
            validateIdentity(input);
            if (!lineIds.add(input.lineId()) || (input.optionId() != null && !optionIds.add(input.optionId()))) invalid();
            if ("STANDARD".equals(input.source())) {
                String existing = standardIds.putIfAbsent(input.itemCode(), input.itemId());
                if (existing != null && !existing.equals(input.itemId())) invalid();
            }
            BigDecimal quantity = input.quantity() == null ? null : quantity(input.quantity(), input.unit());
            Long price = input.unitPriceCents();
            if (price != null && (price < 0 || price > MAX_UNIT_PRICE_CENTS)) invalid();
            optionalText(input.freeReason(), 500);
            optionalText(input.excludedReason(), 500);
            if (price != null && price == 0 && input.freeReason() == null) invalid();
            String status;
            Long amount = null;
            if (input.excludedReason() != null) {
                status = "EXCLUDED";
            } else if (quantity == null && price == null) {
                status = "MISSING_BOTH";
            } else if (quantity == null) {
                status = "MISSING_QUANTITY";
            } else if (price == null) {
                status = "MISSING_PRICE";
            } else {
                // Catalog parents are summaries/placeholders; only their actual option child rows are priced.
                if (!"PROJECT_CUSTOM".equals(input.source()) && input.optionId() == null) invalid();
                status = "PRICED";
                BigDecimal rounded = quantity.multiply(BigDecimal.valueOf(price)).setScale(0, RoundingMode.HALF_UP);
                if (rounded.compareTo(BigDecimal.valueOf(MAX_AMOUNT_CENTS)) > 0) throw exception(BUDGET_AMOUNT_LIMIT);
                amount = rounded.longValueExact();
                if ("BODY".equals(input.category())) body = add(body, amount);
                else exterior = add(exterior, amount);
            }
            if (!"PRICED".equals(status) && !"EXCLUDED".equals(status)) complete = false;
            var line = new LineResult(input, status, amount);
            lines.add(line);
            String groupKey = input.itemId() == null ? "line:" + input.lineId() : "item:" + input.itemId();
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(line);
        }
        var missingItems = new ArrayList<String>();
        for (String code : BODY_ITEMS) if (!standardIds.containsKey(code)) missingItems.add(code);
        for (String code : EXTERIOR_ITEMS) if (!standardIds.containsKey(code)) missingItems.add(code);
        if (!missingItems.isEmpty()) complete = false;
        List<ItemTotal> items = groups.values().stream().map(BudgetCalculator::summarize).toList();
        long subtotal = add(body, exterior);
        return new Result(complete ? "COMPLETE" : "INCOMPLETE", List.copyOf(lines), items,
                Map.of("BODY", body, "EXTERIOR", exterior), subtotal, complete ? subtotal : null,
                List.copyOf(missingItems), List.copyOf(inputs.missingFields()), List.copyOf(inputs.warnings()));
    }

    private static ItemTotal summarize(List<LineResult> children) {
        LineInput first = children.get(0).input();
        long subtotal = 0;
        boolean complete = true;
        boolean anyPriced = false;
        for (LineResult child : children) {
            LineInput current = child.input();
            if (!first.itemCode().equals(current.itemCode()) || !first.category().equals(current.category())
                    || !first.source().equals(current.source()) || !first.publicName().equals(current.publicName())) invalid();
            // A missing or explicitly excluded selection group may coexist with priced siblings.
            // Placeholders never carry quantity/price or add another charge to the parent.
            if (children.size() > 1 && current.optionId() == null
                    && (!List.of("MISSING_BOTH", "EXCLUDED").contains(child.status())
                    || current.quantity() != null || current.unitPriceCents() != null)) invalid();
            if (child.amountCents() != null) {
                subtotal = add(subtotal, child.amountCents());
                anyPriced = true;
            }
            if (!"PRICED".equals(child.status()) && !"EXCLUDED".equals(child.status())) complete = false;
        }
        return new ItemTotal(first.itemId(), first.itemCode(), first.category(), first.publicName(), first.source(),
                complete ? "COMPLETE" : "INCOMPLETE", subtotal, complete && anyPriced ? subtotal : null, List.copyOf(children));
    }

    private static void validateIdentity(LineInput line) {
        if (line == null) invalid();
        requiredText(line.lineId(), 64);
        if (line.itemCode() == null || !line.itemCode().matches("[A-Z0-9_]{1,64}")) invalid();
        requiredText(line.publicName(), 100);
        optionalText(line.optionLabel(), 100);
        if (!("BODY".equals(line.category()) || "EXTERIOR".equals(line.category()))
                || line.unit() == null || !UNITS.contains(line.unit()) || line.source() == null || !SOURCES.contains(line.source())) invalid();
        if ("PROJECT_CUSTOM".equals(line.source())) {
            if (line.itemId() != null || line.optionId() != null) invalid();
        } else {
            BudgetInputs.positiveId(line.itemId());
            if (line.optionId() != null) BudgetInputs.positiveId(line.optionId());
        }
        if ("STANDARD".equals(line.source())) {
            boolean body = BODY_ITEMS.contains(line.itemCode());
            if ((!body && !EXTERIOR_ITEMS.contains(line.itemCode())) || !line.category().equals(body ? "BODY" : "EXTERIOR")) invalid();
        }
    }

    private static BigDecimal quantity(String text, String unit) {
        if (text.length() > 20 || !text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?")) throw exception(BUDGET_INPUT_INVALID);
        BigDecimal quantity = new BigDecimal(text);
        boolean count = !"SQM".equals(unit) && !"METER".equals(unit);
        if (quantity.signum() <= 0 || quantity.compareTo(count ? MAX_COUNT : BudgetInputs.MAX_AREA) > 0
                || (count && quantity.stripTrailingZeros().scale() > 0)) invalid();
        return quantity;
    }

    private static long add(long first, long second) {
        if (second > MAX_AMOUNT_CENTS - first) throw exception(BUDGET_AMOUNT_LIMIT);
        return first + second;
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static void requireAreaUnit(String unit) { if (!"SQM".equals(unit)) invalid(); }
    private static void requireNoKey(String key) { if (key != null) invalid(); }
    private static void requiredText(String text, int max) { if (text == null) invalid(); optionalText(text, max); }
    private static void optionalText(String text, int max) { if (text != null && (text.isBlank() || text.length() > max)) invalid(); }
    private static void invalid() { throw exception(BUDGET_INPUT_INVALID); }
}
