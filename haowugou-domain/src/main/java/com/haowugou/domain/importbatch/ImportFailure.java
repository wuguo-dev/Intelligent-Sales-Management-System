package com.haowugou.domain.importbatch;

import java.time.LocalDate;
import java.util.List;

/** 校验失败的批次载荷：批次记 FAILED，原始行按行级错误记 VALID/INVALID，不产生任何库存变化。 */
public record ImportFailure(
        long storeId,
        String fileName,
        String fileHash,
        LocalDate dataDate,
        List<ImportFailureRow> rows,
        String errorSummary) {
}
