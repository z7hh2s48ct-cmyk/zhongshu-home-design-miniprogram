package cn.iocoder.yudao.server.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.privacy.PrivacyService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.server.privacy.PrivacyLifecycleService;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController @RequestMapping("/design/v1/privacy-requests") @PermitAll
public class AppPrivacyLifecycleController {
    private final PrivacyService privacy; private final PrivacyLifecycleService lifecycle; private final UserSessionService sessions;
    public AppPrivacyLifecycleController(PrivacyService privacy,PrivacyLifecycleService lifecycle,UserSessionService sessions){this.privacy=privacy;this.lifecycle=lifecycle;this.sessions=sessions;}
    private long user(String authorization){String token=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):authorization;
        return sessions.validateAccessToken(token).orElseThrow(()->new cn.iocoder.yudao.framework.common.exception.ServiceException(401,"会话无效或已过期")).accountId();}
    @GetMapping public CommonResult<List<Map<String,Object>>> list(@RequestHeader(value="Authorization",required=false)String authorization){return success(privacy.listRequests(user(authorization)));}
    @PostMapping("/{id}/download-tickets") public CommonResult<Map<String,Object>> ticket(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String authorization){var ticket=lifecycle.ticket(user(authorization),id);return success(Map.of("ticket",ticket.getToken(),"expiresAt",ticket.getExpiresAt().toString()));}
    @GetMapping("/{id}/content") public ResponseEntity<byte[]> download(@PathVariable long id,@RequestParam String ticket,@RequestHeader(value="Authorization",required=false)String authorization)throws java.io.IOException {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=personal-data-"+id+".json")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_OCTET_STREAM).body(lifecycle.download(user(authorization),id,ticket));
    }
}
