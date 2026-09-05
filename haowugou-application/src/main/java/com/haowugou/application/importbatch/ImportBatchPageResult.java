package com.haowugou.application.importbatch;

import com.haowugou.domain.importbatch.ImportBatchListItem;
import com.haowugou.domain.pagination.PageResult;
import com.haowugou.domain.store.Store;

/** 带门店上下文的导入批次分页应用结果。 */
public record ImportBatchPageResult(
        Store store,
        PageResult<ImportBatchListItem> batches) {
}
