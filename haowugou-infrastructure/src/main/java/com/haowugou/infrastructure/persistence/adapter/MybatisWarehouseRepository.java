package com.haowugou.infrastructure.persistence.adapter;

import com.haowugou.domain.warehouse.WarehouseRepository;
import com.haowugou.domain.warehouse.WarehouseSummary;
import com.haowugou.infrastructure.persistence.data.WarehouseRow;
import com.haowugou.infrastructure.persistence.mapper.WarehouseQueryMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis 实现按门店查询仓库的 Repository。 */
@Repository
public class MybatisWarehouseRepository implements WarehouseRepository {

    private final WarehouseQueryMapper mapper;

    public MybatisWarehouseRepository(WarehouseQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
        return mapper.findAllActiveByStoreId(storeId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
        return mapper.existsByStoreIdAndId(storeId, warehouseId);
    }

    private WarehouseSummary toDomain(WarehouseRow row) {
        return new WarehouseSummary(
                row.getId(),
                row.getStoreId(),
                row.getWarehouseCode(),
                row.getWarehouseName());
    }
}
