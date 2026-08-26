package com.haowugou.application.product;

/** 商品未在指定门店建立当前库存关系。 */
public final class StoreProductNotFoundException extends RuntimeException {

    public StoreProductNotFoundException(long storeId, long productId) {
        super("商品未在指定门店建立库存关系: storeId=" + storeId + ", productId=" + productId);
    }
}
