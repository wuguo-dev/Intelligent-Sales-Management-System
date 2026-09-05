package com.haowugou.application.importbatch;

import com.haowugou.application.importbatch.exception.ImportBatchNotFoundException;
import com.haowugou.application.importbatch.exception.InvalidImportBatchQueryException;
import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportBatchDetail;
import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import com.haowugou.domain.importbatch.ImportBatchQueryRepository;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.time.LocalDate;
import java.util.Objects;

/** 门店范围导入批次查询的应用用例：批次列表与批次详情（含问题行）。 */
public final class ImportBatchQuery {

    /** 与商品查询一致的每页上限。 */
    static final int MAX_PAGE_SIZE = 100;

    private final StoreRepository storeRepository;
    private final ImportBatchQueryRepository batchRepository;

    public ImportBatchQuery(
            StoreRepository storeRepository,
            ImportBatchQueryRepository batchRepository) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.batchRepository = Objects.requireNonNull(batchRepository);
    }

    public ImportBatchPageResult listBatches(ImportBatchQueryCriteria criteria) {
        if (criteria == null) {
            throw new InvalidImportBatchQueryException("查询条件不能为空");
        }
        requirePositive(criteria.storeId(), "门店ID");
        validatePaging(criteria.page(), criteria.size());
        validateDateRange(criteria.dataDateFrom(), criteria.dataDateTo());
        Store store = requireActiveStore(criteria.storeId());
        return new ImportBatchPageResult(store, batchRepository.findPage(criteria));
    }

    /**
     * 查批次详情与问题行。
     *
     * <p>批次不存在与批次属于其他门店走同一个异常：对外都是 404，不泄露其他门店批次的存在。
     */
    public ImportBatchDetailResult findBatch(long storeId, long batchId, int page, int size) {
        requirePositive(storeId, "门店ID");
        requirePositive(batchId, "批次ID");
        validatePaging(page, size);
        Store store = requireActiveStore(storeId);
        ImportBatchDetail batch = batchRepository.findDetail(storeId, batchId)
                .orElseThrow(() -> new ImportBatchNotFoundException(storeId, batchId));
        return new ImportBatchDetailResult(
                store, batch, batchRepository.findProblemRows(storeId, batchId, page, size));
    }

    private Store requireActiveStore(long storeId) {
        return storeRepository.findActiveById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
    }

    private void requirePositive(long value, String label) {
        if (value <= 0) {
            throw new InvalidImportBatchQueryException(label + "必须大于0");
        }
    }

    private void validatePaging(int page, int size) {
        if (page < 0) {
            throw new InvalidImportBatchQueryException("页码不能小于0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidImportBatchQueryException("每页数量必须在1到" + MAX_PAGE_SIZE + "之间");
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidImportBatchQueryException("开始日期不能晚于结束日期");
        }
    }
}
