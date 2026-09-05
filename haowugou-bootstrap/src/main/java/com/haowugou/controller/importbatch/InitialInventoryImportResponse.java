package com.haowugou.controller.importbatch;

import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.importbatch.ImportRowError;
import java.util.List;

/**
 * 初始库存导入的 HTTP 响应模型：批次主键、终态与行数摘要。
 *
 * @param batchId 批次主键（POSTED 或 FAILED 均返回）
 * @param status 终态：POSTED / FAILED
 * @param totalRows 原始数据行数
 * @param successRows 成功行数（FAILED 时恒为 0）
 * @param errorRows 错误行数（POSTED 时恒为 0）
 * @param errors 行级错误明细，最多返回 50 条
 */
public record InitialInventoryImportResponse(
        long batchId,
        String status,
        int totalRows,
        int successRows,
        int errorRows,
        List<RowError> errors) {

    private static final int MAX_ERRORS = 50;

    static InitialInventoryImportResponse from(ImportBatchResult result) {
        return new InitialInventoryImportResponse(
                result.batchId(),
                result.status(),
                result.totalRows(),
                result.successRows(),
                result.errorRows(),
                result.errors().stream()
                        .limit(MAX_ERRORS)
                        .map(RowError::from)
                        .toList());
    }

    /** 行级错误条目：Excel 行号、条码与原因。 */
    public record RowError(long rowNumber, String barcode, String message) {

        static RowError from(ImportRowError error) {
            return new RowError(error.rowNumber(), error.barcode(), error.message());
        }
    }
}
