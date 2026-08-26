package com.haowugou.application.product;

import com.haowugou.domain.product.StoreProductDetail;
import com.haowugou.domain.store.Store;

/** 带门店上下文的商品详情应用结果。 */
public record StoreProductDetailResult(
        Store store,
        StoreProductDetail product) {
}
