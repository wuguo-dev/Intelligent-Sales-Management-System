package com.haowugou.domain.sales;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 门店日销售仓库接口。
 */
public interface StoreDailySalesRepository {

    /**
     * 查询指定门店和营业日的销售汇总。
     */
    Optional<StoreDailySales> findByStoreAndDate(long storeId, LocalDate businessDate);
}
