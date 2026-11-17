package app.pagefault.visionforge.common.audit;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback store used until the application provides a database-backed one.
 */
@Slf4j
public final class LoggingBizLogStore implements BizLogStore {

    @Override
    public void save(BizLogRecord record) {
        if (record.success()) {
            log.info("biz-log type={} subType={} bizNo={} operator={} detail={} traceId={}",
                    record.type(), record.subType(), record.bizNo(), record.operatorName(),
                    record.detail(), record.traceId());
        } else {
            log.warn("biz-log type={} subType={} bizNo={} operator={} detail={} error={} traceId={}",
                    record.type(), record.subType(), record.bizNo(), record.operatorName(),
                    record.detail(), record.errorMessage(), record.traceId());
        }
    }
}
