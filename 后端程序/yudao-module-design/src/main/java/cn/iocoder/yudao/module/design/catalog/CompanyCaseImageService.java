package cn.iocoder.yudao.module.design.catalog;

import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.AssetTypePolicy;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/** 编排已有资产扫描、案例版本与授权；扫描不占用案例数据库锁。 */
@Service
public class CompanyCaseImageService {
    private final CaseCatalogService catalog;
    private final AssetService assets;
    private final RightsGrantService rights;
    private final TransactionTemplate tx;

    public CompanyCaseImageService(CaseCatalogService catalog, AssetService assets, RightsGrantService rights, PlatformTransactionManager manager) {
        this.catalog = catalog; this.assets = assets; this.rights = rights; this.tx = new TransactionTemplate(manager);
    }

    public record Uploaded(String assetId, long version) {}

    public Uploaded upload(long caseId, long adminId, long version, String role, Integer floorNo,
                           String mime, byte[] content, boolean publicDisplay, boolean generationReference) {
        if (!publicDisplay || mime == null || content == null || content.length == 0 || content.length > AssetTypePolicy.CASE_IMAGE.maxBytes()
                || !AssetTypePolicy.CASE_IMAGE.mimeAllowed(mime))
            throw new IllegalArgumentException("请确认图片公开展示权利，并上传不超过20MB的JPG或PNG图片");
        catalog.requireImageEditable(caseId, version, role, floorNo);
        long assetId = assets.uploadCompanyImage(adminId, mime, content);
        return tx.execute(status -> {
            long nextVersion = catalog.replaceCompanyImage(caseId, adminId, version, assetId, role, floorNo);
            rights.createGrant(adminId, assetId, "PUBLIC_DISPLAY", "公司案例上传确认", "*", "公司案例公开展示", Instant.now(), null);
            if (generationReference)
                rights.createGrant(adminId, assetId, "GENERATION_REFERENCE", "公司案例上传确认", "*", "设计生成参考", Instant.now(), null);
            return new Uploaded(String.valueOf(assetId), nextVersion);
        });
    }
}
