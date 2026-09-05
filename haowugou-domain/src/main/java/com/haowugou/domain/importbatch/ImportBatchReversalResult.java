package com.haowugou.domain.importbatch;

import java.time.LocalDateTime;

/**
 * 撤销落库结果。
 *
 * @param reversedMovements 写入的 REVERSAL 流水条数，与原流水条数相等
 * @param restoredProducts  库存被回滚的商品数
 */
public record ImportBatchReversalResult(
        long batchId,
        ImportType importType,
        int reversedMovements,
        int restoredProducts,
        LocalDateTime reversedAt) {
}
