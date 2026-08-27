package com.haowugou.domain.importbatch;

import java.util.List;

/** 导入用例的返回结果：批次主键、终态与行数摘要。 */
public record ImportBatchResult(
        long batchId,
        String status,
        int totalRows,
        int successRows,
        int errorRows,
        List<ImportRowError> errors) {

    public static final String STATUS_POSTED = "POSTED";
    public static final String STATUS_FAILED = "FAILED";
}
