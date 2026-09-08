package cn.iocoder.yudao.framework.common.exception;

/**
 * 众墅之家设计小程序：平台合同级错误码（跨业务模块共用）
 *
 * 使用 1-099-000-000 段；常量字段名即架构文档 §7.6 的稳定字符串标识，
 * 数值码随 {@link CommonResult#code} 上报，字段名与数值码的对应关系由合同测试锁定。
 *
 * 模块级错误码段：identity=1-070、design=1-071、commerce=1-072、ai-orchestration=1-073。
 */
public interface ZhongshuErrorCodeConstants {

    // ========== 通用幂等与并发 1-099-000-000 ==========
    ErrorCode IDEMPOTENCY_KEY_REUSED = new ErrorCode(1_099_000_000, "幂等键已被不同请求内容使用");
    ErrorCode STATE_VERSION_CONFLICT = new ErrorCode(1_099_000_001, "数据版本已过期，请刷新后重试");
    ErrorCode RESOURCE_FORBIDDEN = new ErrorCode(1_099_000_002, "无权访问该资源");

}
