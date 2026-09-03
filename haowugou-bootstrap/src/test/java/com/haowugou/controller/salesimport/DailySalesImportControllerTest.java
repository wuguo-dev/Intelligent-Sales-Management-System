package com.haowugou.controller.salesimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.haowugou.application.salesimport.PostDailySalesImport;
import com.haowugou.controller.ApiExceptionHandler;
import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.salesimport.DailySalesFileParser;
import com.haowugou.domain.salesimport.DailySalesImportRepository;
import com.haowugou.domain.salesimport.DailySalesPosting;
import com.haowugou.domain.salesimport.ParsedSalesFile;
import com.haowugou.domain.salesimport.ParsedSalesRow;
import com.haowugou.domain.salesimport.SalesMovement;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 从 HTTP 接口验证每日销售导入 Controller 的公开契约。
 *
 * <p>测试使用真实导入用例和内存替身（Repository / 解析器），只替换 I/O Adapter，
 * 不依赖 MySQL 或 EasyExcel 内部实现。
 */
class DailySalesImportControllerTest {

    private static final long STORE_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 29);
    private static final String FILE_NAME = "商品销售汇总.xls";

    private MockMvc mockMvc;
    private RecordingSalesRepository sales;
    private List<ParsedSalesRow> parsedRows;

    @BeforeEach
    void setUp() {
        sales = new RecordingSalesRepository();
        sales.productIds.put("9556155017024", 10L);
        sales.supplierIds.put("好物购供应链", 7L);
        sales.categoryIds.put("休闲零食", 3L);
        parsedRows = List.of(
                row(2L, "9556155017024", "薯片", "3", "36.00", "25.5", "8.00", "12.00",
                        "休闲零食", "好物购供应链"),
                row(3L, "0000001234567", "巧克力", "2", "40.00", "30", "14.00", "20.00",
                        "休闲零食", "好物购供应链"));

        PostDailySalesImport useCase =
                new PostDailySalesImport(storeRepository(), sales, fileParser(), () -> TODAY);
        JsonMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new DailySalesImportController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void postsValidFileReturnsPostedContract() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "batchId":"42",
                          "status":"POSTED",
                          "totalRows":2,
                          "successRows":2,
                          "errorRows":0,
                          "salesRows":2,
                          "pendingProductsCreated":1,
                          "deductedProducts":2,
                          "errors":[]
                        }
                        """, JsonCompareMode.STRICT));

        assertNotNull(sales.posted);
        assertEquals(STORE_ID, sales.posted.storeId());
        assertEquals(FILE_NAME, sales.posted.fileName());
        assertEquals(BUSINESS_DATE, sales.posted.businessDate());
        assertEquals(2, sales.posted.rawRows().size());
        assertEquals(2, sales.posted.factRows().size());
        assertEquals(1, sales.posted.pendingProducts().size());
        assertEquals("0000001234567", sales.posted.pendingProducts().getFirst().barcode());
        assertEquals(3L, sales.posted.pendingProducts().getFirst().categoryId());
        assertEquals(7L, sales.posted.factRows().getFirst().supplierId());
    }

    /** 销售出库要写成负变化量，退货要写成正变化量，否则库存越卖越多。 */
    @Test
    void deductsInventoryWithNegativeQuantityAndReturnsWithPositive() throws Exception {
        parsedRows = List.of(
                row(2L, "9556155017024", "薯片", "3", "36.00", "25.5", "8.00", "12.00",
                        "休闲零食", "好物购供应链"),
                row(3L, "0000001234567", "巧克力", "-2", "-40.00", "30", "14.00", "20.00",
                        "休闲零食", "好物购供应链"));

        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deductedProducts").value(2));

        List<SalesMovement> movements = sales.posted.movements();
        assertEquals(SalesMovement.TYPE_SALE_OUT, movements.getFirst().movementType());
        assertEquals(new BigDecimal("-3"), movements.getFirst().quantityChange());
        assertEquals(SalesMovement.TYPE_SALE_RETURN, movements.get(1).movementType());
        assertEquals(new BigDecimal("2"), movements.get(1).quantityChange());
    }

    /** 数量与收入同时为 0 的行只留档，不进销售事实、不产生流水、不建待完善商品。 */
    @Test
    void zeroSalesRowIsArchivedOnly() throws Exception {
        parsedRows = List.of(
                row(2L, "9556155017024", "薯片", "3", "36.00", "25.5", "8.00", "12.00",
                        "休闲零食", "好物购供应链"),
                row(3L, "0000009999999", "未上架商品", "0", "0", "", "", "",
                        "休闲零食", "好物购供应链"));

        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "batchId":"42",
                          "status":"POSTED",
                          "totalRows":2,
                          "successRows":2,
                          "errorRows":0,
                          "salesRows":1,
                          "pendingProductsCreated":0,
                          "deductedProducts":1,
                          "errors":[]
                        }
                        """, JsonCompareMode.STRICT));

        assertEquals(2, sales.posted.rawRows().size());
    }

    @Test
    void rowErrorReturnsFailedContract() throws Exception {
        parsedRows = List.of(
                row(2L, "9556155017024", "薯片", "3", "36.00", "25.5", "8.00", "12.00",
                        "休闲零食", "好物购供应链"),
                row(3L, "9556155017024", "薯片", "1", "12.00", "25.5", "8.00", "12.00",
                        "休闲零食", "好物购供应链"));

        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "batchId":"43",
                          "status":"FAILED",
                          "totalRows":2,
                          "successRows":0,
                          "errorRows":1,
                          "salesRows":0,
                          "pendingProductsCreated":0,
                          "deductedProducts":0,
                          "errors":[
                            {
                              "rowNumber":3,
                              "barcode":"9556155017024",
                              "message":"同条码同供应商在文件中重复（首次出现于第2行）: 9556155017024"
                            }
                          ]
                        }
                        """, JsonCompareMode.STRICT));

        assertNotNull(sales.failed);
        assertEquals(BUSINESS_DATE, sales.failed.dataDate());
        assertEquals(2, sales.failed.rows().size());
    }

    @Test
    void missingBusinessDateReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("缺少请求参数: businessDate"));
    }

    @Test
    void malformedBusinessDateReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", "2026/08/29"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("请求参数格式错误: businessDate"));
    }

    @Test
    void futureBusinessDateReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", TODAY.plusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("销售业务日期不能晚于今天: 2026-08-31"));
    }

    @Test
    void missingFilePartReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("缺少上传文件: file"));
    }

    @Test
    void nonMultipartRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/sales/import", STORE_ID)
                        .param("businessDate", BUSINESS_DATE.toString())
                        .param("file", FILE_NAME))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail")
                        .value("请求不是 multipart/form-data 表单，请以 form-data 方式上传 file 文件参数"));
    }

    @Test
    void unsupportedExtensionReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(new MockMultipartFile(
                                "file", "销售.csv", "text/csv", new byte[]{1, 2, 3}))
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("请求参数错误"))
                .andExpect(jsonPath("$.detail").value("仅支持 .xls 或 .xlsx 文件: 销售.csv"));
    }

    @Test
    void unknownStoreReturnsNotFound() throws Exception {
        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", 99L)
                        .file(validFile())
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("门店不存在或未启用"))
                .andExpect(jsonPath("$.detail").value("门店不存在或未启用: 99"));
    }

    @Test
    void duplicateFileReturnsConflict() throws Exception {
        sales.fileHashExists = true;

        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("导入冲突"))
                .andExpect(jsonPath("$.detail").value("该销售文件已导入过: " + FILE_NAME));
    }

    @Test
    void existingPostedBatchForThatDateReturnsConflict() throws Exception {
        sales.postedSalesBatch = true;

        mockMvc.perform(multipart("/api/stores/{storeId}/sales/import", STORE_ID)
                        .file(validFile())
                        .param("businessDate", BUSINESS_DATE.toString()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("导入冲突"))
                .andExpect(jsonPath("$.detail")
                        .value("门店 " + STORE_ID + " 在 " + BUSINESS_DATE + " 已有有效销售批次"));
    }

    private static ParsedSalesRow row(
            long rowNumber,
            String barcode,
            String productName,
            String salesQuantity,
            String salesAmount,
            String grossProfitRate,
            String taxCostPrice,
            String salePrice,
            String categoryName,
            String supplierName) {
        return new ParsedSalesRow(
                rowNumber, barcode, productName, salesQuantity, salesAmount, grossProfitRate,
                taxCostPrice, salePrice, categoryName, supplierName,
                "{\"条码\":\"" + barcode + "\",\"本期销售数量\":\"" + salesQuantity + "\"}");
    }

    private MockMultipartFile validFile() {
        return new MockMultipartFile(
                "file", FILE_NAME, "application/vnd.ms-excel", new byte[]{1, 2, 3});
    }

    private DailySalesFileParser fileParser() {
        return (content, fileName) -> new ParsedSalesFile(parsedRows);
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

    private static final class RecordingSalesRepository implements DailySalesImportRepository {

        private boolean fileHashExists;
        private boolean postedSalesBatch;
        private final Map<String, Long> productIds = new LinkedHashMap<>();
        private final Map<String, Long> categoryIds = new LinkedHashMap<>();
        private final Map<String, Long> supplierIds = new LinkedHashMap<>();
        private DailySalesPosting posted;
        private ImportFailure failed;

        @Override
        public boolean existsFileHash(long storeId, String fileHash) {
            return fileHashExists;
        }

        @Override
        public boolean existsPostedSalesBatch(long storeId, LocalDate businessDate) {
            return postedSalesBatch;
        }

        @Override
        public Map<String, Long> findProductIdsByBarcodes(List<String> barcodes) {
            return filter(productIds, barcodes);
        }

        @Override
        public Map<String, Long> findCategoryIdsByNames(List<String> categoryNames) {
            return filter(categoryIds, categoryNames);
        }

        @Override
        public Map<String, Long> findSupplierIdsByNames(List<String> supplierNames) {
            return filter(supplierIds, supplierNames);
        }

        @Override
        public long postBatch(DailySalesPosting posting) {
            this.posted = posting;
            return 42L;
        }

        @Override
        public long saveFailedBatch(ImportFailure failure) {
            this.failed = failure;
            return 43L;
        }

        private Map<String, Long> filter(Map<String, Long> source, List<String> keys) {
            Map<String, Long> found = new LinkedHashMap<>();
            for (String key : keys) {
                Long id = source.get(key);
                if (id != null) {
                    found.put(key, id);
                }
            }
            return found;
        }
    }
}
