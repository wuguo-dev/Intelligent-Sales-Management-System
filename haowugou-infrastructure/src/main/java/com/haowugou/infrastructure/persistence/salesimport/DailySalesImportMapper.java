package com.haowugou.infrastructure.persistence.salesimport;

import com.haowugou.infrastructure.persistence.importbatch.ImportBatchDataObject;
import com.haowugou.infrastructure.persistence.importbatch.ImportRawRowObject;
import com.haowugou.infrastructure.persistence.importbatch.InventoryQuantityRow;
import com.haowugou.infrastructure.persistence.importbatch.ProductIdRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 每日销售导入的原始 MyBatis Mapper。
 *
 * <p>批次与原始行两张表与初始库存导入相同，因此复用 {@code importbatch} 包的持久化对象，
 * 但 SQL 各自独立（本 Mapper 的批次查询固定按 {@code import_type='DAILY_SALES'} 过滤）。
 * 所有涉及门店数据的语句都带 {@code storeId}；写语句由 {@link MybatisDailySalesImportRepository}
 * 在单事务内按固定顺序调用。
 */
@Mapper
public interface DailySalesImportMapper {

    /** 同门店、DAILY_SALES 类型、同文件指纹的批次数量（> 0 表示文件已导入过）。 */
    long countBatchByFileHash(@Param("storeId") long storeId, @Param("fileHash") String fileHash);

    /** 该门店该业务日期仍有效的销售批次数量（走数据库生成列，与唯一约束同口径）。 */
    long countActiveSalesBatch(
            @Param("storeId") long storeId,
            @Param("businessDate") LocalDate businessDate);

    /** 批量按条码查商品主键；不存在的条码不返回。 */
    List<ProductIdRow> findProductIdsByBarcodes(@Param("barcodes") List<String> barcodes);

    /** 批量按名称查品类主键；品类名称非唯一，同名取最小主键保证结果确定。 */
    List<NameIdRow> findCategoryIdsByNames(@Param("names") List<String> names);

    /** 批量按名称查供应商主键（{@code uk_supplier_name} 保证同名最多一条）。 */
    List<NameIdRow> findSupplierIdsByNames(@Param("names") List<String> names);

    /** 插入批次，回填自增主键到 {@code batch.id}。 */
    int insertBatch(ImportBatchDataObject batch);

    /** 批量插入原始行。 */
    int insertRawRows(@Param("batchId") long batchId, @Param("rows") List<ImportRawRowObject> rows);

    /** 批量插入待完善商品（{@code data_status='PENDING'}），主键随后按条码回查。 */
    int insertPendingProducts(@Param("rows") List<PendingProductObject> rows);

    /** 批量插入销售事实。 */
    int insertDailySales(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId,
            @Param("businessDate") LocalDate businessDate,
            @Param("rows") List<DailySalesFactObject> rows);

    /** 读取扣减前现有库存余额（不存在的行不返回，适配器按 0 处理）。 */
    List<InventoryQuantityRow> findCurrentQuantities(
            @Param("storeId") long storeId,
            @Param("productIds") List<Long> productIds);

    /**
     * 库存 upsert：已存在则按增量累加并递增版本号，不覆盖仓库分配；
     * 不存在则插入并置版本号为 1（仓库留空、允许负库存）。
     */
    int upsertInventory(
            @Param("storeId") long storeId,
            @Param("rows") List<InventoryDeltaObject> rows);

    /** 批量插入 SALE_OUT / SALE_RETURN 流水。 */
    int insertMovements(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId,
            @Param("businessDate") LocalDate businessDate,
            @Param("rows") List<SalesMovementObject> rows);
}
