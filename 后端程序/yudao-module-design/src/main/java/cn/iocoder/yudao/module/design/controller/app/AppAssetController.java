package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppAssetDownloadTicketRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppAssetUploadTicketReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppAssetUploadTicketRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序/后台 - 资产上传与下载")
@RestController
@RequestMapping("/design/v1/assets")
@PermitAll // 自有 user_session Bearer 校验在每个方法内强制执行（requireAccountId）
public class AppAssetController {

    @Resource
    private AssetService assetService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @PostMapping("/upload-tickets")
    @Operation(summary = "申请私有存储上传凭证：服务端按资产类型强制 MIME/大小，object key 由服务端生成")
    public CommonResult<AppAssetUploadTicketRespVO> createUploadTicket(
            @Valid @RequestBody AppAssetUploadTicketReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        AssetService.UploadTicket ticket = assetService.createUploadTicket(userId,
                reqVO.getAssetType(), reqVO.getMimeType(), reqVO.getSizeBytes(),
                reqVO.getSha256().toLowerCase());
        AppAssetUploadTicketRespVO vo = new AppAssetUploadTicketRespVO();
        vo.setAssetId(String.valueOf(ticket.assetId()));
        vo.setUploadUrl(ticket.uploadUrl());
        vo.setHeaders(Map.of("Content-Type", reqVO.getMimeType()));
        return success(vo);
    }

    @PostMapping("/{assetId}/complete")
    @Operation(summary = "上传完成：服务端校验 object、大小、SHA-256、魔数、像素、EXIF 剥离与内容审核")
    public CommonResult<Boolean> completeUpload(@PathVariable("assetId") String assetId,
                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        String status = assetService.completeUpload(userId, Long.parseLong(assetId));
        return success("ACCEPTED".equals(status));
    }

    @PostMapping("/{assetId}/download-tickets")
    @Operation(summary = "申请一次性下载票据：对象级权限（所有者或公开展示授权）+ 扫描门禁")
    public CommonResult<AppAssetDownloadTicketRespVO> createDownloadTicket(
            @PathVariable("assetId") String assetId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var ticket = assetService.requestDownloadTicket(userId, Long.parseLong(assetId));
        AppAssetDownloadTicketRespVO vo = new AppAssetDownloadTicketRespVO();
        vo.setTicketId(ticket.getToken());
        vo.setDownloadUrl("pending-ticket-consumption");
        vo.setExpiresAt(LocalDateTime.ofInstant(ticket.getExpiresAt(), ZoneOffset.UTC));
        return success(vo);
    }

    @PostMapping("/{assetId}/downloads")
    @Operation(summary = "凭一次性票据换短期签名下载 URL（公开授权撤回后立即拒绝）")
    public CommonResult<Map<String, Object>> resolveDownload(
            @PathVariable("assetId") String assetId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        String url = assetService.resolveDownloadTicket(userId, Long.parseLong(assetId), body.get("ticket"));
        return success(Map.of("downloadUrl", url, "expiresInSeconds", 300));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

}
