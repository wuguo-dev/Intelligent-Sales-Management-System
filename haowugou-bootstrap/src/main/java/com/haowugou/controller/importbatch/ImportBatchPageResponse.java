package com.haowugou.controller.importbatch;

import com.haowugou.application.importbatch.ImportBatchPageResult;
import java.util.List;

/**
 * 批次分页列表的 HTTP 响应模型。
 *
 * @param store 查询范围所属门店
 * @param items 当前页批次列表
 * @param page 当前页码，从 0 开始
 * @param size 每页数量
 * @param totalElements 符合筛选条件的批次总数
 * @param totalPages 总页数
 */
public record ImportBatchPageResponse(
        ImportStoreResponse store,
        List<ImportBatchItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static ImportBatchPageResponse from(ImportBatchPageResult result) {
        return new ImportBatchPageResponse(
                ImportStoreResponse.from(result.store()),
                result.batches().items().stream()
                        .map(ImportBatchItemResponse::from)
                        .toList(),
                result.batches().page(),
                result.batches().size(),
                result.batches().totalElements(),
                result.batches().totalPages());
    }
}
