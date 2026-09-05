package com.haowugou.application.importbatch;

import com.haowugou.domain.importbatch.ImportBatchDetail;
import com.haowugou.domain.importbatch.ImportBatchProblemRow;
import com.haowugou.domain.pagination.PageResult;
import com.haowugou.domain.store.Store;

/** 带门店上下文的批次详情应用结果，问题行单独分页。 */
public record ImportBatchDetailResult(
        Store store,
        ImportBatchDetail batch,
        PageResult<ImportBatchProblemRow> problemRows) {
}
