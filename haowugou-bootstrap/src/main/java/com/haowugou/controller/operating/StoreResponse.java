package com.haowugou.controller.operating;

import com.haowugou.domain.store.Store;

/**
 * 门店的 HTTP 响应模型。

 * @param id 门店标识
 * @param storeCode 门店编码
 * @param storeName 门店名称
 */
public record StoreResponse(Long id, String storeCode, String storeName) {

    static StoreResponse from(Store store) {
        return new StoreResponse(store.id(), store.storeCode(), store.storeName());
    }
}
