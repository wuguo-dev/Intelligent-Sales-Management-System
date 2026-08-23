package com.haowugou.controller.operating;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.haowugou.application.operating.OperatingDataQuery;
import com.haowugou.controller.ApiExceptionHandler;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 从 HTTP 接口验证经营数据 Controller 的公开契约。
 *
 * <p>测试使用真实应用查询模块和内存 Repository 替身，只替换 I/O Adapter，避免依赖 MySQL 或内部实现细节。
 */
class OperatingDataControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 23);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OperatingDataQuery operatingDataQuery = new OperatingDataQuery(
                storeRepository(),
                salesRepository(),
                inventoryRepository());
        JsonMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new OperatingDataController(operatingDataQuery))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void listStoresExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/stores"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [
                          {"id":1,"storeCode":"STORE_001","storeName":"好物购一店"},
                          {"id":2,"storeCode":"STORE_002","storeName":"好物购二店"}
                        ]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void dailySalesExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/sales/daily")
                        .param("storeId", "1")
                        .param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "storeId":1,
                          "businessDate":"2026-08-23",
                          "totalSalesAmount":3896.20,
                          "orderCount":132,
                          "refundAmount":23.80,
                          "grossProfitAmount":1358.60,
                          "dataOrigin":"DEMO"
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void inventoryExposesStableJsonContract() throws Exception {
        mockMvc.perform(get("/api/inventory")
                        .param("storeId", "1")
                        .param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{
                          "productId":1,
                          "barcode":"6941335429200",
                          "productName":"KM-6767台灯",
                          "unit":"个",
                          "categoryCode":"0501",
                          "categoryName":"小家电",
                          "snapshotDate":"2026-08-23",
                          "quantity":8.000,
                          "unitCost":16.50,
                          "salePrice":23.80,
                          "dataOrigin":"DEMO"
                        }]
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void missingOrMalformedQueryParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/sales/daily").param("date", DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("缺少请求参数: storeId"));
        mockMvc.perform(get("/api/sales/daily").param("storeId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("缺少请求参数: date"));
        mockMvc.perform(get("/api/sales/daily")
                        .param("storeId", "1")
                        .param("date", "2026/08/23"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("请求参数格式错误: date"));
    }

    @Test
    void nonPositiveStoreIdReturnsBadRequestProblemDetail() throws Exception {
        mockMvc.perform(get("/api/inventory")
                        .param("storeId", "0")
                        .param("date", DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("门店ID必须大于0"));
    }

    @Test
    void unknownStoreReturnsNotFoundProblemDetail() throws Exception {
        mockMvc.perform(get("/api/inventory")
                        .param("storeId", "99")
                        .param("date", DATE.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("门店不存在或未启用"))
                .andExpect(jsonPath("$.detail").value("门店不存在或未启用: 99"));
    }

    @Test
    void knownStoreWithoutDailySalesReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/sales/daily")
                        .param("storeId", "2")
                        .param("date", DATE.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    private StoreRepository storeRepository() {
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

    private StoreDailySalesRepository salesRepository() {
        return (storeId, date) -> storeId == 1L
                ? Optional.of(new StoreDailySales(
                        10L,
                        storeId,
                        date,
                        new BigDecimal("3896.20"),
                        132,
                        new BigDecimal("23.80"),
                        new BigDecimal("1358.60"),
                        "DEMO"))
                : Optional.empty();
    }

    private InventoryRepository inventoryRepository() {
        return (storeId, date) -> storeId == 1L
                ? List.of(new InventoryItem(
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
                        "DEMO"))
                : List.of();
    }
}
