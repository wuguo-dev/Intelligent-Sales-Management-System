package com.haowugou.application.importbatch;

import com.haowugou.domain.importbatch.ImportType;
import com.haowugou.domain.store.Store;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 撤销成功的应用结果。不带状态字段——能返回本记录就意味着批次已是 REVERSED。
 *
 * @param reversedMovements 写入的反向流水条数
 * @param restoredProducts  库存被回滚的商品数
 */
public record ReverseImportBatchResult(
        Store store,
        long batchId,
        ImportType importType,
        LocalDate dataDate,
        String fileName,
        int reversedMovements,
        int restoredProducts,
        LocalDateTime reversedAt,
        String reversedBy,
        String reversedReason) {
}
