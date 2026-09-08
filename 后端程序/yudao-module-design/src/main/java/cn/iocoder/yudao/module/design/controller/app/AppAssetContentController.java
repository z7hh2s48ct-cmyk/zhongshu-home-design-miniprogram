package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发/联调内容端点：本地存储适配器产生的 local:// URL 无法被小程序 image 组件加载，
 * 该端点凭一次性票据流式输出对象字节；切 COS 后由 COS 签名 URL 取代
 * （zhongshu.design.asset.dev-content-endpoint=false 关闭）。
 */
@Tag(name = "小程序 - 资产内容（开发期）")
@RestController
@RequestMapping("/design/v1/assets")
@PermitAll // 自有 user_session Bearer 校验在方法内强制执行
public class AppAssetContentController {

    @Resource
    private AssetService assetService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    // fail-safe 默认关：仅 zsdev profile 显式打开；无 profile 裸启动时开发端点不可用
    @Value("${zhongshu.design.asset.dev-content-endpoint:false}")
    private boolean enabled;

    @GetMapping("/{assetId}/content")
    @Operation(summary = "凭一次性票据读取对象内容（开发期；生产切 COS 后关闭）")
    public ResponseEntity<byte[]> content(@PathVariable("assetId") String assetId,
                                          @RequestParam("ticket") String ticket,
                                          @org.springframework.web.bind.annotation.RequestHeader(
                                                  value = "Authorization", required = false) String authorization) {
        if (!enabled) {
            throw new org.springframework.security.access.AccessDeniedException("内容端点已关闭");
        }
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        long userId = identitySessionPort.requireUnrestricted(token).accountId();
        var result = assetService.readForTicket(userId, Long.parseLong(assetId), ticket);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(result.mimeType()));
        headers.setCacheControl("private, max-age=300");
        return ResponseEntity.ok().headers(headers).body(result.content());
    }

    @PostMapping("/{assetId}/content")
    @Operation(summary = "开发期直传：multipart 交付对象字节（本地适配器无 http 直传地址；切 COS 后关闭）")
    public CommonResult<Boolean> upload(@PathVariable("assetId") String assetId,
                                        @org.springframework.web.bind.annotation.RequestParam("file")
                                        org.springframework.web.multipart.MultipartFile file,
                                        @org.springframework.web.bind.annotation.RequestHeader(
                                                value = "Authorization", required = false) String authorization) {
        if (!enabled) {
            throw new org.springframework.security.access.AccessDeniedException("内容端点已关闭");
        }
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        long userId = identitySessionPort.requireUnrestricted(token).accountId();
        try (java.io.InputStream in = file.getInputStream()) {
            assetService.devDirectUpload(userId, Long.parseLong(assetId), file.getSize(), in.readAllBytes());
        } catch (java.io.IOException e) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(1_071_000_001, "读取上传内容失败");
        }
        return CommonResult.success(true);
    }

}
