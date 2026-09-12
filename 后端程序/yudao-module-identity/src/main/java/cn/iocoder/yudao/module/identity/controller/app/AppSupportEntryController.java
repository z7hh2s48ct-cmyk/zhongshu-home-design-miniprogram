package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppSupportEntryRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 客服入口（页面 15 公开区）。
 * P0 不建工单系统，配置从部署环境注入；未配置时下发空串，前端据此隐藏对应入口，
 * 而不是展示打不通的假号码。
 */
@Tag(name = "小程序 - 客服入口")
@RestController
@RequestMapping("/design/v1/support-entry")
@PermitAll
public class AppSupportEntryController {

    @Value("${zhongshu.support.phone:}")
    private String phone;

    @Value("${zhongshu.support.wecom-corp-id:}")
    private String wecomCorpId;

    @Value("${zhongshu.support.wecom-url:}")
    private String wecomUrl;

    @Value("${zhongshu.support.help-url:}")
    private String helpUrl;

    @GetMapping
    @Operation(summary = "获取客服入口配置（P0 不扩展为完整工单系统）")
    public CommonResult<AppSupportEntryRespVO> getSupportEntry() {
        AppSupportEntryRespVO vo = new AppSupportEntryRespVO();
        vo.setPhone(phone);
        vo.setWecomCorpId(wecomCorpId);
        vo.setWecomUrl(wecomUrl);
        vo.setHelpUrl(helpUrl);
        return success(vo);
    }

}
