package com.haowugou.application.importbatch;

import com.haowugou.domain.importbatch.ImportBatchDetail;
import com.haowugou.domain.importbatch.ImportBatchListItem;
import com.haowugou.domain.importbatch.ImportBatchProblemRow;
import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import com.haowugou.domain.importbatch.ImportBatchQueryRepository;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.importbatch.ImportType;
import com.haowugou.domain.pagination.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批次查询端口的内存替身：记录收到的参数，按开关返回固定数据或断言失败。
 *
 * <p>查询与撤销两个用例都要它，所以单独成类而不是各自的匿名内部类。
 */
class ImportBatchRepositoryStub implements ImportBatchQueryRepository {

    static final long STORE_ID = 1L;
    static final long BATCH_ID = 88L;
    static final LocalDate DATA_DATE = LocalDate.of(2026, 8, 20);
    static final LocalDateTime IMPORTED_AT = LocalDateTime.of(2026, 8, 20, 9, 30);

    ImportBatchQueryCriteria criteria;
    Long detailStoreId;
    Long problemRowBatchId;

    /** 详情是否存在；false 模拟批次属于其他门店。 */
    boolean detailPresent = true;

    /** 详情返回的状态，用于驱动 {@code reversible()}。 */
    ImportBatchStatus status = ImportBatchStatus.POSTED;

    /** true 时任何访问都断言失败，用于验证校验早于查询。 */
    boolean failOnAccess;

    @Override
    public PageResult<ImportBatchListItem> findPage(ImportBatchQueryCriteria criteria) {
        failIfUnexpected();
        this.criteria = criteria;
        return new PageResult<>(List.of(listItem()), criteria.page(), criteria.size(), 1, 1);
    }

    @Override
    public Optional<ImportBatchDetail> findDetail(long storeId, long batchId) {
        failIfUnexpected();
        this.detailStoreId = storeId;
        return detailPresent ? Optional.of(detail()) : Optional.empty();
    }

    @Override
    public PageResult<ImportBatchProblemRow> findProblemRows(
            long storeId, long batchId, int page, int size) {
        failIfUnexpected();
        this.problemRowBatchId = batchId;
        return new PageResult<>(
                List.of(new ImportBatchProblemRow(7L, "6901234567890", "INVALID", "库存数量不是数字")),
                page, size, 1, 1);
    }

    ImportBatchListItem listItem() {
        return new ImportBatchListItem(
                BATCH_ID, STORE_ID, ImportType.DAILY_SALES, status, DATA_DATE,
                "销售汇总.xls", 120, 118, 2, IMPORTED_AT, IMPORTED_AT, null);
    }

    ImportBatchDetail detail() {
        return new ImportBatchDetail(
                BATCH_ID, STORE_ID, ImportType.DAILY_SALES, status, DATA_DATE,
                "销售汇总.xls", "a".repeat(64), 120, 118, 2, null, "张三",
                IMPORTED_AT, IMPORTED_AT, null, null, null);
    }

    private void failIfUnexpected() {
        if (failOnAccess) {
            throw new AssertionError("校验未通过时不应查询批次");
        }
    }
}
