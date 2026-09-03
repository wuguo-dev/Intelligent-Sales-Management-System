package com.haowugou.controller.product;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.haowugou.domain.warehouse.WarehouseSummary;

/**
 * 门店仓库的 HTTP 响应模型。
 *
 * @param id 仓库标识
 * @param storeId 所属门店标识
 * @param warehouseCode 仓库编码
 * @param warehouseName 仓库名称
 */
public record WarehouseResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long storeId,
        String warehouseCode,
        String warehouseName) {

    static WarehouseResponse from(WarehouseSummary warehouse) {
        return new WarehouseResponse(
                warehouse.id(),
                warehouse.storeId(),
                warehouse.warehouseCode(),
                warehouse.warehouseName());
    }
}