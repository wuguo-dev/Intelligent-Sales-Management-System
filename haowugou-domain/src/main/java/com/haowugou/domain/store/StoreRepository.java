package com.haowugou.domain.store;

import java.util.List;

/**
 * 门店仓库接口。
 */
public interface StoreRepository {

    /**
     * 查询所有启用的门店。
     */
    List<Store> findAllActive();

    /**
     * 判断门店是否存在且已启用。
     */
    boolean existsActiveById(long storeId);
}
