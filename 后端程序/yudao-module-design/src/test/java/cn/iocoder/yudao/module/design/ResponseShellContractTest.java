package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.design.enums.ErrorCodeConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1A 响应壳合同：CommonResult / PageResult / CursorPageResult 的线上 JSON 形状
 */
class ResponseShellContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successResultSerializesToUnifiedShell() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(CommonResult.success("x")));
        assertThat(json.get("code").asInt()).isZero();
        assertThat(json.get("msg").asText()).isEmpty();
        assertThat(json.get("data").asText()).isEqualTo("x");
    }

    @Test
    void errorResultCarriesStableNumericCodeAndMsg() throws Exception {
        CommonResult<?> result = CommonResult.error(ErrorCodeConstants.DESIGN_STAGE_CONFLICT);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));
        assertThat(json.get("code").asInt()).isEqualTo(1_071_000_000);
        assertThat(json.get("msg").asText()).isNotBlank();
    }

    @Test
    void pageResultShapeIsListAndTotal() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(PageResult.empty()));
        assertThat(json.get("total").asLong()).isZero();
        assertThat(json.get("list").isEmpty()).isTrue();
    }

    @Test
    void cursorPageResultShapeIsListAndNextCursor() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(CursorPageResult.empty()));
        assertThat(json.get("list").isEmpty()).isTrue();
        assertThat(json.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void platformErrorCodesAreDefinedInCommon() {
        assertThat(ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()).isEqualTo(1_099_000_000);
        assertThat(ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT.getCode()).isEqualTo(1_099_000_001);
        assertThat(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN.getCode()).isEqualTo(1_099_000_002);
    }

}
