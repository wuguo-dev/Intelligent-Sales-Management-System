package com.haowugou.infrastructure.persistence.importbatch;

import com.haowugou.domain.importbatch.ImportPostRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 初始库存导入批次的原始 MyBatis Mapper。
 *
 * <p>全部语句按 {@code storeId} 隔离；写语句由 {@link MybatisImportBatchRepository}
 * 在单事务内按固定顺序调用，状态机由应用层控制，本 Mapper 不校验状态迁移。
 */
@Mapper
public interface ImportBatchMapper {

    /** 同门店、同导入类型、同文件指纹的批次数量（> 0 表示文件已导入过）。 */
    long countBatchByFileHash(@Param("storeId") long storeId, @Param("fileHash") String fileHash);

    /** 门店仍有效的初始库存批次数量（数据库生成列，与唯一约束同口径）。 */
    long countActiveInitialBatch(@Param("storeId") long storeId);

    /** 批量按条码查商品主键；不存在的条码不返回。 */
    List<ProductIdRow> findProductIdsByBarcodes(@Param("barcodes") List<String> barcodes);

    /** 插入批次，回填自增主键到 {@code batch.id}。 */
    int insertBatch(ImportBatchDataObject batch);

    /** 批量插入原始行。 */
    int insertRawRows(@Param("batchId") long batchId, @Param("rows") List<ImportRawRowObject> rows);

    /** 读取过账前现有库存余额（不存在的行不返回，适配器按 0 处理）。 */
    List<InventoryQuantityRow> findCurrentQuantities(
            @Param("storeId") long storeId,
            @Param("productIds") List<Long> productIds);

    /**
     * 库存 upsert：已存在则累加数量并递增版本号，不覆盖仓库分配；
     * 不存在则插入并置版本号为 1。
     */
    int upsertInventory(
            @Param("storeId") long storeId,
            @Param("warehouseId") Long warehouseId,
            @Param("rows") List<ImportPostRow> rows);

    /** 批量插入 INITIAL_BALANCE 流水。 */
    int insertMovements(
            @Param("storeId") long storeId,
            @Param("batchId") long batchId,
            @Param("businessDate") LocalDate businessDate,
            @Param("rows") List<InventoryMovementRow> rows);
}
