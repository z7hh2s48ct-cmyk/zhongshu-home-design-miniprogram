package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.budget.BudgetCalculator;
import cn.iocoder.yudao.module.design.budget.BudgetCalculator.LineInput;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetCalculatorT10ContractTest {

    @Test
    void goldenThreeBodySevenExteriorItemsPriceEachCombinationChildExactlyOnce() {
        var result = BudgetCalculator.calculate(inputs(), golden(inputs()));
        assertThat(result.completeness()).isEqualTo("COMPLETE");
        assertThat(result.totalCents()).isEqualTo(48_202_000L);
        assertThat(result.pricedSubtotalCents()).isEqualTo(48_202_000L);
        assertThat(result.categoryTotals()).containsEntry("BODY", 35_584_000L).containsEntry("EXTERIOR", 12_618_000L);
        assertThat(result.items()).hasSize(10);
        assertThat(result.lines()).hasSize(13);
        assertThat(result.missingItemCodes()).isEmpty();
        Map<String, Long> expected = Map.of("FOUNDATION", 6_240_000L, "STRUCTURE", 23_520_000L,
                "ROOF", 5_824_000L, "DECORATION", 2_880_000L, "DOORS_WINDOWS", 4_740_000L,
                "WALL_PAINT", 2_880_000L, "CULTURE_STONE", 528_000L, "LIGHTING", 420_000L,
                "WATERPROOF_LIGHTNING", 670_000L, "INSURANCE", 500_000L);
        result.items().forEach(item -> assertThat(item.amountCents()).as(item.itemCode()).isEqualTo(expected.get(item.itemCode())));
        assertThat(result.items().stream().filter(item -> item.itemCode().equals("DOORS_WINDOWS")).findFirst().orElseThrow().lines())
                .extracting(BudgetCalculator.LineResult::amountCents).containsExactly(1_800_000L, 2_940_000L);
    }

    @Test
    void customDrainageAdds3600YuanWithoutChangingOriginalResultOrIntroducingQuoteAdjustments() {
        var originalLines = golden(inputs());
        var original = BudgetCalculator.calculate(inputs(), originalLines);
        var revisedLines = new ArrayList<>(originalLines);
        revisedLines.add(custom("drainage", "EXTERIOR", "排水沟施工", "METER", "20", 18_000L, null, null));
        var revised = BudgetCalculator.calculate(inputs(), revisedLines);
        assertThat(revised.totalCents()).isEqualTo(48_562_000L);
        assertThat(revised.categoryTotals()).containsEntry("BODY", 35_584_000L).containsEntry("EXTERIOR", 12_978_000L);
        assertThat(revised.items()).hasSize(11);
        assertThat(original.totalCents()).isEqualTo(48_202_000L);
        assertThat(originalLines).hasSize(13);
        assertThatThrownBy(() -> revised.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> revised.items().get(0).lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> revised.categoryTotals().put("BODY", 0L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void decimalsRoundHalfUpPerLineBeforeSumming() {
        var result = BudgetCalculator.calculate(inputs(), List.of(
                custom("decimal", "BODY", "长度计价", "METER", "12.3450", 1_001L, null, null),
                custom("half-cent-a", "EXTERIOR", "半分一", "METER", "0.5000", 1L, null, null),
                custom("half-cent-b", "EXTERIOR", "半分二", "METER", "0.5000", 1L, null, null)));
        assertThat(result.lines()).extracting(BudgetCalculator.LineResult::amountCents).containsExactly(12_357L, 1L, 1L);
        assertThat(result.categoryTotals()).containsEntry("BODY", 12_357L).containsEntry("EXTERIOR", 2L);
        assertThat(result.pricedSubtotalCents()).isEqualTo(12_359L);
    }

    @Test
    void actualBuildingAreaAndExplicitWindowQuantityTakePriorityWithoutApplyingQuarterTwice() {
        var raw = new LinkedHashMap<>(base());
        raw.put("buildingArea", "251.2");
        var actual = BudgetInputs.resolve(raw, Map.of());
        assertThat(BudgetCalculator.resolveQuantity(actual, "SQM", "BUILDING_AREA", null))
                .isEqualTo(new BudgetCalculator.Quantity("251.2", "PROJECT"));
        assertThat(BudgetCalculator.resolveQuantity(actual, "SQM", "WINDOW_AREA", null))
                .isEqualTo(new BudgetCalculator.Quantity("62.8", "DERIVED"));
        raw.put("quantities", Map.of("WINDOW_AREA", "75.1250"));
        assertThat(BudgetCalculator.resolveQuantity(BudgetInputs.resolve(raw, Map.of()), "SQM", "WINDOW_AREA", null))
                .isEqualTo(new BudgetCalculator.Quantity("75.125", "PROJECT"));
        var standardWindow = golden(inputs()).get(5);
        assertThat(standardWindow.quantity()).isEqualTo("60");
        assertThat(BudgetCalculator.calculate(inputs(), List.of(standardWindow)).pricedSubtotalCents()).isEqualTo(2_940_000L);
    }

    @Test
    void quantityRulesDoNotInventProfessionalMeasurementsOrDefaultOne() {
        var empty = BudgetInputs.resolve(Map.of(), Map.of());
        for (String rule : List.of("FOOTPRINT_AREA", "BUILDING_AREA", "ROOF_AREA", "WINDOW_AREA")) {
            assertThat(BudgetCalculator.resolveQuantity(empty, "SQM", rule, null)).isEqualTo(new BudgetCalculator.Quantity(null, null));
        }
        for (var entry : Map.of("DOOR_HOUSEHOLDS", "HOUSEHOLD", "CULTURE_STONE_LENGTH", "METER",
                "LIGHTING_WASHER_LENGTH", "METER", "LIGHTING_STRIP_LENGTH", "METER", "LIGHTING_WALL_LAMP_COUNT", "PIECE",
                "WALL_PAINT_AREA", "SQM", "WATERPROOF_AREA", "SQM", "INSULATED_WATERPROOF_AREA", "SQM").entrySet()) {
            assertThat(BudgetCalculator.resolveQuantity(empty, entry.getValue(), "PROJECT_QUANTITY", entry.getKey()).value()).isNull();
        }
        assertThat(BudgetCalculator.resolveQuantity(empty, "SET", "FIXED_ONE", null))
                .isEqualTo(new BudgetCalculator.Quantity("1", "FIXED_ONE"));
        assertThat(BudgetCalculator.resolveQuantity(empty, "ITEM", "FIXED_ONE", null).value()).isEqualTo("1");
        assertThat(BudgetCalculator.resolveQuantity(inputs(), "SQM", "FOOTPRINT_AREA", null))
                .isEqualTo(new BudgetCalculator.Quantity("120", "PROJECT"));
        assertThat(BudgetCalculator.resolveQuantity(inputs(), "SQM", "BUILDING_AREA", null))
                .isEqualTo(new BudgetCalculator.Quantity("240", "DERIVED"));
    }

    @Test
    void quantityRuleUnitAndKeyMismatchesAreRejected() {
        for (String[] invalid : List.of(
                new String[]{"METER", "BUILDING_AREA", null}, new String[]{"SQM", "FIXED_ONE", null},
                new String[]{"METER", "FIXED_ONE", null}, new String[]{"SET", "FIXED_ONE", "DOOR_HOUSEHOLDS"},
                new String[]{"SQM", "WINDOW_AREA", "WINDOW_AREA"}, new String[]{"PIECE", "PROJECT_QUANTITY", "DOOR_HOUSEHOLDS"},
                new String[]{"SQM", "PROJECT_QUANTITY", "CULTURE_STONE_LENGTH"}, new String[]{"SQM", "PROJECT_QUANTITY", "GUESSED_AREA"},
                new String[]{"METER", "PROJECT_QUANTITY", null}, new String[]{"METER", "UNKNOWN_RULE", null},
                new String[]{"UNKNOWN_UNIT", "FIXED_ONE", null})) {
            assertInvalid(() -> BudgetCalculator.resolveQuantity(inputs(), invalid[0], invalid[1], invalid[2]));
        }
    }

    @Test
    void derivedWindowQuantityRoundsToFourPlacesAndTinyZeroRemainsMissing() {
        var raw = BudgetInputs.resolve(Map.of("buildingArea", "1.0001"), Map.of());
        assertThat(BudgetCalculator.resolveQuantity(raw, "SQM", "WINDOW_AREA", null).value()).isEqualTo("0.25");
        var tiny = BudgetInputs.resolve(Map.of("buildingArea", "0.0001"), Map.of());
        assertThat(BudgetCalculator.resolveQuantity(tiny, "SQM", "WINDOW_AREA", null).value()).isNull();
    }

    @Test
    void missingPriceMissingQuantityBothAndExplicitFreeHaveDifferentStates() {
        var selected = new ArrayList<>(golden(inputs()));
        selected.add(custom("missing-price", "EXTERIOR", "未定价", "METER", "20", null, null, null));
        selected.add(custom("missing-quantity", "EXTERIOR", "待核工程量", "METER", null, 18_000L, null, null));
        selected.add(custom("missing-both", "EXTERIOR", "待核价量", "METER", null, null, null, null));
        selected.add(custom("free", "EXTERIOR", "赠送服务", "ITEM", "1", 0L, "合同约定赠送", null));
        var result = BudgetCalculator.calculate(inputs(), selected);
        assertThat(result.completeness()).isEqualTo("INCOMPLETE");
        assertThat(result.totalCents()).isNull();
        assertThat(result.pricedSubtotalCents()).isEqualTo(48_202_000L);
        assertThat(result.lines().subList(13, 17)).extracting(BudgetCalculator.LineResult::status)
                .containsExactly("MISSING_PRICE", "MISSING_QUANTITY", "MISSING_BOTH", "PRICED");
        assertThat(result.lines().subList(13, 17)).extracting(BudgetCalculator.LineResult::amountCents)
                .containsExactly(null, null, null, 0L);
        selected.removeIf(line -> line.lineId().startsWith("missing-"));
        assertThat(BudgetCalculator.calculate(inputs(), selected).completeness()).isEqualTo("COMPLETE");
        selected.set(5, repriced(selected.get(5), "60", null, null, null));
        var partialDoorWindow = BudgetCalculator.calculate(inputs(), selected).items().stream()
                .filter(item -> item.itemCode().equals("DOORS_WINDOWS")).findFirst().orElseThrow();
        assertThat(partialDoorWindow.completeness()).isEqualTo("INCOMPLETE");
        assertThat(partialDoorWindow.amountCents()).isNull();
        assertThat(partialDoorWindow.pricedSubtotalCents()).isEqualTo(1_800_000L);
    }

    @Test
    void explicitExclusionsHaveNoAmountAndNeverPretendMissingPriceMeansZero() {
        var selected = new ArrayList<>(golden(inputs()));
        var foundation = selected.get(0);
        selected.set(0, repriced(foundation, null, null, null, "业主另行委托施工"));
        var result = BudgetCalculator.calculate(inputs(), selected);
        assertThat(result.completeness()).isEqualTo("COMPLETE");
        assertThat(result.totalCents()).isEqualTo(41_962_000L);
        assertThat(result.lines().get(0).status()).isEqualTo("EXCLUDED");
        assertThat(result.lines().get(0).amountCents()).isNull();
        assertThat(result.items().get(0).amountCents()).isNull();
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(repriced(foundation, "120", 0L, null, null))));
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(repriced(foundation, "120", 0L, " ", null))));
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(repriced(foundation, null, null, null, " "))));
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(repriced(foundation, "0", null, null, "明确排除"))));
    }

    @Test
    void emptyOrPartialStandardScopeCannotBePresentedAsACompleteBudget() {
        assertThat(BudgetCalculator.calculate(inputs(), List.of()).missingItemCodes()).hasSize(10);
        var partial = BudgetCalculator.calculate(inputs(), List.of(golden(inputs()).get(0),
                custom("custom", "EXTERIOR", "补充费", "ITEM", "1", 100L, null, null)));
        assertThat(partial.completeness()).isEqualTo("INCOMPLETE");
        assertThat(partial.totalCents()).isNull();
        assertThat(partial.missingItemCodes()).hasSize(9).doesNotContain("FOUNDATION");
        var emptyProject = BudgetInputs.resolve(Map.of(), Map.of());
        var selectedWithExplicitAmounts = BudgetCalculator.calculate(emptyProject, golden(inputs()));
        assertThat(selectedWithExplicitAmounts.completeness()).isEqualTo("INCOMPLETE");
        assertThat(selectedWithExplicitAmounts.missingFields()).contains("regionCode", "footprintArea", "floorCount", "roofArea", "buildingArea");
    }

    @Test
    void independentActualAreaConflictKeepsPricesButBlocksACompleteTotal() {
        var raw = new LinkedHashMap<>(base());
        raw.put("buildingArea", "251.2");
        var actual = BudgetInputs.resolve(raw, Map.of());
        var result = BudgetCalculator.calculate(actual, golden(actual));
        assertThat(result.missingFields()).isEmpty();
        assertThat(result.missingItemCodes()).isEmpty();
        assertThat(result.lines()).allMatch(line -> line.status().equals("PRICED"));
        assertThat(result.completeness()).isEqualTo("INCOMPLETE");
        assertThat(result.totalCents()).isNull();
        assertThat(result.pricedSubtotalCents()).isPositive();
        assertThat(result.warnings()).contains("BUILDING_AREA_REVIEW_REQUIRED");
        assertThat(actual.values()).containsEntry("buildingArea", "251.2");
        var rangeWarning = BudgetInputs.resolve(Map.of("regionCode", "TEST_ONLY", "footprintArea", "1000000", "floorCount", 20,
                "buildingArea", "240", "roofArea", "112"), Map.of());
        var reviewRequired = BudgetCalculator.calculate(rangeWarning, golden(inputs()));
        assertThat(reviewRequired.missingFields()).isEmpty();
        assertThat(reviewRequired.warnings()).contains("DERIVED_buildingArea_OUT_OF_RANGE");
        assertThat(reviewRequired.completeness()).isEqualTo("INCOMPLETE");
    }

    @Test
    void sameNamesRemainSeparateByIdAndProjectCustomLinesStaySeparate() {
        var one = new LineInput("template-a", "9007199254740993", "CUSTOM_A", "EXTERIOR", "同名施工",
                "9007199254740995", "选项", "ITEM", "1", 100L, null, null, "CUSTOM_TEMPLATE");
        var two = new LineInput("template-b", "9007199254740994", "CUSTOM_B", "EXTERIOR", "同名施工",
                "9007199254740996", "选项", "ITEM", "1", 200L, null, null, "CUSTOM_TEMPLATE");
        var result = BudgetCalculator.calculate(inputs(), List.of(one, two,
                custom("temporary-a", "EXTERIOR", "同名施工", "ITEM", "1", 300L, null, null),
                custom("temporary-b", "EXTERIOR", "同名施工", "ITEM", "1", 400L, null, null)));
        assertThat(result.items()).hasSize(4);
        assertThat(result.items().get(0).itemId()).isEqualTo("9007199254740993");
        assertThat(result.lines().get(0).input().optionId()).isEqualTo("9007199254740995");
        assertThat(result.pricedSubtotalCents()).isEqualTo(1_000L);
    }

    @Test
    void duplicateLinesOptionsCatalogParentsAndConflictingIdentitiesAreRejected() {
        var foundation = golden(inputs()).get(0);
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(foundation, foundation)));
        var sameOption = new LineInput("new-line", foundation.itemId(), foundation.itemCode(), foundation.category(), foundation.publicName(),
                foundation.optionId(), foundation.optionLabel(), foundation.unit(), foundation.quantity(), foundation.unitPriceCents(), null, null, foundation.source());
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(foundation, sameOption)));
        var parent = new LineInput("parent", foundation.itemId(), foundation.itemCode(), foundation.category(), foundation.publicName(),
                null, null, "SQM", "120", 52_000L, null, null, "STANDARD");
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(parent)));
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(foundation, repriced(parent, "120", null, null, null))));
        var conflictingItem = new LineInput("different-item", "999", foundation.itemCode(), "BODY", foundation.publicName(),
                "998", "选项", "SQM", "120", 52_000L, null, null, "STANDARD");
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(foundation, conflictingItem)));
        var changedName = new LineInput("changed-name", foundation.itemId(), foundation.itemCode(), "BODY", "冲突名称",
                "998", "选项", "SQM", "120", 52_000L, null, null, "STANDARD");
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(foundation, changedName)));
    }

    @Test
    void invalidQuantitySyntaxPrecisionRangesAndIndivisibleUnitsAreRejected() {
        for (String value : new String[]{"0", "-1", "NaN", "Infinity", "1e3", "1.00001", "1000000.0001", " 1", "01", ".5", "1.", "1米"}) {
            assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(custom("invalid", "EXTERIOR", "计价", "METER", value, 1L, null, null))));
        }
        for (String unit : List.of("PIECE", "SET", "HOUSEHOLD", "ITEM")) {
            for (String value : List.of("0.5", "100001")) {
                assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(custom("count", "EXTERIOR", "计数", unit, value, 1L, null, null))));
            }
            assertThat(BudgetCalculator.calculate(inputs(), List.of(custom("count", "EXTERIOR", "计数", unit, "100000.0000", 1L, null, null)))
                    .pricedSubtotalCents()).isEqualTo(100_000L);
        }
        assertThat(BudgetCalculator.calculate(inputs(), List.of(custom("area-max", "BODY", "面积", "SQM", "1000000.0000", 1L, null, null)))
                .pricedSubtotalCents()).isEqualTo(1_000_000L);
    }

    @Test
    void unitPriceLineCategoryAndWholeBudgetLimitsAreEnforcedBeforeLongOrJsOverflow() {
        for (long price : new long[]{-1, 100_000_001L, Long.MAX_VALUE}) {
            assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(custom("price", "EXTERIOR", "价格", "METER", "1", price, null, null))));
        }
        var maximum = custom("maximum", "BODY", "上限", "METER", "100", 100_000_000L, null, null);
        assertThat(BudgetCalculator.calculate(inputs(), List.of(maximum)).pricedSubtotalCents()).isEqualTo(10_000_000_000L);
        assertAmountLimit(() -> BudgetCalculator.calculate(inputs(), List.of(repriced(maximum, "100.0001", 100_000_000L, null, null))));
        var body = custom("body", "BODY", "主体", "METER", "60", 100_000_000L, null, null);
        var exterior = custom("exterior", "EXTERIOR", "外装", "METER", "41", 100_000_000L, null, null);
        assertAmountLimit(() -> BudgetCalculator.calculate(inputs(), List.of(body, exterior)));
        assertAmountLimit(() -> BudgetCalculator.calculate(inputs(), List.of(maximum,
                custom("one-more", "BODY", "超出一分", "ITEM", "1", 1L, null, null))));
    }

    @Test
    void unknownUnitsSourcesCategoriesAndMalformedIdentitiesAreRejected() {
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), null));
        assertInvalid(() -> BudgetCalculator.calculate(null, List.of()));
        for (LineInput invalid : List.of(
                custom("", "BODY", "名称", "ITEM", "1", 1L, null, null),
                custom("wrong-category", "OTHER", "名称", "ITEM", "1", 1L, null, null),
                custom("wrong-unit", "BODY", "名称", "TON", "1", 1L, null, null),
                custom("wrong-name", "BODY", " ", "ITEM", "1", 1L, null, null),
                new LineInput("unknown-source", null, "TEST", "BODY", "名称", null, null, "ITEM", "1", 1L, null, null, "UNKNOWN"),
                new LineInput("bad-id", "01", "FOUNDATION", "BODY", "名称", "1", null, "SQM", "1", 1L, null, null, "STANDARD"),
                new LineInput("bad-standard", "1", "RAILING", "EXTERIOR", "栏杆", "2", null, "METER", "1", 1L, null, null, "STANDARD"),
                new LineInput("custom-reference", "1", "CUSTOM", "BODY", "名称", null, null, "ITEM", "1", 1L, null, null, "PROJECT_CUSTOM"))) {
            assertInvalid(() -> BudgetCalculator.calculate(inputs(), List.of(invalid)));
        }
    }

    @Test
    void missingRequiredSubgroupKeepsPricedSiblingButCannotBecomeComplete() {
        var rows = new java.util.ArrayList<>(golden(inputs()));
        var window = rows.get(5);
        rows.set(5, new LineInput("missing-window", window.itemId(), window.itemCode(), window.category(), window.publicName(),
                null, "窗配置待补", "SQM", null, null, null, null, "STANDARD"));
        var result = BudgetCalculator.calculate(inputs(), rows);
        assertThat(result.completeness()).isEqualTo("INCOMPLETE");
        assertThat(result.totalCents()).isNull();
        assertThat(result.pricedSubtotalCents()).isEqualTo(45_262_000L);
        var doors = result.items().stream().filter(i -> i.itemCode().equals("DOORS_WINDOWS")).findFirst().orElseThrow();
        assertThat(doors.amountCents()).isNull();
        assertThat(doors.pricedSubtotalCents()).isEqualTo(1_800_000L);
        assertThat(doors.lines()).extracting(BudgetCalculator.LineResult::status).containsExactly("PRICED", "MISSING_BOTH");
    }

    @Test
    void explicitlyExcludedRequiredSubgroupKeepsSiblingWithoutInventingAParentPrice() {
        var rows = new ArrayList<>(golden(inputs()));
        var window = rows.get(5);
        var excluded = new LineInput("excluded-window", window.itemId(), window.itemCode(), window.category(), window.publicName(),
                null, "窗待选择", "SQM", null, null, null, "业主自理", "STANDARD");
        rows.set(5, excluded);
        var result = BudgetCalculator.calculate(inputs(), rows);
        assertThat(result.completeness()).isEqualTo("COMPLETE");
        assertThat(result.totalCents()).isEqualTo(45_262_000L);
        var doors = result.items().stream().filter(i -> i.itemCode().equals("DOORS_WINDOWS")).findFirst().orElseThrow();
        assertThat(doors.amountCents()).isEqualTo(1_800_000L);
        assertThat(doors.lines()).extracting(BudgetCalculator.LineResult::status).containsExactly("PRICED", "EXCLUDED");
        rows.set(5, repriced(excluded, "60", null, null, "业主自理"));
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), rows));
        rows.set(5, repriced(excluded, null, 49_000L, null, "业主自理"));
        assertInvalid(() -> BudgetCalculator.calculate(inputs(), rows));
    }

    @Test
    void fixedFeesUseOnlyExplicitItemOrSetUnits() {
        for (String unit : List.of("PIECE", "HOUSEHOLD", "SQM", "METER")) {
            assertInvalid(() -> BudgetCalculator.resolveQuantity(inputs(), unit, "FIXED_ONE", null));
        }
        for (String unit : List.of("ITEM", "SET")) {
            assertThat(BudgetCalculator.resolveQuantity(inputs(), unit, "FIXED_ONE", null).value()).isEqualTo("1");
        }
    }

    private static BudgetInputs.Resolved inputs() { return BudgetInputs.resolve(base(), Map.of()); }

    private static Map<String, Object> base() {
        return Map.of("regionCode", "TEST_ONLY", "footprintArea", "120", "floorCount", 2, "roofArea", "112",
                "quantities", Map.of("DOOR_HOUSEHOLDS", "1", "CULTURE_STONE_LENGTH", "44",
                        "LIGHTING_WASHER_LENGTH", "20", "LIGHTING_STRIP_LENGTH", "30", "WATERPROOF_AREA", "20"));
    }

    /** Explicit fixture prices only; never production seed or calculated expected values. */
    private static List<LineInput> golden(BudgetInputs.Resolved inputs) {
        return List.of(
                standard(inputs, 1, 207001, "FOUNDATION", "BODY", "地基基础", "条形基础", "SQM", "FOOTPRINT_AREA", null, 52_000),
                standard(inputs, 2, 207002, "STRUCTURE", "BODY", "主体结构", "框架", "SQM", "BUILDING_AREA", null, 98_000),
                standard(inputs, 3, 207003, "ROOF", "BODY", "屋面结构", "单层现浇", "SQM", "ROOF_AREA", null, 52_000),
                standard(inputs, 4, 207004, "DECORATION", "EXTERIOR", "装饰构件", "现代", "SQM", "BUILDING_AREA", null, 12_000),
                standard(inputs, 5, 207005, "DOORS_WINDOWS", "EXTERIOR", "门窗", "全铝门", "HOUSEHOLD", "PROJECT_QUANTITY", "DOOR_HOUSEHOLDS", 1_800_000),
                standard(inputs, 6, 207005, "DOORS_WINDOWS", "EXTERIOR", "门窗", "1.8壁厚窗", "SQM", "WINDOW_AREA", null, 49_000),
                standard(inputs, 7, 207006, "WALL_PAINT", "EXTERIOR", "外墙漆", "黑线工艺水包砂", "SQM", "BUILDING_AREA", null, 12_000),
                standard(inputs, 8, 207007, "CULTURE_STONE", "EXTERIOR", "文化石", "页岩陶粒", "METER", "PROJECT_QUANTITY", "CULTURE_STONE_LENGTH", 12_000),
                standard(inputs, 9, 207008, "LIGHTING", "EXTERIOR", "灯具", "洗墙灯", "METER", "PROJECT_QUANTITY", "LIGHTING_WASHER_LENGTH", 12_000),
                standard(inputs, 10, 207008, "LIGHTING", "EXTERIOR", "灯具", "灯带", "METER", "PROJECT_QUANTITY", "LIGHTING_STRIP_LENGTH", 6_000),
                standard(inputs, 11, 207009, "WATERPROOF_LIGHTNING", "EXTERIOR", "防水防雷", "雨虹防水", "SQM", "PROJECT_QUANTITY", "WATERPROOF_AREA", 8_500),
                standard(inputs, 12, 207009, "WATERPROOF_LIGHTNING", "EXTERIOR", "防水防雷", "防雷", "SET", "FIXED_ONE", null, 500_000),
                standard(inputs, 13, 207010, "INSURANCE", "EXTERIOR", "保险", "保险", "SET", "FIXED_ONE", null, 500_000));
    }

    private static LineInput standard(BudgetInputs.Resolved inputs, int lineId, int itemId, String code, String category,
                                      String name, String label, String unit, String rule, String key, long price) {
        return new LineInput(String.valueOf(lineId), String.valueOf(itemId), code, category, name, String.valueOf(1000 + lineId),
                label, unit, BudgetCalculator.resolveQuantity(inputs, unit, rule, key).value(), price, null, null, "STANDARD");
    }

    private static LineInput custom(String id, String category, String name, String unit, String quantity, Long price, String free, String excluded) {
        return new LineInput(id, null, "PROJECT_CUSTOM", category, name, null, null, unit, quantity, price, free, excluded, "PROJECT_CUSTOM");
    }

    private static LineInput repriced(LineInput original, String quantity, Long price, String free, String excluded) {
        return new LineInput(original.lineId(), original.itemId(), original.itemCode(), original.category(), original.publicName(),
                original.optionId(), original.optionLabel(), original.unit(), quantity, price, free, excluded, original.source());
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(1_071_000_100));
    }

    private static void assertAmountLimit(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(1_071_000_103));
    }
}
