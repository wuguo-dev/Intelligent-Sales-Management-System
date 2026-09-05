package com.haowugou.domain.importbatch;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批次详情：列表行的全部字段，外加错误摘要与撤销审计信息。
 *
 * <p>行级明细单独分页查询，不放进本记录——失败批次可能整批都是错误行。
 */
public record ImportBatchDetail(
        long batchId,
        long storeId,
        ImportType importType,
        ImportBatchStatus status,
        LocalDate dataDate,
        String fileName,
        String fileHash,
        int totalRows,
        int successRows,
        int errorRows,
        String errorMessage,
        String operatorName,
        LocalDateTime importedAt,
        LocalDateTime postedAt,
        LocalDateTime reversedAt,
        String reversedBy,
        String reversedReason) {

    /** 该批次当前是否可撤销。 */
    public boolean reversible() {
        return status.reversible();
    }
}
