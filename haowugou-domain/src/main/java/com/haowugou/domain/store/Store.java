package com.haowugou.domain.store;

/**
 * 门店。
 *
 * @param id 门店标识
 * @param storeCode 门店编码
 * @param storeName 门店名称
 */
public record Store(Long id, String storeCode, String storeName) {
}
