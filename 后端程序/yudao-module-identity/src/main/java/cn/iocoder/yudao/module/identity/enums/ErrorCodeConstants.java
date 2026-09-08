package cn.iocoder.yudao.module.identity.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * identity 模块错误码
 *
 * 使用 1-070-000-000 段；常量字段名即架构文档 §7.6 的稳定字符串标识，
 * 由合同测试锁定字段名与数值码的对应关系，禁止改名或复用数值。
 */
public interface ErrorCodeConstants {

    // ========== 授权码 1-070-000-000 ==========
    ErrorCode ACCESS_CODE_INVALID = new ErrorCode(1_070_000_000, "授权码无效");
    ErrorCode ACCESS_CODE_EXPIRED = new ErrorCode(1_070_000_001, "授权码已过期");
    ErrorCode ACCESS_CODE_DISABLED = new ErrorCode(1_070_000_002, "授权码已停用");
    ErrorCode ACCESS_CODE_ALREADY_CONSUMED = new ErrorCode(1_070_000_003, "授权码已被使用");
    ErrorCode ACCESS_CODE_SECRET_ALREADY_EXPOSED = new ErrorCode(1_070_000_004, "授权码完整明文已交付，不能再次交付");
    ErrorCode WECHAT_IDENTITY_ALREADY_BOUND = new ErrorCode(1_070_000_005, "微信身份已绑定其他账号");

}
