package com.haowugou.infrastructure.persistence.adapter;

import com.haowugou.domain.inventory.InventoryItem;
import com.haowugou.domain.inventory.InventoryRepository;
import com.haowugou.infrastructure.persistence.data.InventoryItemRow;
import com.haowugou.infrastructure.persistence.mapper.InventorySnapshotMapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis 实现领域层库存 Repository 接口的持久化 Adapter。 */
@Repository
public class MybatisInventoryRepository implements InventoryRepository {

    private final InventorySnapshotMapper mapper;

    public MybatisInventoryRepository(InventorySnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<InventoryItem> findByStoreAndDate(long storeId, LocalDate snapshotDate) {
        return mapper.findInventory(storeId, snapshotDate).stream()
                .map(this::toDomain)
                .toList();
    }

    private InventoryItem toDomain(InventoryItemRow row) {
        return new InventoryItem(
                row.getStoreId(),
                row.getProductId(),
                row.getBarcode(),
                row.getProductName(),
                row.getUnit(),
                row.getCategoryCode(),
                row.getCategoryName(),
                row.getSnapshotDate(),
                row.getQuantity(),
                row.getUnitCost(),
                row.getSalePrice(),
                row.getDataOrigin());
    }
}
