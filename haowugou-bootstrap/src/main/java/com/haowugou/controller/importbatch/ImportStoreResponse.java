package com.haowugou.controller.importbatch;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.domain.store.Store;

/**
 * 批次查询响应中的门店简要信息。
 *
 * @param id 门店标识
 * @param storeCode 门店编码
 * @param storeName 门店名称
 */
public record ImportStoreResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String storeCode,
        String storeName) {

    static ImportStoreResponse from(Store store) {
        return new ImportStoreResponse(store.id(), store.storeCode(), store.storeName());
    }
}
