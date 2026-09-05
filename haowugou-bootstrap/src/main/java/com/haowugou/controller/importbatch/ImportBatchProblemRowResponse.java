package com.haowugou.controller.importbatch;

import com.haowugou.domain.importbatch.ImportBatchProblemRow;

/**
 * 批次问题行条目：定位到 Excel 原始行。
 *
 * @param rowNumber Excel 原始行号
 * @param barcode 该行解析出的条码；未解析出为 null
 * @param parseStatus INVALID / WARNING / PENDING
 * @param errorMessage 行级错误或警告
 */
public record ImportBatchProblemRowResponse(
        long rowNumber,
        String barcode,
        String parseStatus,
        String errorMessage) {

    static ImportBatchProblemRowResponse from(ImportBatchProblemRow row) {
        return new ImportBatchProblemRowResponse(
                row.rowNumber(), row.barcode(), row.parseStatus(), row.errorMessage());
    }
}
