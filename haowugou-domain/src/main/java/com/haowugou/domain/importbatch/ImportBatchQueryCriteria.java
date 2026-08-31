package com.haowugou.domain.importbatch;

import java.time.LocalDate;

/** 导入批次分页查询条件，必须携带门店标识（架构规范 §9）。 */
public record ImportBatchQueryCriteria(
        long storeId,
        ImportType importType,
        ImportBatchStatus status,
        LocalDate dataDateFrom,
        LocalDate dataDateTo,
        int page,
        int size) {
}
