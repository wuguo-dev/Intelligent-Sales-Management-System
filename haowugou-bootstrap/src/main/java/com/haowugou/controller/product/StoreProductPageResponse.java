package com.haowugou.controller.product;

import com.haowugou.application.product.StoreProductPageResult;
import java.util.List;

/**
 * 门店商品分页查询的 HTTP 响应模型。
 *
 * @param store 查询范围所属门店
 * @param items 当前页商品列表
 * @param page 当前页码，从 0 开始
 * @param size 每页数量
 * @param totalElements 符合筛选条件的商品总数
 * @param totalPages 总页数
 */
public record StoreProductPageResponse(
        StoreSummaryResponse store,
        List<StoreProductItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static StoreProductPageResponse from(StoreProductPageResult result) {
        return new StoreProductPageResponse(
                StoreSummaryResponse.from(result.store()),
                result.products().items().stream()
                        .map(StoreProductItemResponse::from)
                        .toList(),
                result.products().page(),
                result.products().size(),
                result.products().totalElements(),
                result.products().totalPages());
    }
}