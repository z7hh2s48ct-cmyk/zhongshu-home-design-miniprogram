package cn.iocoder.yudao.module.identity.controller.app.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AppProfileUpdateReqVO {
    @NotBlank
    @Size(max = 32)
    private String nickname;
    /** Omitted means keep the existing avatar. */
    private String avatarAssetId;
}
