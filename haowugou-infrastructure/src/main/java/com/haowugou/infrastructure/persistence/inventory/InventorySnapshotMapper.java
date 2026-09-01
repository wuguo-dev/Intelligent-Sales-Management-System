package com.haowugou.infrastructure.persistence.inventory;

import com.haowugou.infrastructure.persistence.inventory.InventoryItemRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 库存快照查询 Mapper，负责封装库存表与商品表的联表 SQL。 */
@Mapper
public interface InventorySnapshotMapper {

    @Select("""
            SELECT
                i.store_id,
                i.product_id,
                p.barcode,
                p.product_name,
                p.unit,
                p.category_code,
                p.category_name,
                i.snapshot_date,
                i.quantity,
                i.unit_cost,
                i.sale_price,
                i.data_origin
            FROM inventory_snapshot i
            INNER JOIN product p ON p.id = i.product_id
            WHERE i.store_id = #{storeId}
              AND i.snapshot_date = #{snapshotDate}
            ORDER BY p.category_code, p.product_name
            """)
    List<InventoryItemRow> findInventory(
            @Param("storeId") long storeId,
            @Param("snapshotDate") LocalDate snapshotDate);
}
