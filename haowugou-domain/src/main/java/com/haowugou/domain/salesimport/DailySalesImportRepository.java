package com.haowugou.domain.salesimport;

import com.haowugou.domain.importbatch.ImportFailure;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 每日销售导入的持久化边界：批次、原始行、销售事实、库存余额与流水的原子落库。
 *
 * <p>写入方法必须保证单事务；文件解析与业务校验在调用前完成（架构规范 §12）。
 */
public interface DailySalesImportRepository {

    /**
     * 同门店、DAILY_SALES 类型、同文件指纹是否已有<strong>有效</strong>批次。
     *
     * <p>只看有效批次：批次被撤销或失败后同一份文件可以重传。
     */
    boolean existsFileHash(long storeId, String fileHash);

    /** 该门店该业务日期是否已有 POSTED 销售批次。 */
    boolean existsPostedSalesBatch(long storeId, LocalDate businessDate);

    /** 批量按条码查商品主键；返回条码 → 商品主键，不存在的条码不出现。 */
    Map<String, Long> findProductIdsByBarcodes(List<String> barcodes);

    /** 批量按名称查品类主键；返回品类名称 → 主键，不存在的名称不出现。 */
    Map<String, Long> findCategoryIdsByNames(List<String> categoryNames);

    /** 批量按名称查供应商主键；返回供应商名称 → 主键，不存在的名称不出现。 */
    Map<String, Long> findSupplierIdsByNames(List<String> supplierNames);

    /**
     * 单事务过账：插入 POSTED 批次与原始行、新建待完善商品、写入销售事实、扣减库存、写入
     * SALE_OUT/SALE_RETURN 流水。
     *
     * @return 批次主键
     */
    long postBatch(DailySalesPosting posting);

    /**
     * 单事务记录失败批次：插入 FAILED 批次与带行级错误的原始行，不产生销售事实与库存变化。
     *
     * @return 批次主键
     */
    long saveFailedBatch(ImportFailure failure);
}