package com.haowugou.application.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haowugou.application.operating.exception.StoreNotFoundException;
import com.haowugou.application.product.exception.InvalidStoreProductQueryException;
import com.haowugou.application.product.exception.StoreProductNotFoundException;
import com.haowugou.application.product.exception.WarehouseNotInStoreException;
import com.haowugou.domain.pagination.PageResult;
import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.ProductDataStatus;
import com.haowugou.domain.product.StoreProductDetail;
import com.haowugou.domain.product.StoreProductListItem;
import com.haowugou.domain.product.StoreProductQueryCriteria;
import com.haowugou.domain.product.StoreProductQueryRepository;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import com.haowugou.domain.warehouse.WarehouseSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoreProductQueryTest {

    private static final long STORE_ID = 1L;
    private static final long WAREHOUSE_ID = 5L;
    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 8, 7);

    @Test
    void forwardsAllFiltersWithinTheValidatedStoreScope() {
        RecordingProductRepository products = new RecordingProductRepository();
        StoreProductQuery query = query(activeStores(), warehouses(), products);
        StoreProductQueryCriteria criteria = new StoreProductQueryCriteria(
                STORE_ID,
                "9556",
                3L,
                7L,
                WAREHOUSE_ID,
                InventoryStatus.NEGATIVE,
                ProductDataStatus.ACTIVE,
                new BigDecimal("-10.000"),
                BigDecimal.ZERO,
                START_DATE,
                END_DATE,
                2,
                20);

        StoreProductPageResult result = query.listProducts(criteria);

        assertEquals(STORE_ID, result.store().id());
        assertSame(criteria, products.criteria);
        assertEquals(2, result.products().page());
    }

    @Test
    void returnsDetailAndWarehousesOnlyForTheRequestedStore() {
        RecordingProductRepository products = new RecordingProductRepository();
        StoreProductQuery query = query(activeStores(), warehouses(), products);

        StoreProductDetailResult detail = query.findProduct(STORE_ID, 10L, START_DATE, END_DATE);
        List<WarehouseSummary> warehouseResults = query.listWarehouses(STORE_ID);

        assertEquals(STORE_ID, detail.store().id());
        assertEquals(10L, detail.product().productId());
        assertEquals(STORE_ID, products.detailStoreId);
        assertEquals(START_DATE, products.detailStartDate);
        assertEquals(List.of(WAREHOUSE_ID), warehouseResults.stream().map(WarehouseSummary::id).toList());
    }

    @Test
    void rejectsInvalidRangesAndPaginationBeforeReadingRepositories() {
        StoreProductQuery query = query(failingStores(), failingWarehouses(), failingProducts());

        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(null));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(criteria(-1, 20, null, null)));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(criteria(0, 0, null, null)));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(criteria(0, 101, null, null)));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(new StoreProductQueryCriteria(
                STORE_ID, null, null, null, null, null, null,
                BigDecimal.ONE, BigDecimal.ZERO, null, null, 0, 20)));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(criteria(0, 20, START_DATE, null)));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(criteria(0, 20, END_DATE, START_DATE)));
    }

    @Test
    void rejectsNonPositiveOptionalIdentifiersBeforeReadingRepositories() {
        StoreProductQuery query = query(failingStores(), failingWarehouses(), failingProducts());

        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(new StoreProductQueryCriteria(
                STORE_ID, null, 0L, null, null, null, null, null, null, null, null, 0, 20)));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(new StoreProductQueryCriteria(
                STORE_ID, null, null, -1L, null, null, null, null, null, null, null, 0, 20)));
        assertThrows(InvalidStoreProductQueryException.class, () -> query.listProducts(new StoreProductQueryCriteria(
                STORE_ID, null, null, null, 0L, null, null, null, null, null, null, 0, 20)));
    }

    @Test
    void rejectsUnknownStoreBeforeProductOrWarehouseQueries() {
        StoreProductQuery query = query(activeStores(), failingWarehouses(), failingProducts());

        assertThrows(StoreNotFoundException.class, () -> query.listProducts(new StoreProductQueryCriteria(
                99L, null, null, null, null, null, null, null, null, null, null, 0, 20)));
        assertThrows(StoreNotFoundException.class, () -> query.findProduct(99L, 10L, null, null));
        assertThrows(StoreNotFoundException.class, () -> query.listWarehouses(99L));
    }

    @Test
    void rejectsWarehouseFromAnotherStoreBeforeProductQuery() {
        WarehouseRepository warehouses = new WarehouseRepository() {
            @Override
            public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
                return List.of();
            }

            @Override
            public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
                return false;
            }
        };
        StoreProductQuery query = query(activeStores(), warehouses, failingProducts());

        assertThrows(WarehouseNotInStoreException.class, () -> query.listProducts(new StoreProductQueryCriteria(
                STORE_ID, null, null, null, 99L, null, null, null, null, null, null, 0, 20)));
    }

    @Test
    void reportsProductWithoutStoreInventoryAsNotFound() {
        StoreProductQueryRepository products = new StoreProductQueryRepository() {
            @Override
            public PageResult<StoreProductListItem> findPage(StoreProductQueryCriteria criteria) {
                return new PageResult<>(List.of(), 0, 20, 0, 0);
            }

            @Override
            public Optional<StoreProductDetail> findDetail(
                    long storeId, long productId, LocalDate startDate, LocalDate endDate) {
                return Optional.empty();
            }
        };
        StoreProductQuery query = query(activeStores(), warehouses(), products);

        assertThrows(StoreProductNotFoundException.class, () -> query.findProduct(STORE_ID, 10L, null, null));
    }

    private StoreProductQueryCriteria criteria(int page, int size, LocalDate startDate, LocalDate endDate) {
        return new StoreProductQueryCriteria(
                STORE_ID, null, null, null, null, null, null,
                null, null, startDate, endDate, page, size);
    }

    private StoreProductQuery query(
            StoreRepository stores,
            WarehouseRepository warehouses,
            StoreProductQueryRepository products) {
        return new StoreProductQuery(stores, warehouses, products);
    }

    private StoreRepository activeStores() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                return List.of(new Store(STORE_ID, "S-001", "城南店"));
            }

            @Override
            public boolean existsActiveById(long storeId) {
                return storeId == STORE_ID;
            }
        };
    }

    private StoreRepository failingStores() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                throw new AssertionError("非法参数不应查询门店");
            }

            @Override
            public boolean existsActiveById(long storeId) {
                throw new AssertionError("非法参数不应查询门店");
            }
        };
    }

    private WarehouseRepository warehouses() {
        return new WarehouseRepository() {
            @Override
            public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
                return List.of(new WarehouseSummary(WAREHOUSE_ID, storeId, "W-01", "日化仓"));
            }

            @Override
            public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
                return storeId == STORE_ID && warehouseId == WAREHOUSE_ID;
            }
        };
    }

    private WarehouseRepository failingWarehouses() {
        return new WarehouseRepository() {
            @Override
            public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
                throw new AssertionError("不应查询仓库");
            }

            @Override
            public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
                throw new AssertionError("不应查询仓库");
            }
        };
    }

    private StoreProductQueryRepository failingProducts() {
        return new StoreProductQueryRepository() {
            @Override
            public PageResult<StoreProductListItem> findPage(StoreProductQueryCriteria criteria) {
                throw new AssertionError("不应查询商品");
            }

            @Override
            public Optional<StoreProductDetail> findDetail(
                    long storeId, long productId, LocalDate startDate, LocalDate endDate) {
                throw new AssertionError("不应查询商品");
            }
        };
    }

    private static final class RecordingProductRepository implements StoreProductQueryRepository {
        private StoreProductQueryCriteria criteria;
        private long detailStoreId;
        private LocalDate detailStartDate;

        @Override
        public PageResult<StoreProductListItem> findPage(StoreProductQueryCriteria criteria) {
            this.criteria = criteria;
            return new PageResult<>(List.of(), criteria.page(), criteria.size(), 0, 0);
        }

        @Override
        public Optional<StoreProductDetail> findDetail(
                long storeId, long productId, LocalDate startDate, LocalDate endDate) {
            detailStoreId = storeId;
            detailStartDate = startDate;
            return Optional.of(new StoreProductDetail(
                    productId,
                    "9556155017024",
                    "130g花王香皂",
                    "块",
                    3L,
                    "C-03",
                    "香皂",
                    WAREHOUSE_ID,
                    "W-01",
                    "日化仓",
                    new BigDecimal("4.0000"),
                    new BigDecimal("5.0000"),
                    null,
                    List.of("天和日化"),
                    new BigDecimal("-3.000"),
                    InventoryStatus.NEGATIVE,
                    ProductDataStatus.ACTIVE,
                    null));
        }
    }
}
