package com.haowugou.controller.operating;

import com.haowugou.application.operating.OperatingDataQuery;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 经营数据的 HTTP 查询入口。

 * <p>该适配器只负责 HTTP 参数绑定、响应模型转换和状态码处理。门店校验与查询编排统一由
 * {@link OperatingDataQuery} 完成，因此其他入口可以复用同一应用层查询模块。
 */
@RestController
@RequestMapping("/api")
public class OperatingDataController {

    private final OperatingDataQuery operatingDataQuery;

    public OperatingDataController(OperatingDataQuery operatingDataQuery) {
        this.operatingDataQuery = operatingDataQuery;
    }

    /** 返回所有已启用门店。 */
    @GetMapping("/stores")
    public List<StoreResponse> listStores() {
        return operatingDataQuery.listStores().stream()
                .map(StoreResponse::from)
                .toList();
    }

    /** 返回指定门店、营业日的销售汇总；当日无销售记录时返回 HTTP 404。 */
    @GetMapping("/sales/daily")
    public ResponseEntity<StoreDailySalesResponse> findDailySales(
            @RequestParam long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.of(operatingDataQuery.findDailySales(storeId, date)
                .map(StoreDailySalesResponse::from));
    }

    /** 返回指定门店、快照日期的商品库存；没有快照时返回空列表。 */
    @GetMapping("/inventory")
    public List<InventoryItemResponse> listInventory(
            @RequestParam long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return operatingDataQuery.listInventory(storeId, date).stream()
                .map(InventoryItemResponse::from)
                .toList();
    }
}
