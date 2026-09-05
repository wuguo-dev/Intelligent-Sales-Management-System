package com.haowugou.controller.importbatch;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.application.importbatch.ReverseImportBatchResult;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 撤销批次的 HTTP 响应模型。
 *
 * <p>不带 {@code status} 字段：能返回 200 就说明批次已是 REVERSED。
 *
 * @param store 批次所属门店
 * @param batchId 被撤销的批次主键
 * @param importType INITIAL_INVENTORY / DAILY_SALES
 * @param dataDate 数据归属日期
 * @param fileName 原始文件名
 * @param reversedMovements 写入的反向流水条数
 * @param restoredProducts 库存被回滚的商品数
 * @param reversedAt 撤销时间
 * @param reversedBy 撤销操作人
 * @param reversedReason 撤销原因
 */
public record ReverseImportBatchResponse(
        ImportStoreResponse store,
        @JsonSerialize(using = ToStringSerializer.class) long batchId,
        String importType,
        LocalDate dataDate,
        String fileName,
        int reversedMovements,
        int restoredProducts,
        LocalDateTime reversedAt,
        String reversedBy,
        String reversedReason) {

    static ReverseImportBatchResponse from(ReverseImportBatchResult result) {
        return new ReverseImportBatchResponse(
                ImportStoreResponse.from(result.store()),
                result.batchId(),
                result.importType().name(),
                result.dataDate(),
                result.fileName(),
                result.reversedMovements(),
                result.restoredProducts(),
                result.reversedAt(),
                result.reversedBy(),
                result.reversedReason());
    }
}
