package com.haowugou.infrastructure.persistence.importbatch;

import com.haowugou.domain.importbatch.ImportBatchRepository;
import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.importbatch.ImportFailureRow;
import com.haowugou.domain.importbatch.ImportPosting;
import com.haowugou.domain.importbatch.ImportPostRow;
import com.haowugou.domain.importbatch.ParsedImportRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始库存导入批次的 MyBatis 实现。
 *
 * <p>{@link #postBatch} 与 {@link #saveFailedBatch} 均为单事务写入：
 * 批次 → 原始行 →（过账时）库存 upsert → 流水。余额按「先 SELECT 现有库存 → Java 计算」得到，
 * 保证 {@code chk_inventory_movement_balance} 校验通过。日志只记录批次元信息，不记录文件内容。
 */
@Repository
public class MybatisImportBatchRepository implements ImportBatchRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisImportBatchRepository.class);

    private static final String IMPORT_TYPE_INITIAL = "INITIAL_INVENTORY";
    private static final String STATUS_POSTED = "POSTED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String RAW_VALID = "VALID";
    private static final String RAW_INVALID = "INVALID";

    private final ImportBatchMapper mapper;

    public MybatisImportBatchRepository(ImportBatchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean existsFileHash(long storeId, String fileHash) {
        return mapper.countBatchByFileHash(storeId, fileHash) > 0;
    }

    @Override
    public boolean existsActiveInitialBatch(long storeId) {
        return mapper.countActiveInitialBatch(storeId) > 0;
    }

    @Override
    public Map<String, Long> findProductIdsByBarcodes(List<String> barcodes) {
        if (barcodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> productIds = new LinkedHashMap<>();
        for (ProductIdRow row : mapper.findProductIdsByBarcodes(barcodes)) {
            productIds.put(row.getBarcode(), row.getProductId());
        }
        return productIds;
    }

    @Override
    @Transactional
    public long postBatch(ImportPosting posting) {
        ImportBatchDataObject batch = baseBatch(
                posting.storeId(), posting.fileName(), posting.fileHash(), posting.dataDate());
        batch.setStatus(STATUS_POSTED);
        batch.setTotalRows(posting.rawRows().size());
        batch.setSuccessRows(posting.rawRows().size());
        batch.setPostedAt(LocalDateTime.now());
        mapper.insertBatch(batch);
        long batchId = batch.getId();

        mapper.insertRawRows(batchId, posting.rawRows().stream()
                .map(this::toRawRowObject)
                .toList());

        List<ImportPostRow> postRows = posting.postRows();
        if (!postRows.isEmpty()) {
            Map<Long, BigDecimal> balanceBefore = balancesBefore(posting.storeId(), postRows);
            mapper.upsertInventory(posting.storeId(), posting.warehouseId(), postRows);
            mapper.insertMovements(posting.storeId(), batchId, posting.dataDate(),
                    toMovements(postRows, balanceBefore));
        }
        log.info("初始库存批次过账完成 batchId={} storeId={} dataDate={} 数据行={} 过账行={} 文件名={}",
                batchId, posting.storeId(), posting.dataDate(),
                posting.rawRows().size(), postRows.size(), posting.fileName());
        return batchId;
    }

    @Override
    @Transactional
    public long saveFailedBatch(ImportFailure failure) {
        List<ImportFailureRow> rows = failure.rows();
        int errorRows = (int) rows.stream().filter(row -> row.errorMessage() != null).count();

        ImportBatchDataObject batch = baseBatch(
                failure.storeId(), failure.fileName(), failure.fileHash(), failure.dataDate());
        batch.setStatus(STATUS_FAILED);
        batch.setTotalRows(rows.size());
        batch.setErrorRows(errorRows);
        batch.setErrorMessage(failure.errorSummary());
        mapper.insertBatch(batch);
        long batchId = batch.getId();

        mapper.insertRawRows(batchId, rows.stream()
                .map(this::toRawRowObject)
                .toList());
        log.info("初始库存批次记录失败 batchId={} storeId={} dataDate={} 数据行={} 错误行={} 文件名={}",
                batchId, failure.storeId(), failure.dataDate(), rows.size(), errorRows, failure.fileName());
        return batchId;
    }

    private ImportBatchDataObject baseBatch(
            long storeId, String fileName, String fileHash, LocalDate dataDate) {
        ImportBatchDataObject batch = new ImportBatchDataObject();
        batch.setStoreId(storeId);
        batch.setImportType(IMPORT_TYPE_INITIAL);
        batch.setFileName(fileName);
        batch.setFileHash(fileHash);
        batch.setDataDate(dataDate);
        return batch;
    }

    private ImportRawRowObject toRawRowObject(ParsedImportRow row) {
        ImportRawRowObject object = new ImportRawRowObject();
        object.setRowNumber(row.rowNumber());
        object.setBarcode(row.barcode());
        object.setRawData(row.rawData());
        object.setParseStatus(RAW_VALID);
        return object;
    }

    private ImportRawRowObject toRawRowObject(ImportFailureRow row) {
        ImportRawRowObject object = new ImportRawRowObject();
        object.setRowNumber(row.rowNumber());
        object.setBarcode(row.barcode());
        object.setRawData(row.rawData());
        object.setParseStatus(row.errorMessage() == null ? RAW_VALID : RAW_INVALID);
        object.setErrorMessage(row.errorMessage());
        return object;
    }

    private Map<Long, BigDecimal> balancesBefore(long storeId, List<ImportPostRow> postRows) {
        List<Long> productIds = postRows.stream().map(ImportPostRow::productId).toList();
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (InventoryQuantityRow row : mapper.findCurrentQuantities(storeId, productIds)) {
            balances.put(row.getProductId(), row.getCurrentQuantity());
        }
        return balances;
    }

    private List<InventoryMovementRow> toMovements(
            List<ImportPostRow> postRows,
            Map<Long, BigDecimal> balanceBefore) {
        List<InventoryMovementRow> movements = new ArrayList<>(postRows.size());
        for (ImportPostRow row : postRows) {
            BigDecimal before = balanceBefore.getOrDefault(row.productId(), BigDecimal.ZERO);
            InventoryMovementRow movement = new InventoryMovementRow();
            movement.setProductId(row.productId());
            movement.setQuantityChange(row.quantity());
            movement.setBalanceBefore(before);
            movement.setBalanceAfter(before.add(row.quantity()));
            movements.add(movement);
        }
        return movements;
    }
}
