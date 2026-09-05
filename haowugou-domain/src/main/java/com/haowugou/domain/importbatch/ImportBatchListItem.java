package com.haowugou.domain.importbatch;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 批次列表行：足够判断「导了什么、结果如何、能不能撤销」，不含行级明细。 */
public record ImportBatchListItem(
        long batchId,
        long storeId,
        ImportType importType,
        ImportBatchStatus status,
        LocalDate dataDate,
        String fileName,
        int totalRows,
        int successRows,
        int errorRows,
        LocalDateTime importedAt,
        LocalDateTime postedAt,
        LocalDateTime reversedAt) {

    /** 该批次当前是否可撤销。 */
    public boolean reversible() {
        return status.reversible();
    }
}
