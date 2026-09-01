package com.haowugou.infrastructure.persistence.warehouse;

import com.haowugou.infrastructure.persistence.warehouse.WarehouseRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 按门店查询仓库的 Mapper。 */
@Mapper
public interface WarehouseQueryMapper {

    List<WarehouseRow> findAllActiveByStoreId(@Param("storeId") long storeId);

    boolean existsByStoreIdAndId(
            @Param("storeId") long storeId,
            @Param("warehouseId") long warehouseId);
}
