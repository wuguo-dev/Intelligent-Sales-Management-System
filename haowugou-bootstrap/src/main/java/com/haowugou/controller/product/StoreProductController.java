package com.haowugou.controller.product;

import com.haowugou.application.product.StoreProductDetailResult;
import com.haowugou.application.product.StoreProductPageResult;
import com.haowugou.application.product.StoreProductQuery;
import com.haowugou.application.product.exception.InvalidStoreProductQueryException;
import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.ProductDataStatus;
import com.haowugou.domain.product.StoreProductQueryCriteria;
import com.haowugou.security.AppUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店范围商品查询的 HTTP 入口。
 *
 * <p>该适配器只负责 HTTP 参数绑定和响应模型转换，门店校验与查询编排统一由
 * {@link StoreProductQuery} 完成。所有路径显式携带门店标识，避免调用方遗漏门店范围。
 */
@RestController
@RequestMapping("/api/stores/{storeId}")
public class StoreProductController {

    private final StoreProductQuery storeProductQuery;

    public StoreProductController(StoreProductQuery storeProductQuery) {
        this.storeProductQuery = storeProductQuery;
    }

    /**
     * 返回指定门店的商品分页列表。
     *
     * <p>支持条码/名称关键字、品类、供应商、仓库、库存状态、库存范围和商品资料状态筛选；
     * 日期范围用于统计该门店有效销售批次的期间销量、销售额和毛利额。
     *
     * <p>响应模型按角色二选一：管理员拿 {@link StoreProductPageResponse}，普通用户拿
     * 字段更少的 {@link RestrictedStoreProductPageResponse}。判定走
     * {@link com.haowugou.domain.user.UserRole#canViewCostAndProfit()}——该谓词在这里
     * 决定的是整个投影，不只是成本价与毛利两个字段。
     */
    @GetMapping("/products")
    public ResponseEntity<?> listProducts(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable long storeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) InventoryStatus inventoryStatus,
            @RequestParam(required = false) ProductDataStatus dataStatus,
            @RequestParam(required = false) BigDecimal minStock,
            @RequestParam(required = false) BigDecimal maxStock,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        boolean full = principal.role().canViewCostAndProfit();
        if (!full && supplierId != null) {
            // 供应商筛选是行级过滤，静默忽略会让调用方拿到比它请求的更多的行；
            // 而照常执行又等于把「该商品属于哪家供应商」这个普通用户看不到的字段
            // 变成可探测的推断通道，所以只能明确拒绝（与跨门店仓库同为 400）。
            throw new InvalidStoreProductQueryException("当前账号无权按供应商筛选商品");
        }
        StoreProductQueryCriteria criteria = new StoreProductQueryCriteria(
                storeId,
                keyword,
                categoryId,
                supplierId,
                warehouseId,
                inventoryStatus,
                dataStatus,
                minStock,
                maxStock,
                // 普通用户的投影里没有期间指标，日期传下去只会多跑一次销售聚合查询。
                // 日期不参与行过滤（只喂指标子查询），所以清空不会改变返回的行集。
                full ? startDate : null,
                full ? endDate : null,
                page,
                size);
        StoreProductPageResult result = storeProductQuery.listProducts(criteria);
        if (full) {
            return ResponseEntity.ok(StoreProductPageResponse.from(result));
        }
        return ResponseEntity.ok(RestrictedStoreProductPageResponse.from(result));
    }

    /**
     * 返回指定门店内单个商品的详情；商品未在该门店建立库存关系时返回 HTTP 404。
     *
     * <p>与列表一致，普通用户拿到的是只含售价、库存数量与所处仓库的
     * {@link RestrictedStoreProductDetailResponse}。
     */
    @GetMapping("/products/{productId}")
    public ResponseEntity<?> findProduct(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable long storeId,
            @PathVariable long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        boolean full = principal.role().canViewCostAndProfit();
        StoreProductDetailResult result = storeProductQuery.findProduct(
                storeId, productId, full ? startDate : null, full ? endDate : null);
        if (full) {
            return ResponseEntity.ok(StoreProductDetailResponse.from(result));
        }
        return ResponseEntity.ok(RestrictedStoreProductDetailResponse.from(result));
    }

    /** 返回指定门店的已启用仓库；门店不存在时返回 HTTP 404。 */
    @GetMapping("/warehouses")
    public List<WarehouseResponse> listWarehouses(@PathVariable long storeId) {
        return storeProductQuery.listWarehouses(storeId).stream()
                .map(WarehouseResponse::from)
                .toList();
    }
}