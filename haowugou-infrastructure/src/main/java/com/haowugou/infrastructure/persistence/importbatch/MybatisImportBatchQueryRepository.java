package com.haowugou.infrastructure.persistence.importbatch;

import com.haowugou.domain.importbatch.ImportBatchDetail;
import com.haowugou.domain.importbatch.ImportBatchListItem;
import com.haowugou.domain.importbatch.ImportBatchProblemRow;
import com.haowugou.domain.importbatch.ImportBatchQueryCriteria;
import com.haowugou.domain.importbatch.ImportBatchQueryRepository;
import com.haowugou.domain.importbatch.ImportBatchStatus;
import com.haowugou.domain.importbatch.ImportType;
import com.haowugou.domain.pagination.PageResult;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 导入批次查询的 MyBatis 实现。
 *
 * <p>列表与问题行都是「先 COUNT 再取当前页」两次查询，总数为 0 时不发第二条语句。
 * 每条语句都带 {@code store_id}，批次属于其他门店时详情返回空，由应用层映射成 404。
 */
@Repository
public class MybatisImportBatchQueryRepository implements ImportBatchQueryRepository {

    private final ImportBatchAdminMapper mapper;

    public MybatisImportBatchQueryRepository(ImportBatchAdminMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<ImportBatchListItem> findPage(ImportBatchQueryCriteria criteria) {
        long totalElements = mapper.countBatches(criteria);
        if (totalElements == 0) {
            return emptyPage(criteria.page(), criteria.size());
        }
        long offset = (long) criteria.page() * criteria.size();
        List<ImportBatchListItem> items = mapper
                .findBatchPage(criteria, offset, criteria.size())
                .stream()
                .map(this::toListItem)
                .toList();
        return page(items, criteria.page(), criteria.size(), totalElements);
    }

    @Override
    public Optional<ImportBatchDetail> findDetail(long storeId, long batchId) {
        return Optional.ofNullable(mapper.findBatchDetail(storeId, batchId)).map(this::toDetail);
    }

    @Override
    public PageResult<ImportBatchProblemRow> findProblemRows(
            long storeId, long batchId, int page, int size) {
        long totalElements = mapper.countProblemRows(storeId, batchId);
        if (totalElements == 0) {
            return emptyPage(page, size);
        }
        long offset = (long) page * size;
        List<ImportBatchProblemRow> items = mapper
                .findProblemRowPage(storeId, batchId, offset, size)
                .stream()
                .map(this::toProblemRow)
                .toList();
        return page(items, page, size, totalElements);
    }

    private ImportBatchListItem toListItem(ImportBatchSummaryRow row) {
        return new ImportBatchListItem(
                row.getBatchId(),
                row.getStoreId(),
                ImportType.valueOf(row.getImportType()),
                ImportBatchStatus.valueOf(row.getStatus()),
                row.getDataDate(),
                row.getFileName(),
                (int) row.getTotalRows(),
                (int) row.getSuccessRows(),
                (int) row.getErrorRows(),
                row.getImportedAt(),
                row.getPostedAt(),
                row.getReversedAt());
    }

    private ImportBatchDetail toDetail(ImportBatchSummaryRow row) {
        return new ImportBatchDetail(
                row.getBatchId(),
                row.getStoreId(),
                ImportType.valueOf(row.getImportType()),
                ImportBatchStatus.valueOf(row.getStatus()),
                row.getDataDate(),
                row.getFileName(),
                row.getFileHash(),
                (int) row.getTotalRows(),
                (int) row.getSuccessRows(),
                (int) row.getErrorRows(),
                row.getErrorMessage(),
                row.getOperatorName(),
                row.getImportedAt(),
                row.getPostedAt(),
                row.getReversedAt(),
                row.getReversedBy(),
                row.getReversedReason());
    }

    private ImportBatchProblemRow toProblemRow(ImportProblemRowObject row) {
        return new ImportBatchProblemRow(
                row.getRowNumber(), row.getBarcode(), row.getParseStatus(), row.getErrorMessage());
    }

    private <T> PageResult<T> emptyPage(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0, 0);
    }

    private <T> PageResult<T> page(List<T> items, int page, int size, long totalElements) {
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PageResult<>(items, page, size, totalElements, totalPages);
    }
}
