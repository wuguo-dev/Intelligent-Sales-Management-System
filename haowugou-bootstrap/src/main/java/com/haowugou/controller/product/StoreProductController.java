package com.haowugou.controller.product;

import com.haowugou.application.product.StoreProductQuery;
import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.ProductDataStatus;
import com.haowugou.domain.product.StoreProductQueryCriteria;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
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
     */
    @GetMapping("/products")
    public StoreProductPageResponse listProducts(
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
                startDate,
                endDate,
                page,
                size);
        return StoreProductPageResponse.from(storeProductQuery.listProducts(criteria));
    }

    /** 返回指定门店内单个商品的详情；商品未在该门店建立库存关系时返回 HTTP 404。 */
    @GetMapping("/products/{productId}")
    public StoreProductDetailResponse findProduct(
            @PathVariable long storeId,
            @PathVariable long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return StoreProductDetailResponse.from(
                storeProductQuery.findProduct(storeId, productId, startDate, endDate));
    }

    /** 返回指定门店的已启用仓库；门店不存在时返回 HTTP 404。 */
    @GetMapping("/warehouses")
    public List<WarehouseResponse> listWarehouses(@PathVariable long storeId) {
        return storeProductQuery.listWarehouses(storeId).stream()
                .map(WarehouseResponse::from)
                .toList();
    }
}