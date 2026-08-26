package com.haowugou.domain.inventory;

import java.time.LocalDate;
import java.util.List;

/**
 * 库存快照仓库接口。
 */
public interface InventoryRepository {

    /**
     * 查询指定门店和日期的商品库存。
     */
    List<InventoryItem> findByStoreAndDate(long storeId, LocalDate snapshotDate);
}
