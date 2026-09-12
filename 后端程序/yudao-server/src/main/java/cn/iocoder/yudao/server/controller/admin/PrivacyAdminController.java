package cn.iocoder.yudao.server.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.server.privacy.PrivacyLifecycleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController @RequestMapping("/design/v1/privacy-requests")
@PreAuthorize("@ss.hasPermission('identity:privacy:manage')")
public class PrivacyAdminController {
    private final PrivacyLifecycleService service;
    public PrivacyAdminController(PrivacyLifecycleService service){this.service=service;}
    @GetMapping public CommonResult<cn.iocoder.yudao.framework.common.pojo.PageResult<Map<String,Object>>> list(@RequestParam(defaultValue="1")int pageNo,@RequestParam(defaultValue="20")int pageSize){return success(service.pageRequests(pageNo,pageSize));}
    public record Decision(Boolean approve,String reason) { }
    @PostMapping("/{id}/decision") public CommonResult<Boolean> decide(@PathVariable long id,@RequestBody Decision command){
        if(command.approve()==null)throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400,"必须指定处理决定");
        service.decide(SecurityFrameworkUtils.getLoginUserId(),id,command.approve(),command.reason());return success(true);
    }
}
