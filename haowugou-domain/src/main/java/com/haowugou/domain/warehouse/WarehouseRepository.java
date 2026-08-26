package com.haowugou.domain.warehouse;

import java.util.List;

/** 门店仓库查询的持久化边界。 */
public interface WarehouseRepository {

    List<WarehouseSummary> findAllActiveByStoreId(long storeId);

    boolean existsByStoreIdAndId(long storeId, long warehouseId);
}
