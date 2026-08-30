package com.haowugou.domain.salesimport;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 校验通过的每日销售过账载荷。
 *
 * <p>{@code factRows} 与 {@code movements} 都按条码引用商品：已存在的商品在 {@code knownProductIds}
 * 里，未知条码在 {@code pendingProducts} 里，由持久化层在同一事务内建好并补齐主键。
 *
 * @param storeId         门店主键，批次、销售事实、库存与流水均按此隔离
 * @param fileName        原始文件名
 * @param fileHash        文件 SHA-256 指纹（十六进制小写）
 * @param businessDate    销售业务日期，来自请求参数（文件本身无日期列）
 * @param rawRows         全部数据行（parse_status 记 VALID）
 * @param knownProductIds 已存在商品的条码 → 商品主键
 * @param pendingProducts 未知条码需新建的待完善商品
 * @param factRows        按 {@code (条码, 供应商)} 归并的销售事实
 * @param movements       按商品汇总的库存流水（净销量为 0 的商品不在其中）
 */
public record DailySalesPosting(
        long storeId,
        String fileName,
        String fileHash,
        LocalDate businessDate,
        List<ParsedSalesRow> rawRows,
        Map<String, Long> knownProductIds,
        List<PendingProductDraft> pendingProducts,
        List<DailySalesFactRow> factRows,
        List<SalesMovement> movements) {
}