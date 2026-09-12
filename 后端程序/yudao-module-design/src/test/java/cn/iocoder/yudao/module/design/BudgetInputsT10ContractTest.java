package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetInputsT10ContractTest {

    @Test
    void validatesOnlyNestedBudgetContractAndPreservesExistingDesignFields() {
        var request = Map.<String, Object>of("prompt", "保留原设计要求", "budgetInputs", Map.of("footprintArea", "120.0000", "floorCount", 2));
        var saved = BudgetInputs.validateRequirementInputs(request);
        assertThat(saved).containsEntry("prompt", "保留原设计要求");
        assertThat(saved.get("budgetInputs")).isEqualTo(Map.of("footprintArea", "120", "floorCount", 2));
        assertThat(request.get("budgetInputs")).isEqualTo(Map.of("footprintArea", "120.0000", "floorCount", 2));
        assertThat(BudgetInputs.validateRequirementInputs(Map.of("legacyField", "kept"))).containsEntry("legacyField", "kept");
    }

    @Test
    void actualBuildingAreaTakesPriorityAndMissingAreaIsDerivedOnce() {
        var actual = BudgetInputs.resolve(Map.of("footprintArea", "120", "floorCount", 2, "buildingArea", "251.2"), Map.of());
        assertThat(actual.values()).containsEntry("buildingArea", "251.2");
        assertThat(actual.sources()).containsEntry("buildingArea", "PROJECT");
        assertThat(actual.warnings()).containsExactly("BUILDING_AREA_REVIEW_REQUIRED");
        var derived = BudgetInputs.resolve(Map.of("footprintArea", "120", "floorCount", 2), Map.of());
        assertThat(derived.values()).containsEntry("buildingArea", "240");
        assertThat(derived.sources()).containsEntry("buildingArea", "DERIVED");
    }

    @Test
    void overridesAndRestoreDoNotMutateImportedValuesOrOverwriteActualArea() {
        var imported = Map.<String, Object>of("regionCode", "TEST_ONLY", "footprintArea", "120", "floorCount", 2, "buildingArea", "240", "roofArea", "112");
        var edited = BudgetInputs.resolve(imported, Map.of("footprintArea", "130", "floorCount", 3));
        assertThat(edited.values()).containsEntry("footprintArea", "130").containsEntry("buildingArea", "240");
        assertThat(edited.sources()).containsEntry("footprintArea", "USER_OVERRIDE");
        assertThat(edited.warnings()).contains("BUILDING_AREA_REVIEW_REQUIRED");
        var restored = BudgetInputs.resolve(imported, Map.of());
        assertThat(restored.publicValues()).containsExactlyInAnyOrderEntriesOf(imported);
        assertThat(restored.warnings()).isEmpty();
        assertThatThrownBy(() -> BudgetInputs.resolve(imported, Map.of("buildingArea", "1"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BudgetInputs.resolve(imported, Map.of("unitPriceCents", 1))).isInstanceOf(ServiceException.class);
    }

    @Test
    void derivesFootprintOnlyFromBothDimensionsAndNeverGuessesRoofOrProfessionalQuantities() {
        var partial = BudgetInputs.resolve(Map.of("faceWidth", "12", "floorCount", 2), Map.of());
        assertThat(partial.missingFields()).contains("footprintArea", "buildingArea", "roofArea");
        assertThat(partial.quantities()).isEmpty();
        var completeDimensions = BudgetInputs.resolve(Map.of("faceWidth", "12", "depth", "10", "floorCount", 2), Map.of());
        assertThat(completeDimensions.values()).containsEntry("footprintArea", "120").containsEntry("buildingArea", "240");
        assertThat(completeDimensions.values()).doesNotContainKey("roofArea");
        assertThat(completeDimensions.quantities()).isEmpty();
        var cleared = new LinkedHashMap<String, Object>();
        cleared.put("footprintArea", null);
        assertThat(BudgetInputs.resolve(Map.of("faceWidth", "12", "depth", "10"), cleared).missingFields()).contains("footprintArea");
    }

    @Test
    void invalidPrecisionNumbersBoundsAndUnitsAreRejected() {
        for (Object value : new Object[]{"0", "-1", "NaN", "Infinity", "1e3", "1.00001", "1000000.0001", " 120", "12㎡", true, 120.0}) {
            assertThatThrownBy(() -> BudgetInputs.validateRequirementInputs(Map.of("budgetInputs", Map.of("footprintArea", value))))
                    .as("invalid area %s", value).isInstanceOf(ServiceException.class);
        }
        for (Object value : new Object[]{0, 21, "1.5"}) {
            assertThatThrownBy(() -> BudgetInputs.resolve(Map.of("floorCount", value), Map.of())).isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> BudgetInputs.resolve(Map.of("faceWidth", "1000.0001"), Map.of())).isInstanceOf(ServiceException.class);
        for (String value : new String[]{"0", "1.5", "100001"}) {
            assertThatThrownBy(() -> BudgetInputs.resolve(Map.of("quantities", Map.of("DOOR_HOUSEHOLDS", value)), Map.of()))
                    .isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> BudgetInputs.validateRequirementInputs(Map.of("budgetInputs", Map.of("unitPriceCents", 1))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> BudgetInputs.resolve(Map.of("quantities", Map.of("GUESSED_PERIMETER", "44")), Map.of()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void professionalQuantitiesStayInternalInBothBudgetAndExistingResultProjection() {
        var raw = Map.<String, Object>of("footprintArea", "120", "quantities", Map.of("CULTURE_STONE_LENGTH", "44", "DOOR_HOUSEHOLDS", "1"));
        var result = BudgetInputs.resolve(raw, Map.of());
        assertThat(result.quantities()).containsEntry("CULTURE_STONE_LENGTH", "44");
        assertThat(result.publicValues()).containsExactlyEntriesOf(Map.of("footprintArea", "120"));
        String publicConfig = BudgetInputs.publicConfigJson("{\"styleCode\":\"MODERN\",\"budgetInputs\":{\"footprintArea\":\"120\",\"quantities\":{\"CULTURE_STONE_LENGTH\":\"44\"},\"internalNote\":\"private\"}}");
        assertThat(publicConfig).contains("MODERN", "footprintArea").doesNotContain("quantities", "CULTURE_STONE_LENGTH", "internalNote", "private");
        assertThat(BudgetInputs.publicConfigJson("{\"budgetInputs\":null}")).isEqualTo("{\"budgetInputs\":{}}");
    }

    @Test
    void legacyReadUsesOnlyStoredFieldsNotUiDefaultsOrFreeText() {
        var read = BudgetInputs.fromSnapshot(Map.of("floor", "两层", "prompt", "面宽12.6m进深13.8m", "buildingArea", 240));
        var values = BudgetInputs.resolve(read, Map.of());
        assertThat(values.values()).containsEntry("floorCount", 2).containsEntry("buildingArea", "240");
        assertThat(values.missingFields()).contains("footprintArea", "roofArea", "regionCode");
        assertThat(BudgetInputs.resolve(BudgetInputs.fromSnapshot(Map.of("floor", "未知层数", "footprintArea", "bad")), Map.of()).missingFields())
                .contains("floorCount", "footprintArea");
        assertThat(BudgetInputs.resolve(Map.of(), Map.of()).publicValues()).isEmpty();
    }

    @Test
    void derivationRoundsToFourPlacesAndOutOfRangeStaysMissing() {
        var rounded = BudgetInputs.resolve(Map.of("faceWidth", "1.0001", "depth", "1.0001"), Map.of());
        assertThat(rounded.values()).containsEntry("footprintArea", "1.0002");
        var overflow = BudgetInputs.resolve(Map.of("footprintArea", "1000000", "floorCount", 20), Map.of());
        assertThat(overflow.missingFields()).contains("buildingArea");
        assertThat(overflow.warnings()).contains("DERIVED_buildingArea_OUT_OF_RANGE");
    }

    @Test
    void identifiersAreExactPositiveLongStrings() {
        assertThat(BudgetInputs.positiveId("9007199254740993")).isEqualTo(9007199254740993L);
        for (String id : new String[]{"0", "-1", "01", "1e3", "1.0", " 1", "9223372036854775808", ""}) {
            assertThatThrownBy(() -> BudgetInputs.positiveId(id)).isInstanceOf(ServiceException.class);
        }
    }
}
