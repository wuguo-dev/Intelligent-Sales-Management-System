package com.haowugou.controller.product;

import com.haowugou.application.product.StoreProductPageResult;
import java.util.List;

/**
 * 普通用户可见的门店商品分页查询响应。
 *
 * <p>分页信息与门店信息与完整模型一致，只有列表项换成了
 * {@link RestrictedStoreProductItemResponse}。
 *
 * @param store 查询范围所属门店
 * @param items 当前页商品列表
 * @param page 当前页码，从 0 开始
 * @param size 每页数量
 * @param totalElements 符合筛选条件的商品总数
 * @param totalPages 总页数
 */
public record RestrictedStoreProductPageResponse(
        StoreSummaryResponse store,
        List<RestrictedStoreProductItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static RestrictedStoreProductPageResponse from(StoreProductPageResult result) {
        return new RestrictedStoreProductPageResponse(
                StoreSummaryResponse.from(result.store()),
                result.products().items().stream()
                        .map(RestrictedStoreProductItemResponse::from)
                        .toList(),
                result.products().page(),
                result.products().size(),
                result.products().totalElements(),
                result.products().totalPages());
    }
}
