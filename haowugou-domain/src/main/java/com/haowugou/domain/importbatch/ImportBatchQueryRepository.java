package com.haowugou.domain.importbatch;

import com.haowugou.domain.pagination.PageResult;
import java.util.Optional;

/**
 * 导入批次查询的持久化边界（只读），两条导入链路共用。
 *
 * <p>所有方法都以门店为范围：{@code import_raw_row} 的外键只有 {@code batch_id}，
 * 实现必须 join {@code import_batch} 并带上 {@code store_id}，否则能跨门店读到别人的批次
 * （架构规范 §9）。
 */
public interface ImportBatchQueryRepository {

    /** 按条件分页查该门店的导入批次，按导入时间倒序。 */
    PageResult<ImportBatchListItem> findPage(ImportBatchQueryCriteria criteria);

    /** 查批次详情；批次不存在或不属于该门店时返回空。 */
    Optional<ImportBatchDetail> findDetail(long storeId, long batchId);

    /** 分页查该批次的问题行（INVALID/WARNING），按行号升序。 */
    PageResult<ImportBatchProblemRow> findProblemRows(long storeId, long batchId, int page, int size);
}
