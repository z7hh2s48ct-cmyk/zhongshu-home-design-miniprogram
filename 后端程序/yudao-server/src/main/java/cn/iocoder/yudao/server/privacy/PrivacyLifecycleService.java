package cn.iocoder.yudao.server.privacy;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.*;
import cn.iocoder.yudao.module.infra.zhongshu.audit.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;

/** Cross-domain privacy orchestration; ledger evidence is retained and never silently erased. */
@Slf4j @Service
public class PrivacyLifecycleService {
    private static final int MAX_BYTES=8*1024*1024, MAX_ROWS=10000;
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    private final ObjectStoragePort storage; private final DeliveryPort delivery; private final UserSessionService sessions;
    private final AuditPort audit;
    @Value("${zhongshu.privacy.export-worker-enabled:true}") private boolean enabled;
    @Value("${zhongshu.privacy.retention-policy-version:}") private String policyVersion;
    public PrivacyLifecycleService(DataSource ds,PlatformTransactionManager manager,ObjectStoragePort storage,DeliveryPort delivery,UserSessionService sessions,AuditPort audit) {
        jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(15);tx=new TransactionTemplate(manager);this.storage=storage;this.delivery=delivery;this.sessions=sessions;
        this.audit=audit;
    }
    @Scheduled(fixedDelay=15000,initialDelay=30000)
    public void tick() {
        if(!enabled)return;
        try {
            for(var id:jdbc.queryForList("SELECT id FROM data_subject_request WHERE request_type='EXPORT' AND deleted=FALSE "
                    + "AND (status='PENDING' OR (status='PROCESSING' AND (lease_expires_at IS NULL OR lease_expires_at<now()))) ORDER BY id LIMIT 3",Long.class)) exportOne(id);
        }catch(Exception e){log.warn("[privacy-export] tick failureType={}",e.getClass().getSimpleName());}
    }
    public void exportOne(long id) {
        String token=UUID.randomUUID().toString();
        try {
            var rows=jdbc.queryForList("UPDATE data_subject_request SET status='PROCESSING',lease_token=?,lease_expires_at=now()+interval '2 minutes',attempts=attempts+1 "
                    + "WHERE id=? AND request_type='EXPORT' AND deleted=FALSE AND (status='PENDING' OR (status='PROCESSING' AND (lease_expires_at IS NULL OR lease_expires_at<now()))) RETURNING user_id,attempts",token,id);
            if(rows.isEmpty())return;
            if(((Number)rows.get(0).get("attempts")).intValue()>5)throw new Limit("EXPORT_RETRY_LIMIT");
            long user=((Number)rows.get(0).get("user_id")).longValue();
            byte[] bytes=personalJson(user,id,token);
            String key="privacy/"+id+"/"+token+".json";storage.putObject(key,bytes);
            jdbc.update("UPDATE data_subject_request SET status='COMPLETED',completed_at=now(),file_key=?,file_sha256=?,file_expires_at=now()+interval '1 day', "
                    + "lease_token=NULL,lease_expires_at=NULL,error_code=NULL WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_expires_at>now()",
                    key,sha(bytes),id,token);
        }catch(Exception e){jdbc.update("UPDATE data_subject_request SET status='REJECTED',completed_at=now(),error_code=?,lease_token=NULL,lease_expires_at=NULL "
                + "WHERE id=? AND status='PROCESSING' AND lease_token=?",e instanceof Limit?e.getMessage():"EXPORT_FAILED",id,token);
            log.warn("[privacy-export] request={} failureType={}",id,e.getClass().getSimpleName());}
    }
    private record Dataset(String name,String select,String from) { }
    private byte[] personalJson(long user,long requestId,String token) throws IOException {
        var sets=List.of(
            new Dataset("profile","a.id,a.status,a.nickname,a.avatar,a.create_time","account a WHERE a.id=? AND a.deleted=FALSE"),
            new Dataset("consents","a.id,a.policy_type,a.version,a.accepted_at","privacy_consent a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("wechatIdentity","a.id,a.appid,a.openid,a.unionid,a.create_time","wechat_identity a WHERE a.account_id=? AND a.deleted=FALSE"),
            new Dataset("authorizations","a.id,a.status,a.granted_at,a.revoked_at","design_access_grant a WHERE a.account_id=? AND a.deleted=FALSE"),
            new Dataset("privacyRequests","a.id,a.request_type,a.status,a.create_time,a.completed_at","data_subject_request a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("projects","a.id,a.source_type,a.stage,a.status,a.create_time","design_project a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("requirements","a.id,a.project_id,a.inputs::text,a.sketch_asset_id,a.create_time","design_requirement_snapshot a JOIN design_project p ON p.id=a.project_id WHERE p.user_id=? AND a.deleted=FALSE AND p.deleted=FALSE"),
            new Dataset("orders","a.id,a.order_no,a.amount_cents,a.base_points,a.bonus_points,a.payment_state,a.fulfillment_state,a.create_time","recharge_order a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("refunds","a.id,a.order_id,a.amount_cents,a.channel_state,a.point_reversal_state,a.reason,a.create_time","refund_order a JOIN recharge_order o ON o.id=a.order_id WHERE o.user_id=? AND a.deleted=FALSE"),
            new Dataset("generationJobs","a.id,a.phase,a.status,a.requested_count,a.accepted_count,a.total_point_cost,a.create_time","ai_job a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("resultVersions","a.id,a.project_id,a.version,a.config_snapshot::text,a.notes,a.create_time","design_result_version a JOIN design_project p ON p.id=a.project_id WHERE p.user_id=? AND a.deleted=FALSE"),
            new Dataset("budgets","a.id,a.project_id,a.result_version_id,a.input_snapshot::text,a.total_low_cents,a.total_high_cents,a.create_time","budget_estimate a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("userBudgetRevisions","a.id,a.estimate_id,a.revision_no,a.input_snapshot::text,a.completeness,a.total_cents,a.create_time","budget_revision a JOIN budget_estimate e ON e.id=a.estimate_id WHERE e.user_id=? AND a.actor_type='USER' AND a.deleted=FALSE"),
            new Dataset("publishedQuotes","a.id,a.estimate_id,a.quote_version,a.final_price_cents,a.published_at","budget_quote a JOIN budget_estimate e ON e.id=a.estimate_id WHERE e.user_id=? AND a.status='PUBLISHED' AND a.deleted=FALSE"),
            new Dataset("favorites","a.id,a.case_id,a.create_time","case_favorite a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("pointLedger","a.id,a.type,a.delta,a.available_after,a.reserved_after,a.biz_type,a.biz_id,a.create_time","design_point_ledger a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("submissions","a.id,a.project_id,a.status,a.published_case_id,a.create_time","case_submission a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("messages","a.id,a.message_type,a.title,a.content,a.create_time","user_message a WHERE a.user_id=? AND a.deleted=FALSE"),
            new Dataset("assetInventory","a.id,a.asset_type,a.declared_mime,a.upload_status,a.stored_sha256,a.stored_size,a.create_time","asset a WHERE a.owner_user_id=? AND a.source_type IN ('USER_UPLOAD','AI_GENERATED') AND a.deleted=FALSE"));
        var buffer=new ByteArrayOutputStream();
        var bounded=new FilterOutputStream(buffer){
            @Override public void write(int b)throws IOException{if(buffer.size()>=MAX_BYTES)throw new Limit("EXPORT_SIZE_LIMIT");buffer.write(b);}
            @Override public void write(byte[] b,int o,int n)throws IOException{if((long)buffer.size()+n>MAX_BYTES)throw new Limit("EXPORT_SIZE_LIMIT");buffer.write(b,o,n);}
        };
        int total=0;
        try(var json=new com.fasterxml.jackson.databind.ObjectMapper().getFactory().createGenerator(bounded)){
            json.writeStartObject();json.writeStringField("format","zhongshu-personal-records-v1");
            json.writeStringField("scope","本人资料及业务记录；图片清单不包含图片字节，图片可在原项目下载。账务和审计资料按运营方确认的留存规则处理。");
            for(var set:sets){json.writeArrayFieldStart(set.name());long cursor=0;
                while(true){
                    if(jdbc.update("UPDATE data_subject_request SET lease_expires_at=now()+interval '2 minutes' WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_expires_at>now()",requestId,token)!=1)throw new Limit("EXPORT_LEASE_LOST");
                    var page=jdbc.queryForList("SELECT "+set.select()+" FROM "+set.from()+" AND a.id>? ORDER BY a.id LIMIT 20",user,cursor);
                    for(var row:page){if(++total>MAX_ROWS)throw new Limit("EXPORT_ROW_LIMIT");cursor=((Number)row.get("id")).longValue();
                        var safe=new LinkedHashMap<String,Object>();for(var entry:row.entrySet()){
                            Object value=entry.getValue();if(value instanceof Timestamp time)value=time.toInstant().toString();
                            else if(value instanceof Number && (entry.getKey().equals("id")||entry.getKey().endsWith("_id")))value=value.toString();
                            safe.put(entry.getKey(),value);
                        }json.writeObject(safe);
                    }if(page.size()<20)break;
                }json.writeEndArray();
            }json.writeEndObject();
        }return buffer.toByteArray();
    }
    private Map<String,Object> requireFile(long user,long id) {
        var rows=jdbc.queryForList("SELECT file_key,file_sha256 FROM data_subject_request WHERE id=? AND user_id=? AND request_type='EXPORT' "
                + "AND status='COMPLETED' AND file_expires_at>now() AND deleted=FALSE",id,user);
        if(rows.isEmpty())throw new ServiceException(403,"导出不存在、未完成或已过期");return rows.get(0);
    }
    public IssuedTicket ticket(long user,long id){requireFile(user,id);return delivery.issueDownloadTicket("PERSONAL_DATA",String.valueOf(id),user,600);}
    public byte[] download(long user,long id,String token)throws IOException {
        var row=requireFile(user,id);
        if(delivery.consumeOwnedDownloadTicket(token,user,"PERSONAL_DATA",String.valueOf(id)).getOutcome()!=TicketConsumption.Outcome.CONSUMED_NOW)throw new ServiceException(403,"下载票据无效或已使用");
        try(var in=storage.getObject((String)row.get("file_key"))){byte[] bytes=in.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES||!sha(bytes).equals(row.get("file_sha256")))throw new ServiceException(409,"文件校验失败，请重新申请导出");return bytes;}
    }
    public cn.iocoder.yudao.framework.common.pojo.PageResult<Map<String,Object>> pageRequests(int pageNo,int pageSize){
        int size=Math.max(1,Math.min(100,pageSize));
        var rows=jdbc.queryForList("SELECT id::text AS \"requestId\",user_id::text AS \"userId\",request_type AS \"requestType\",status,create_time AS \"createdAt\",completed_at AS \"completedAt\",error_code AS \"errorCode\" FROM data_subject_request WHERE deleted=FALSE ORDER BY CASE WHEN status IN ('PENDING','PROCESSING') THEN 0 ELSE 1 END,id DESC LIMIT ? OFFSET ?",size,(long)Math.max(0,pageNo-1)*size);
        return new cn.iocoder.yudao.framework.common.pojo.PageResult<>(rows,jdbc.queryForObject("SELECT count(*) FROM data_subject_request WHERE deleted=FALSE",Long.class));
    }
    public void decide(long admin,long id,boolean approve,String reason){
        if(reason==null||reason.isBlank()||reason.length()>512)throw new ServiceException(400,"请填写处理说明（最多512字）");
        tx.executeWithoutResult(state->{
            // Same lock order as request creation and new charge/project creation: account -> business.
            var owners=jdbc.queryForList("SELECT user_id FROM data_subject_request WHERE id=? AND deleted=FALSE",Long.class,id);
            if(owners.isEmpty())throw new ServiceException(403,"请求不存在");
            Long user=owners.get(0);
            var accounts=jdbc.queryForList("SELECT status FROM account WHERE id=? AND deleted=FALSE FOR UPDATE",String.class,user);
            var rows=jdbc.queryForList("SELECT request_type,status FROM data_subject_request WHERE id=? AND deleted=FALSE FOR UPDATE",id);
            if(rows.isEmpty()||accounts.isEmpty())throw new ServiceException(403,"请求不存在");
            var row=rows.get(0);if(List.of("COMPLETED","REJECTED").contains(row.get("status")))return;
            if(!"CLOSE_ACCOUNT".equals(row.get("request_type")))throw new ServiceException(409,"导出请求由导出任务处理");
            if(approve){
                if(policyVersion==null||!policyVersion.matches("[A-Za-z0-9_.-]{1,64}"))throw new ServiceException(409,"尚未配置已确认的数据留存政策版本");
                if(!"ACTIVE".equals(accounts.get(0)))throw new ServiceException(409,"账号状态已变化，请核对");
                var balances=jdbc.queryForList("SELECT available_points,reserved_points FROM design_point_account WHERE user_id=? FOR UPDATE",user);
                if(balances.stream().anyMatch(b->((Number)b.get("available_points")).longValue()!=0||((Number)b.get("reserved_points")).longValue()!=0))throw new ServiceException(409,"账号仍有设计点或预留点，请先完成退款与结算");
                long open=jdbc.queryForObject("SELECT count(*) FROM recharge_order WHERE user_id=? AND deleted=FALSE AND (payment_state IN ('CREATED','PENDING','UNKNOWN') OR (payment_state='SUCCEEDED' AND fulfillment_state<>'CREDITED'))",Long.class,user)
                        +jdbc.queryForObject("SELECT count(*) FROM refund_order r JOIN recharge_order o ON o.id=r.order_id WHERE o.user_id=? AND r.deleted=FALSE AND r.channel_state IN ('CREATED','PROCESSING','UNKNOWN')",Long.class,user)
                        +jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE user_id=? AND deleted=FALSE AND status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED')",Long.class,user);
                if(open>0)throw new ServiceException(409,"账号仍有未完成的订单、退款或生成任务");
                jdbc.update("UPDATE account SET status='CLOSED',nickname=NULL,avatar=NULL,preferences=NULL,update_time=now() WHERE id=?",user);
                sessions.revokeAllForAccount(user);
                jdbc.update("UPDATE design_access_grant SET status='REVOKED',revoked_at=now(),revoked_by=?,update_time=now() WHERE account_id=? AND status='ACTIVE'",String.valueOf(admin),user);
                jdbc.update("UPDATE design_project SET status='ARCHIVED',update_time=now() WHERE user_id=? AND deleted=FALSE",user);
                jdbc.update("UPDATE design_case SET publication_status='OFFLINE',update_time=now() WHERE id IN (SELECT published_case_id FROM case_submission WHERE user_id=? AND deleted=FALSE)",user);
                jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN',withdrawn_at=now(),rights_version=rights_version+1 WHERE status='ACTIVE' AND asset_id IN (SELECT id FROM asset WHERE owner_user_id=? AND source_type IN ('USER_UPLOAD','AI_GENERATED') AND deleted=FALSE)",user);
            }
            jdbc.update("UPDATE data_subject_request SET status=?,completed_at=now(),decision_reason=?,reviewed_by=?,retention_policy_version=? WHERE id=?",approve?"COMPLETED":"REJECTED",reason,admin,approve?policyVersion:null,id);
            audit.record(AuditEventMessage.builder().eventType("PRIVACY_REQUEST_DECIDED").actorType(AuditEventMessage.ActorType.ADMIN)
                    .actorId(String.valueOf(admin)).action("DECIDE").bizType("data_subject_request").bizId(String.valueOf(id))
                    .result(AuditEventMessage.AuditResult.SUCCESS).detail(Map.of("approved",approve,"retentionPolicyVersion",approve?policyVersion:"")).build());
        });
    }
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static class Limit extends RuntimeException{Limit(String message){super(message);}}
}
