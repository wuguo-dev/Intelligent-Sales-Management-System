package com.haowugou.application.operating;

import com.haowugou.domain.inventory.InventoryItem;
import com.haowugou.domain.inventory.InventoryRepository;
import com.haowugou.domain.sales.StoreDailySales;
import com.haowugou.domain.sales.StoreDailySalesRepository;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 经营数据查询模块，为不同入口提供统一的门店校验与查询编排。
 */
public final class OperatingDataQuery {

    private final StoreRepository storeRepository;
    private final StoreDailySalesRepository salesRepository;
    private final InventoryRepository inventoryRepository;

    public OperatingDataQuery(
            StoreRepository storeRepository,
            StoreDailySalesRepository salesRepository,
            InventoryRepository inventoryRepository) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.salesRepository = Objects.requireNonNull(salesRepository);
        this.inventoryRepository = Objects.requireNonNull(inventoryRepository);
    }

    /**
     * 列出所有启用门店。
     */
    public List<Store> listStores() {
        return List.copyOf(storeRepository.findAllActive());
    }

    /**
     * 查询门店日销售；门店不可用时抛出异常，门店存在但当日无数据时返回空。
     */
    public Optional<StoreDailySales> findDailySales(long storeId, LocalDate businessDate) {
        requireValidQuery(storeId, businessDate, "营业日期");
        return salesRepository.findByStoreAndDate(storeId, businessDate);
    }

    /**
     * 查询门店某日的库存快照；门店不可用时抛出异常，没有快照时返回空列表。
     */
    public List<InventoryItem> listInventory(long storeId, LocalDate snapshotDate) {
        requireValidQuery(storeId, snapshotDate, "库存快照日期");
        return List.copyOf(inventoryRepository.findByStoreAndDate(storeId, snapshotDate));
    }

    private void requireValidQuery(long storeId, LocalDate date, String dateLabel) {
        if (storeId <= 0) {
            throw new InvalidOperatingDataQueryException("门店ID必须大于0");
        }
        if (date == null) {
            throw new InvalidOperatingDataQueryException(dateLabel + "不能为空");
        }
        if (!storeRepository.existsActiveById(storeId)) {
            throw new StoreNotFoundException(storeId);
        }
    }
}
