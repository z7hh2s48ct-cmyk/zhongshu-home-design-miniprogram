package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import java.time.Instant;
import java.util.*;

/** Shared input contract; unknown filters must never silently widen an export. */
public final class ExportFilters {
    private ExportFilters() { }
    public static Map<String, Object> validate(Map<String, Object> source) {
        if (source == null) throw invalid();
        var result = new LinkedHashMap<String,Object>();
        var allowed = Set.of("jobType", "userId", "type", "from", "to", "runId");
        for (var entry : source.entrySet()) {
            if (!allowed.contains(entry.getKey())) throw invalid();
            if (entry.getValue() != null && !entry.getValue().toString().isBlank()) result.put(entry.getKey(), entry.getValue().toString());
        }
        if (result.get("jobType") == null || !Set.of("POINT_LEDGER", "AUDIT_EVENTS").contains(result.get("jobType"))) throw invalid();
        if (result.containsKey("userId")) {
            if (!"POINT_LEDGER".equals(result.get("jobType"))) throw invalid();
            try { if (Long.parseLong(result.get("userId").toString()) <= 0) throw invalid(); } catch (NumberFormatException e) { throw invalid(); }
        }
        if (result.containsKey("type") && !result.get("type").toString().matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
        try {
            Instant from = result.containsKey("from") ? Instant.parse(result.get("from").toString()) : null;
            Instant to = result.containsKey("to") ? Instant.parse(result.get("to").toString()) : null;
            if (from != null && to != null && !from.isBefore(to)) throw invalid();
        } catch (java.time.format.DateTimeParseException e) { throw invalid(); }
        if (result.containsKey("runId") && result.get("runId").toString().length() > 128) throw invalid();
        return Map.copyOf(result);
    }
    private static ServiceException invalid() { return new ServiceException(400, "导出类型或筛选条件无效"); }
}
