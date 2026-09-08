package cn.iocoder.yudao.module.identity.wechat;

/**
 * 微信身份端口：login code → openid/unionid（架构 §8.1）
 *
 * P2A 使用 Stub；P2B（G0B 后）替换为真实 code2session Adapter，领域模型不变。
 * 客户端只提交临时 login code，禁止提交自称的 openid。
 */
public interface WechatIdentityPort {

    WechatSession codeToSession(String appid, String loginCode);

    record WechatSession(String openid, String unionid) {
    }

}
