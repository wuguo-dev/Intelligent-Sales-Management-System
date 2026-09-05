package com.haowugou.application.operating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haowugou.application.operating.exception.InvalidOperatingDataQueryException;
import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.domain.inventory.InventoryItem;
import com.haowugou.domain.inventory.InventoryRepository;
import com.haowugou.domain.sales.StoreDailySales;
import com.haowugou.domain.sales.StoreDailySalesRepository;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OperatingDataQueryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 23);

    @Test
    void returnsSalesAndInventoryThroughTheSameApplicationSeam() {
        StoreRepository stores = activeStoreRepository();
        StoreDailySalesRepository sales = (storeId, date) -> Optional.of(new StoreDailySales(
                10L,
                storeId,
                date,
                new BigDecimal("3896.20"),
                132,
                new BigDecimal("23.80"),
                new BigDecimal("1358.60"),
                "DEMO"));
        InventoryRepository inventory = (storeId, date) -> List.of(new InventoryItem(
                storeId,
                1L,
                "6941335429200",
                "KM-6767台灯",
                "个",
                "0501",
                "小家电",
                date,
                new BigDecimal("8.000"),
                new BigDecimal("16.50"),
                new BigDecimal("23.80"),
                "DEMO"));

        OperatingDataQuery query = new OperatingDataQuery(stores, sales, inventory);

        assertEquals(2, query.listStores().size());
        assertEquals(new BigDecimal("3896.20"), query.findDailySales(1L, DATE).orElseThrow().totalSalesAmount());
        assertEquals("6941335429200", query.listInventory(1L, DATE).getFirst().barcode());
    }

    @Test
    void rejectsUnknownStoreBeforeQueryingFacts() {
        OperatingDataQuery query = new OperatingDataQuery(
                activeStoreRepository(),
                (storeId, date) -> {
                    throw new AssertionError("未知门店不应查询销售仓库");
                },
                (storeId, date) -> {
                    throw new AssertionError("未知门店不应查询库存仓库");
                });

        assertThrows(StoreNotFoundException.class, () -> query.findDailySales(99L, DATE));
        assertThrows(StoreNotFoundException.class, () -> query.listInventory(99L, DATE));
    }

    @Test
    void rejectsInvalidParametersBeforeReadingRepositories() {
        StoreRepository stores = new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                throw new AssertionError("非法参数不应查询门店仓库");
            }

            @Override
            public boolean existsActiveById(long storeId) {
                throw new AssertionError("非法参数不应查询门店仓库");
            }
        };
        OperatingDataQuery query = new OperatingDataQuery(
                stores,
                (storeId, date) -> Optional.empty(),
                (storeId, date) -> List.of());

        assertThrows(
                InvalidOperatingDataQueryException.class,
                () -> query.findDailySales(0L, DATE));
        assertThrows(
                InvalidOperatingDataQueryException.class,
                () -> query.listInventory(1L, null));
    }

    private StoreRepository activeStoreRepository() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                return List.of(
                        new Store(1L, "STORE_001", "好物购一店"),
                        new Store(2L, "STORE_002", "好物购二店"));
            }

            @Override
            public boolean existsActiveById(long storeId) {
                return storeId == 1L || storeId == 2L;
            }
        };
    }
}
