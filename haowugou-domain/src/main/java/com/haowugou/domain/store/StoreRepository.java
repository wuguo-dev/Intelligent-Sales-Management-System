package com.haowugou.domain.store;

import java.util.List;
import java.util.Optional;

/**
 * 门店仓库接口。
 */
public interface StoreRepository {

    /**
     * 查询所有启用的门店。
     */
    List<Store> findAllActive();

    /**
     * 查询指定的启用门店。
     *
     * <p>默认实现保持已有 Repository 替身兼容；数据库 Adapter 可覆盖为按主键查询。
     */
    default Optional<Store> findActiveById(long storeId) {
        return findAllActive().stream()
                .filter(store -> store.id() == storeId)
                .findFirst();
    }

    /**
     * 判断门店是否存在且已启用。
     */
    boolean existsActiveById(long storeId);
}
