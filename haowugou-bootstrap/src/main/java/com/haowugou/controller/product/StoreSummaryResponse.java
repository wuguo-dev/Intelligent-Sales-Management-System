package com.haowugou.controller.product;

import com.haowugou.domain.store.Store;

/**
 * 门店商品查询响应中的门店简要信息。
 *
 * @param id 门店标识
 * @param storeCode 门店编码
 * @param storeName 门店名称
 */
public record StoreSummaryResponse(Long id, String storeCode, String storeName) {

    static StoreSummaryResponse from(Store store) {
        return new StoreSummaryResponse(store.id(), store.storeCode(), store.storeName());
    }
}