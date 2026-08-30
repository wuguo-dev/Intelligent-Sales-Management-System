package com.haowugou.infrastructure.persistence.salesimport;

import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.importbatch.ImportFailureRow;
import com.haowugou.domain.salesimport.DailySalesFactRow;
import com.haowugou.domain.salesimport.DailySalesImportRepository;
import com.haowugou.domain.salesimport.DailySalesPosting;
import com.haowugou.domain.salesimport.PendingProductDraft;
import com.haowugou.domain.salesimport.ParsedSalesRow;
import com.haowugou.domain.salesimport.SalesMovement;
import com.haowugou.infrastructure.persistence.importbatch.ImportBatchDataObject;
import com.haowugou.infrastructure.persistence.importbatch.ImportRawRowObject;
import com.haowugou.infrastructure.persistence.importbatch.InventoryQuantityRow;
import com.haowugou.infrastructure.persistence.importbatch.ProductIdRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日销售导入的 MyBatis 实现。
 *
 * <p>{@link #postBatch} 单事务顺序写入：批次 → 原始行 → 待完善商品 → 销售事实 → 库存 upsert → 流水。
 * 待完善商品在事务内建立并按条码回查主键，因此过账失败不会留下孤立的 PENDING 商品。
 * 流水余额按「先 SELECT 现有库存 → Java 计算」得到，满足 {@code chk_inventory_movement_balance}。
 * 日志只记录批次元信息，不记录文件内容。
 */
@Repository
public class MybatisDailySalesImportRepository implements DailySalesImportRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisDailySalesImportRepository.class);

    private static final String IMPORT_TYPE_DAILY_SALES = "DAILY_SALES";
    private static final String STATUS_POSTED = "POSTED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String RAW_VALID = "VALID";
    private static final String RAW_INVALID = "INVALID";

    private final DailySalesImportMapper mapper;

    public MybatisDailySalesImportRepository(DailySalesImportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean existsFileHash(long storeId, String fileHash) {
        return mapper.countBatchByFileHash(storeId, fileHash) > 0;
    }

    @Override
    public boolean existsPostedSalesBatch(long storeId, LocalDate businessDate) {
        return mapper.countActiveSalesBatch(storeId, businessDate) > 0;
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
    public Map<String, Long> findCategoryIdsByNames(List<String> categoryNames) {
        if (categoryNames.isEmpty()) {
            return Map.of();
        }
        return idsByRequestedName(categoryNames, mapper.findCategoryIdsByNames(categoryNames));
    }

    @Override
    public Map<String, Long> findSupplierIdsByNames(List<String> supplierNames) {
        if (supplierNames.isEmpty()) {
            return Map.of();
        }
        return idsByRequestedName(supplierNames, mapper.findSupplierIdsByNames(supplierNames));
    }

    @Override
    @Transactional
    public long postBatch(DailySalesPosting posting) {
        ImportBatchDataObject batch = baseBatch(
                posting.storeId(), posting.fileName(), posting.fileHash(), posting.businessDate());
        batch.setStatus(STATUS_POSTED);
        batch.setTotalRows(posting.rawRows().size());
        batch.setSuccessRows(posting.rawRows().size());
        batch.setPostedAt(LocalDateTime.now());
        mapper.insertBatch(batch);
        long batchId = batch.getId();

        mapper.insertRawRows(batchId, posting.rawRows().stream()
                .map(this::toRawRowObject)
                .toList());

        Map<String, Long> productIds = createPendingAndResolveProductIds(posting);

        List<DailySalesFactObject> facts = toFacts(posting.factRows(), productIds);
        if (!facts.isEmpty()) {
            mapper.insertDailySales(posting.storeId(), batchId, posting.businessDate(), facts);
        }

        List<SalesMovement> movements = posting.movements();
        if (!movements.isEmpty()) {
            List<InventoryDeltaObject> deltas = toDeltas(movements, productIds);
            Map<Long, BigDecimal> balanceBefore = balancesBefore(posting.storeId(), deltas);
            mapper.upsertInventory(posting.storeId(), deltas);
            mapper.insertMovements(posting.storeId(), batchId, posting.businessDate(),
                    toMovementObjects(deltas, movements, balanceBefore));
        }
        log.info("每日销售批次过账完成 batchId={} storeId={} businessDate={} 数据行={} 销售事实={} "
                        + "待完善商品={} 扣减商品={} 文件名={}",
                batchId, posting.storeId(), posting.businessDate(), posting.rawRows().size(),
                facts.size(), posting.pendingProducts().size(), movements.size(), posting.fileName());
        return batchId;
    }

    @Override
    @Transactional
    public long saveFailedBatch(ImportFailure failure) {
        List<ImportFailureRow> rows = failure.rows();
        int errorRows = (int) rows.stream()
                .filter(row -> row.errorMessage() != null)
                .map(ImportFailureRow::rowNumber)
                .distinct()
                .count();

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
        log.info("每日销售批次记录失败 batchId={} storeId={} businessDate={} 数据行={} 错误行={} 文件名={}",
                batchId, failure.storeId(), failure.dataDate(), rows.size(), errorRows, failure.fileName());
        return batchId;
    }

    /**
     * 按调用方传入的名称建索引：数据库名称列的排序规则大小写不敏感，
     * {@code IN} 命中的名称大小写可能与文件里的写法不同，直接用返回值做键会让应用层查不到。
     */
    private Map<String, Long> idsByRequestedName(List<String> requested, List<NameIdRow> rows) {
        Map<String, Long> exact = new LinkedHashMap<>();
        Map<String, Long> ignoringCase = new LinkedHashMap<>();
        for (NameIdRow row : rows) {
            exact.put(row.getName(), row.getId());
            ignoringCase.putIfAbsent(row.getName().toLowerCase(Locale.ROOT), row.getId());
        }
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String name : requested) {
            Long id = exact.get(name);
            if (id == null) {
                id = ignoringCase.get(name.toLowerCase(Locale.ROOT));
            }
            if (id != null) {
                ids.put(name, id);
            }
        }
        return ids;
    }

    private ImportBatchDataObject baseBatch(
            long storeId, String fileName, String fileHash, LocalDate dataDate) {
        ImportBatchDataObject batch = new ImportBatchDataObject();
        batch.setStoreId(storeId);
        batch.setImportType(IMPORT_TYPE_DAILY_SALES);
        batch.setFileName(fileName);
        batch.setFileHash(fileHash);
        batch.setDataDate(dataDate);
        return batch;
    }

    private ImportRawRowObject toRawRowObject(ParsedSalesRow row) {
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

    /** 在过账事务内新建待完善商品，并把回查到的主键并入已知商品映射。 */
    private Map<String, Long> createPendingAndResolveProductIds(DailySalesPosting posting) {
        Map<String, Long> productIds = new LinkedHashMap<>(posting.knownProductIds());
        List<PendingProductDraft> pending = posting.pendingProducts();
        if (pending.isEmpty()) {
            return productIds;
        }
        mapper.insertPendingProducts(pending.stream()
                .map(this::toPendingProduct)
                .toList());
        List<String> barcodes = pending.stream().map(PendingProductDraft::barcode).toList();
        for (ProductIdRow row : mapper.findProductIdsByBarcodes(barcodes)) {
            productIds.put(row.getBarcode(), row.getProductId());
        }
        return productIds;
    }

    private PendingProductObject toPendingProduct(PendingProductDraft draft) {
        PendingProductObject object = new PendingProductObject();
        object.setBarcode(draft.barcode());
        object.setProductName(draft.productName());
        object.setCategoryId(draft.categoryId());
        object.setTaxCostPrice(draft.taxCostPrice());
        object.setSalePrice(draft.salePrice());
        return object;
    }

    private List<DailySalesFactObject> toFacts(
            List<DailySalesFactRow> factRows,
            Map<String, Long> productIds) {
        List<DailySalesFactObject> facts = new ArrayList<>(factRows.size());
        for (DailySalesFactRow row : factRows) {
            DailySalesFactObject fact = new DailySalesFactObject();
            fact.setProductId(productId(row.barcode(), productIds));
            fact.setSupplierId(row.supplierId());
            fact.setSalesQuantity(row.salesQuantity());
            fact.setSalesAmount(row.salesAmount());
            fact.setGrossProfitAmount(row.grossProfitAmount());
            fact.setReportedRate(row.reportedRate());
            facts.add(fact);
        }
        return facts;
    }

    private List<InventoryDeltaObject> toDeltas(
            List<SalesMovement> movements,
            Map<String, Long> productIds) {
        List<InventoryDeltaObject> deltas = new ArrayList<>(movements.size());
        for (SalesMovement movement : movements) {
            InventoryDeltaObject delta = new InventoryDeltaObject();
            delta.setProductId(productId(movement.barcode(), productIds));
            delta.setQuantityChange(movement.quantityChange());
            deltas.add(delta);
        }
        return deltas;
    }

    private Map<Long, BigDecimal> balancesBefore(long storeId, List<InventoryDeltaObject> deltas) {
        List<Long> productIds = deltas.stream().map(InventoryDeltaObject::getProductId).toList();
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (InventoryQuantityRow row : mapper.findCurrentQuantities(storeId, productIds)) {
            balances.put(row.getProductId(), row.getCurrentQuantity());
        }
        return balances;
    }

    private List<SalesMovementObject> toMovementObjects(
            List<InventoryDeltaObject> deltas,
            List<SalesMovement> movements,
            Map<Long, BigDecimal> balanceBefore) {
        List<SalesMovementObject> rows = new ArrayList<>(deltas.size());
        for (int index = 0; index < deltas.size(); index++) {
            InventoryDeltaObject delta = deltas.get(index);
            BigDecimal before = balanceBefore.getOrDefault(delta.getProductId(), BigDecimal.ZERO);
            SalesMovementObject row = new SalesMovementObject();
            row.setProductId(delta.getProductId());
            row.setMovementType(movements.get(index).movementType());
            row.setQuantityChange(delta.getQuantityChange());
            row.setBalanceBefore(before);
            row.setBalanceAfter(before.add(delta.getQuantityChange()));
            rows.add(row);
        }
        return rows;
    }

    private long productId(String barcode, Map<String, Long> productIds) {
        Long productId = productIds.get(barcode);
        if (productId == null) {
            throw new IllegalStateException("过账载荷里的条码未解析出商品主键: " + barcode);
        }
        return productId;
    }
}
