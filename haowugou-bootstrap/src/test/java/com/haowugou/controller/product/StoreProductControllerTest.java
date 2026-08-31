package com.haowugou.controller.product;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.haowugou.application.product.StoreProductQuery;
import com.haowugou.controller.ApiExceptionHandler;
import com.haowugou.domain.pagination.PageResult;
import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.PeriodSalesMetrics;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 从 HTTP 接口验证门店商品 Controller 的公开契约。
 *
 * <p>测试使用真实应用查询模块和内存 Repository 替身，只替换 I/O Adapter，避免依赖 MySQL 或内部实现细节。
 */
class StoreProductControllerTest {

    private static final long STORE_ID = 1L;
    private static final long WAREHOUSE_ID = 5L;
    private static final long PRODUCT_ID = 10L;
    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 8, 7);

    private static final PeriodSalesMetrics METRICS = new PeriodSalesMetrics(
            new BigDecimal("8.000"),
            new BigDecimal("40.00"),
            new BigDecimal("8.00"));

    private MockMvc mockMvc;
    private RecordingProductRepository products;

    @BeforeEach
    void setUp() {
        products = new RecordingProductRepository();
        StoreProductQuery storeProductQuery = new StoreProductQuery(
                storeRepository(), warehouseRepository(), products);
        JsonMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new StoreProductController(storeProductQuery))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void listProductsExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("startDate", START_DATE.toString())
                        .param("endDate", END_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "store":{"id":1,"storeCode":"S-001","storeName":"城南店"},
                          "items":[{
                            "productId":10,
                            "barcode":"9556155017024",
                            "productName":"130g花王香皂",
                            "unit":"块",
                            "categoryId":3,
                            "categoryName":"香皂",
                            "warehouseId":5,
                            "warehouseCode":"W-01",
                            "warehouseName":"日化仓",
                            "supplierNames":["天和日化"],
                            "currentQuantity":-3.000,
                            "inventoryStatus":"NEGATIVE",
                            "dataStatus":"ACTIVE",
                            "periodSalesQuantity":8.000,
                            "periodSalesAmount":40.00,
                            "periodGrossProfitAmount":8.00
                          }],
                          "page":0,
                          "size":20,
                          "totalElements":1,
                          "totalPages":1
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void listProductsWithoutDateRangeReturnsNullSalesMetrics() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].periodSalesQuantity").value(nullValue()))
                .andExpect(jsonPath("$.items[0].periodSalesAmount").value(nullValue()))
                .andExpect(jsonPath("$.items[0].periodGrossProfitAmount").value(nullValue()));
    }

    @Test
    void listProductsForwardsAllFiltersWithinTheStoreScope() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("keyword", "9556")
                        .param("categoryId", "3")
                        .param("supplierId", "7")
                        .param("warehouseId", String.valueOf(WAREHOUSE_ID))
                        .param("inventoryStatus", "NEGATIVE")
                        .param("dataStatus", "ACTIVE")
                        .param("minStock", "-10.000")
                        .param("maxStock", "0")
                        .param("startDate", START_DATE.toString())
                        .param("endDate", END_DATE.toString())
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk());

        StoreProductQueryCriteria criteria = products.criteria;
        assertEquals(new StoreProductQueryCriteria(
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
                20), criteria);
    }

    @Test
    void productDetailExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products/{productId}", STORE_ID, PRODUCT_ID)
                        .param("startDate", START_DATE.toString())
                        .param("endDate", END_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "store":{"id":1,"storeCode":"S-001","storeName":"城南店"},
                          "productId":10,
                          "barcode":"9556155017024",
                          "productName":"130g花王香皂",
                          "unit":"块",
                          "categoryId":3,
                          "categoryCode":"C-03",
                          "categoryName":"香皂",
                          "warehouseId":5,
                          "warehouseCode":"W-01",
                          "warehouseName":"日化仓",
                          "taxCostPrice":4.0000,
                          "salePrice":5.0000,
                          "remarks":"主推款",
                          "supplierNames":["天和日化"],
                          "currentQuantity":-3.000,
                          "inventoryStatus":"NEGATIVE",
                          "dataStatus":"ACTIVE",
                          "periodSalesQuantity":8.000,
                          "periodSalesAmount":40.00,
                          "periodGrossProfitAmount":8.00
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void warehousesExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/warehouses", STORE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [
                          {"id":5,"storeId":1,"warehouseCode":"W-01","warehouseName":"日化仓"}
                        ]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void unknownStoreReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", 99L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("门店不存在或未启用"))
                .andExpect(jsonPath("$.detail").value("门店不存在或未启用: 99"));
        mockMvc.perform(get("/api/stores/{storeId}/warehouses", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("门店不存在或未启用"));
    }

    @Test
    void productWithoutStoreInventoryReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products/{productId}", STORE_ID, 999L))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("门店商品不存在"))
                .andExpect(jsonPath("$.detail").value("商品未在指定门店建立库存关系: storeId=1, productId=999"));
    }

    @Test
    void warehouseFromAnotherStoreReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("warehouseId", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("仓库不属于指定门店: storeId=1, warehouseId=9"));
    }

    @Test
    void invalidPaginationReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("每页数量必须在1到100之间"));
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("每页数量必须在1到100之间"));
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("页码不能小于0"));
    }

    @Test
    void invalidDateRangeReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("startDate", START_DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("开始日期和结束日期必须同时提供"));
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("startDate", END_DATE.toString())
                        .param("endDate", START_DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("开始日期不能晚于结束日期"));
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("startDate", "2026/08/01")
                        .param("endDate", END_DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("请求参数格式错误: startDate"));
    }

    @Test
    void invalidStockRangeReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("minStock", "10")
                        .param("maxStock", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("最小库存不能大于最大库存"));
    }

    @Test
    void invalidEnumValueReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", STORE_ID)
                        .param("inventoryStatus", "BAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("请求参数格式错误: inventoryStatus"));
    }

    @Test
    void nonPositiveStoreIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/stores/{storeId}/products", 0L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("门店ID必须大于0"));
    }

    private StoreRepository storeRepository() {
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

    private WarehouseRepository warehouseRepository() {
        return new WarehouseRepository() {
            @Override
            public List<WarehouseSummary> findAllActiveByStoreId(long storeId) {
                return storeId == STORE_ID
                        ? List.of(new WarehouseSummary(WAREHOUSE_ID, storeId, "W-01", "日化仓"))
                        : List.of();
            }

            @Override
            public boolean existsByStoreIdAndId(long storeId, long warehouseId) {
                return storeId == STORE_ID && warehouseId == WAREHOUSE_ID;
            }
        };
    }

    private static final class RecordingProductRepository implements StoreProductQueryRepository {
        private StoreProductQueryCriteria criteria;

        @Override
        public PageResult<StoreProductListItem> findPage(StoreProductQueryCriteria criteria) {
            this.criteria = criteria;
            return new PageResult<>(List.of(item(criteria.hasSalesPeriod())), criteria.page(), criteria.size(), 1, 1);
        }

        @Override
        public Optional<StoreProductDetail> findDetail(
                long storeId, long productId, LocalDate startDate, LocalDate endDate) {
            if (storeId != STORE_ID || productId != PRODUCT_ID) {
                return Optional.empty();
            }
            return Optional.of(detail(startDate != null));
        }

        private StoreProductListItem item(boolean withMetrics) {
            return new StoreProductListItem(
                    PRODUCT_ID,
                    "9556155017024",
                    "130g花王香皂",
                    "块",
                    3L,
                    "香皂",
                    WAREHOUSE_ID,
                    "W-01",
                    "日化仓",
                    List.of("天和日化"),
                    new BigDecimal("-3.000"),
                    InventoryStatus.NEGATIVE,
                    ProductDataStatus.ACTIVE,
                    withMetrics ? METRICS : null);
        }

        private StoreProductDetail detail(boolean withMetrics) {
            return new StoreProductDetail(
                    PRODUCT_ID,
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
                    "主推款",
                    List.of("天和日化"),
                    new BigDecimal("-3.000"),
                    InventoryStatus.NEGATIVE,
                    ProductDataStatus.ACTIVE,
                    withMetrics ? METRICS : null);
        }
    }
}