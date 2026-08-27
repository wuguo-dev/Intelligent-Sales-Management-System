package com.haowugou.domain.importbatch;

import java.util.List;
import java.util.Map;

/**
 * 初始库存导入的持久化边界：批次、原始行、库存余额与流水的原子落库。
 *
 * <p>写入方法必须保证单事务；文件解析与业务校验在调用前完成（架构规范 §12）。
 */
public interface ImportBatchRepository {

    /** 同门店、同导入类型、同文件指纹是否已导入过。 */
    boolean existsFileHash(long storeId, String fileHash);

    /** 门店是否已有仍有效的初始库存批次。 */
    boolean existsActiveInitialBatch(long storeId);

    /** 批量按条码查商品主键；返回条码 → 商品主键，不存在的条码不出现。 */
    Map<String, Long> findProductIdsByBarcodes(List<String> barcodes);

    /**
     * 单事务过账：插入 POSTED 批次与原始行、库存累加、写入 INITIAL_BALANCE 流水。
     *
     * @return 批次主键
     */
    long postBatch(ImportPosting posting);

    /**
     * 单事务记录失败批次：插入 FAILED 批次与带行级错误的原始行，不产生库存变化。
     *
     * @return 批次主键
     */
    long saveFailedBatch(ImportFailure failure);
}
