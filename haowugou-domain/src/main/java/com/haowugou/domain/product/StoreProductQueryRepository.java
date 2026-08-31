package com.haowugou.domain.product;

import com.haowugou.domain.pagination.PageResult;
import java.time.LocalDate;
import java.util.Optional;

/** 门店范围商品查询的持久化边界。 */
public interface StoreProductQueryRepository {

    PageResult<StoreProductListItem> findPage(StoreProductQueryCriteria criteria);

    Optional<StoreProductDetail> findDetail(
            long storeId,
            long productId,
            LocalDate startDate,
            LocalDate endDate);
}
