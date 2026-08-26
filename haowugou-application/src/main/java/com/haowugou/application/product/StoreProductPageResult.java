package com.haowugou.application.product;

import com.haowugou.domain.product.PageResult;
import com.haowugou.domain.product.StoreProductListItem;
import com.haowugou.domain.store.Store;

/** 带门店上下文的商品分页应用结果。 */
public record StoreProductPageResult(
        Store store,
        PageResult<StoreProductListItem> products) {
}
