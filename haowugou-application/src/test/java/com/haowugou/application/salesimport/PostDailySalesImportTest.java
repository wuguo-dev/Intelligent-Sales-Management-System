package com.haowugou.application.salesimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.application.operating.StoreNotFoundException;
import com.haowugou.domain.importbatch.ImportFailure;
import com.haowugou.domain.importbatch.ImportFileFormatException;
import com.haowugou.domain.importbatch.ImportRowError;
import com.haowugou.domain.salesimport.DailySalesFactRow;
import com.haowugou.domain.salesimport.DailySalesFileParser;
import com.haowugou.domain.salesimport.DailySalesImportRepository;
import com.haowugou.domain.salesimport.DailySalesImportResult;
import com.haowugou.domain.salesimport.DailySalesPosting;
import com.haowugou.domain.salesimport.ParsedSalesFile;
import com.haowugou.domain.salesimport.ParsedSalesRow;
import com.haowugou.domain.salesimport.PendingProductDraft;
import com.haowugou.domain.salesimport.SalesMovement;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostDailySalesImportTest {

    private static final long STORE_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 29);
    private static final String FILE_NAME = "商品销售汇总.xls";
    private static final byte[] CONTENT = "daily-sales".getBytes(StandardCharsets.UTF_8);

    private RecordingSalesRepository sales;
    private StubSalesParser parser;
    private Set<Long> activeStores;

    @BeforeEach
    void setUp() {
        sales = new RecordingSalesRepository();
        parser = new StubSalesParser();
        activeStores = new HashSet<>(List.of(STORE_ID));
    }

    @Test
    void postsSalesFactsAndDeductsInventorySkippingZeroRows() {
        parser.rows = List.of(
                row(2, "6901294177017", "康师傅", "3", "15.0", "10", "天和日化"),
                row(3, "6901294177018", "农夫山泉", "-2", "-4", "25", "长沙合悦"),
                row(4, "6901294177019", "未销售商品", "0", "0", "0", "天和日化"));
        sales.productIds = Map.of(
                "6901294177017", 10L, "6901294177018", 11L, "6901294177019", 12L);
        sales.supplierIds = Map.of("天和日化", 50L, "长沙合悦", 51L);

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals(7L, result.batchId());
        assertEquals("POSTED", result.status());
        assertEquals(3, result.totalRows());
        assertEquals(3, result.successRows());
        assertEquals(0, result.errorRows());
        assertEquals(2, result.salesRows());
        assertEquals(0, result.pendingProductsCreated());
        assertEquals(2, result.deductedProducts());
        assertTrue(result.errors().isEmpty());
        assertEquals(sha256(CONTENT), sales.lastHash);

        DailySalesPosting posting = sales.posting;
        assertEquals(STORE_ID, posting.storeId());
        assertEquals(BUSINESS_DATE, posting.businessDate());
        assertEquals(3, posting.rawRows().size());
        assertTrue(posting.pendingProducts().isEmpty());
        // 零销售行不查商品、不查供应商、不落事实、不产生流水
        assertEquals(List.of("6901294177017", "6901294177018"), sales.askedBarcodes);
        assertEquals(List.of("天和日化", "长沙合悦"), sales.askedSupplierNames);

        assertEquals(2, posting.factRows().size());
        DailySalesFactRow sold = posting.factRows().getFirst();
        assertEquals("6901294177017", sold.barcode());
        assertEquals(50L, sold.supplierId());
        assertEquals(new BigDecimal("3"), sold.salesQuantity());
        assertEquals(new BigDecimal("15.0"), sold.salesAmount());
        assertEquals(new BigDecimal("1.50"), sold.grossProfitAmount());
        assertEquals(new BigDecimal("10"), sold.reportedRate());
        DailySalesFactRow returned = posting.factRows().get(1);
        assertEquals(new BigDecimal("-2"), returned.salesQuantity());
        assertEquals(new BigDecimal("-1.00"), returned.grossProfitAmount());

        assertEquals(2, posting.movements().size());
        SalesMovement out = posting.movements().getFirst();
        assertEquals("6901294177017", out.barcode());
        assertEquals(new BigDecimal("-3"), out.quantityChange());
        assertEquals("SALE_OUT", out.movementType());
        SalesMovement back = posting.movements().get(1);
        assertEquals(new BigDecimal("2"), back.quantityChange());
        assertEquals("SALE_RETURN", back.movementType());
    }

    @Test
    void createsPendingProductForUnknownBarcodeInsteadOfRejecting() {
        parser.rows = List.of(
                row(2, "6901294177017", "康师傅", "1", "5", "10", "天和日化"),
                new ParsedSalesRow(3, "6901294177099", "新到商品", "2", "9.9", "20",
                        "3.5", "4.95", "日化", "天和日化", "{}"));
        sales.productIds = Map.of("6901294177017", 10L);
        sales.categoryIds = Map.of("日化", 70L);
        sales.supplierIds = Map.of("天和日化", 50L);

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("POSTED", result.status());
        assertEquals(1, result.pendingProductsCreated());
        assertEquals(2, result.salesRows());
        assertEquals(2, result.deductedProducts());
        assertEquals(List.of("日化"), sales.askedCategoryNames);

        List<PendingProductDraft> drafts = sales.posting.pendingProducts();
        assertEquals(1, drafts.size());
        PendingProductDraft draft = drafts.getFirst();
        assertEquals("6901294177099", draft.barcode());
        assertEquals("新到商品", draft.productName());
        assertEquals(70L, draft.categoryId());
        assertEquals(new BigDecimal("3.5"), draft.taxCostPrice());
        assertEquals(new BigDecimal("4.95"), draft.salePrice());
        assertEquals(Map.of("6901294177017", 10L), sales.posting.knownProductIds());
        assertEquals("6901294177099", sales.posting.factRows().get(1).barcode());
    }

    @Test
    void leavesCategoryNullWhenNameUnknownAndRoundsPriceScale() {
        parser.rows = List.of(new ParsedSalesRow(2, "6901294177099", "新到商品", "1", "5", "10",
                "1.23456", "12.5", "不存在的品类", "", "{}"));

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("POSTED", result.status());
        PendingProductDraft draft = sales.posting.pendingProducts().getFirst();
        assertNull(draft.categoryId());
        assertEquals(new BigDecimal("1.2346"), draft.taxCostPrice());
        assertEquals(new BigDecimal("12.5"), draft.salePrice());
        assertNull(sales.posting.factRows().getFirst().supplierId());
    }

    @Test
    void mergesRowsThatResolveToTheSameSupplierKey() {
        parser.rows = List.of(
                row(2, "6901294177017", "康师傅", "3", "10", "10", "天和日化"),
                row(3, "6901294177017", "康师傅", "2", "5", "20", "长沙合悦"));
        sales.productIds = Map.of("6901294177017", 10L);

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("POSTED", result.status());
        assertEquals(1, result.salesRows());
        assertEquals(1, result.deductedProducts());
        DailySalesFactRow merged = sales.posting.factRows().getFirst();
        assertNull(merged.supplierId());
        assertEquals(new BigDecimal("5"), merged.salesQuantity());
        assertEquals(new BigDecimal("15"), merged.salesAmount());
        assertEquals(new BigDecimal("2.00"), merged.grossProfitAmount());
        // 归并后 POS 原始毛利率无法归属单一原始值
        assertNull(merged.reportedRate());
        assertEquals(new BigDecimal("-5"), sales.posting.movements().getFirst().quantityChange());
    }

    @Test
    void keepsFactsButSkipsMovementWhenNetQuantityCancelsOut() {
        parser.rows = List.of(
                row(2, "6901294177017", "康师傅", "2", "8", "10", "天和日化"),
                row(3, "6901294177017", "康师傅", "-2", "-8", "10", "长沙合悦"));
        sales.productIds = Map.of("6901294177017", 10L);
        sales.supplierIds = Map.of("天和日化", 50L, "长沙合悦", 51L);

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("POSTED", result.status());
        assertEquals(2, result.salesRows());
        assertEquals(0, result.deductedProducts());
        assertTrue(sales.posting.movements().isEmpty());
    }

    @Test
    void postsBatchWithoutFactsWhenEveryRowHasNoSales() {
        parser.rows = List.of(row(2, "6901294177017", "康师傅", "0", "0", "0", "天和日化"));

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("POSTED", result.status());
        assertEquals(1, result.totalRows());
        assertEquals(0, result.salesRows());
        assertEquals(0, result.deductedProducts());
        assertEquals(0, result.pendingProductsCreated());
        assertEquals(1, sales.posting.rawRows().size());
        assertTrue(sales.askedBarcodes.isEmpty());
    }

    @Test
    void treatsBlankQuantityAndAmountAsZeroButRejectsUnparsableNumbers() {
        parser.rows = List.of(
                row(2, "A", "空白行", "", "", "", "供应商"),
                row(3, "B", "非法数量", "abc", "1", "10", "供应商"),
                row(4, "C", "非法收入", "1", "x", "10", "供应商"),
                row(5, "D", "非法毛利率", "1", "1", "y", "供应商"));
        sales.productIds = Map.of("A", 1L, "B", 2L, "C", 3L, "D", 4L);

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(4, result.totalRows());
        assertEquals(0, result.successRows());
        assertEquals(3, result.errorRows());
        assertEquals(List.of(3L, 4L, 5L),
                result.errors().stream().map(ImportRowError::rowNumber).toList());
        assertTrue(result.errors().get(0).message().contains("销售数量无法解析"));
        assertTrue(result.errors().get(1).message().contains("销售收入无法解析"));
        assertTrue(result.errors().get(2).message().contains("销售毛利率无法解析"));
        assertNull(sales.posting);
        ImportFailure failure = sales.failure;
        assertEquals(BUSINESS_DATE, failure.dataDate());
        assertEquals(4, failure.rows().size());
        assertNull(failure.rows().getFirst().errorMessage());
        assertTrue(failure.errorSummary().contains("第3行"));
    }

    @Test
    void rejectsOverScaledQuantityAmountAndRate() {
        parser.rows = List.of(
                row(2, "A", "数量4位", "1.0001", "1", "10", "供应商"),
                row(3, "B", "收入3位", "1", "1.001", "10", "供应商"),
                row(4, "C", "毛利率5位", "1", "1", "1.00001", "供应商"));

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(3, result.errorRows());
        assertTrue(result.errors().get(0).message().contains("销售数量小数位"));
        assertTrue(result.errors().get(1).message().contains("销售收入小数位"));
        assertTrue(result.errors().get(2).message().contains("销售毛利率小数位"));
    }

    @Test
    void rejectsBlankMalformedAndOverLongBarcodes() {
        parser.rows = List.of(
                row(2, " ", "空条码", "1", "1", "10", "供应商"),
                row(3, "9.55E12", "非法条码", "1", "1", "10", "供应商"),
                row(4, "1".repeat(65), "超长条码", "1", "1", "10", "供应商"));

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(3, result.errorRows());
        assertTrue(result.errors().get(0).message().contains("条码为空"));
        assertTrue(result.errors().get(1).message().contains("格式非法"));
        assertTrue(result.errors().get(2).message().contains("条码长度"));
    }

    @Test
    void rejectsSameBarcodeAndSupplierTwiceButAllowsDifferentSuppliers() {
        parser.rows = List.of(
                row(2, "6901294177017", "康师傅", "1", "1", "10", "天和日化"),
                row(3, "6901294177017", "康师傅", "2", "2", "10", "长沙合悦"),
                row(4, "6901294177017", "康师傅", "3", "3", "10", "天和日化"));

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(1, result.errorRows());
        assertEquals(4L, result.errors().getFirst().rowNumber());
        assertTrue(result.errors().getFirst().message().contains("同条码同供应商在文件中重复"));
        assertTrue(result.errors().getFirst().message().contains("第2行"));
    }

    @Test
    void rejectsUnknownBarcodeWithoutProductName() {
        parser.rows = List.of(row(2, "6901294177099", " ", "1", "5", "10", "天和日化"));

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT);

        assertEquals("FAILED", result.status());
        assertEquals(1, result.errorRows());
        assertTrue(result.errors().getFirst().message().contains("缺少商品名称"));
        assertNull(sales.posting);
        assertEquals(1, sales.failure.rows().size());
    }

    @Test
    void rejectsUnknownStoreBeforeAnyFileWork() {
        parser.failWith = new ImportFileFormatException("不应被调用");

        assertThrows(StoreNotFoundException.class,
                () -> useCase().importDailySales(99L, BUSINESS_DATE, FILE_NAME, CONTENT));
        assertNull(sales.lastHash);
    }

    @Test
    void rejectsDuplicateFileHashBeforeParsing() {
        sales.fileHashExists = true;
        parser.failWith = new ImportFileFormatException("不应被调用");

        DuplicateSalesFileException exception = assertThrows(DuplicateSalesFileException.class,
                () -> useCase().importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT));
        assertTrue(exception.getMessage().contains(FILE_NAME));
    }

    @Test
    void rejectsExistingPostedBatchForTheSameBusinessDate() {
        sales.postedBatchExists = true;
        parser.failWith = new ImportFileFormatException("不应被调用");

        PostedSalesBatchExistsException exception = assertThrows(
                PostedSalesBatchExistsException.class,
                () -> useCase().importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT));
        assertTrue(exception.getMessage().contains("2026-08-29"));
        assertEquals(BUSINESS_DATE, sales.askedPostedDate);
    }

    @Test
    void rejectsMissingAndFutureBusinessDate() {
        parser.failWith = new ImportFileFormatException("不应被调用");
        PostDailySalesImport useCase = useCase();

        assertThrows(InvalidSalesImportException.class,
                () -> useCase.importDailySales(STORE_ID, null, FILE_NAME, CONTENT));
        InvalidSalesImportException future = assertThrows(InvalidSalesImportException.class,
                () -> useCase.importDailySales(STORE_ID, TODAY.plusDays(1), FILE_NAME, CONTENT));
        assertTrue(future.getMessage().contains("不能晚于今天"));
        assertNull(sales.lastHash);
    }

    @Test
    void acceptsTodayAsBusinessDate() {
        parser.rows = List.of(row(2, "6901294177017", "康师傅", "1", "5", "10", "天和日化"));
        sales.productIds = Map.of("6901294177017", 10L);

        DailySalesImportResult result = useCase()
                .importDailySales(STORE_ID, TODAY, FILE_NAME, CONTENT);

        assertEquals("POSTED", result.status());
        assertEquals(TODAY, sales.posting.businessDate());
    }

    @Test
    void wrapsParserFormatErrorsAsInvalidRequest() {
        parser.failWith = new ImportFileFormatException("表头缺少「条码」");

        InvalidSalesImportException exception = assertThrows(InvalidSalesImportException.class,
                () -> useCase().importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT));
        assertTrue(exception.getMessage().contains("表头缺少"));
        assertNull(sales.posting);
        assertNull(sales.failure);
    }

    @Test
    void rejectsUnsupportedExtensionEmptyContentMissingNameAndBadStoreId() {
        parser.failWith = new ImportFileFormatException("不应被调用");
        PostDailySalesImport useCase = useCase();

        assertThrows(InvalidSalesImportException.class,
                () -> useCase.importDailySales(STORE_ID, BUSINESS_DATE, "销售.csv", CONTENT));
        assertThrows(InvalidSalesImportException.class,
                () -> useCase.importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, new byte[0]));
        assertThrows(InvalidSalesImportException.class,
                () -> useCase.importDailySales(STORE_ID, BUSINESS_DATE, null, CONTENT));
        assertThrows(InvalidSalesImportException.class,
                () -> useCase.importDailySales(0L, BUSINESS_DATE, FILE_NAME, CONTENT));
    }

    @Test
    void rejectsFileWithoutDataRows() {
        parser.rows = List.of();

        InvalidSalesImportException exception = assertThrows(InvalidSalesImportException.class,
                () -> useCase().importDailySales(STORE_ID, BUSINESS_DATE, FILE_NAME, CONTENT));
        assertTrue(exception.getMessage().contains("没有数据行"));
    }

    private PostDailySalesImport useCase() {
        return new PostDailySalesImport(
                new StoreRepository() {
                    @Override
                    public List<Store> findAllActive() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<Store> findActiveById(long storeId) {
                        return activeStores.contains(storeId)
                                ? Optional.of(new Store(storeId, "S-001", "测试店"))
                                : Optional.empty();
                    }

                    @Override
                    public boolean existsActiveById(long storeId) {
                        return activeStores.contains(storeId);
                    }
                },
                sales,
                parser,
                () -> TODAY);
    }

    private ParsedSalesRow row(
            long rowNumber,
            String barcode,
            String productName,
            String quantity,
            String amount,
            String rate,
            String supplierName) {
        return new ParsedSalesRow(rowNumber, barcode, productName, quantity, amount, rate,
                "1", "2", "日化", supplierName, "{\"条码\":\"" + barcode + "\"}");
    }

    private static String sha256(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingSalesRepository implements DailySalesImportRepository {

        private Map<String, Long> productIds = new LinkedHashMap<>();
        private Map<String, Long> categoryIds = new LinkedHashMap<>();
        private Map<String, Long> supplierIds = new LinkedHashMap<>();
        private boolean fileHashExists;
        private boolean postedBatchExists;
        private String lastHash;
        private LocalDate askedPostedDate;
        private final List<String> askedBarcodes = new ArrayList<>();
        private final List<String> askedCategoryNames = new ArrayList<>();
        private final List<String> askedSupplierNames = new ArrayList<>();
        private DailySalesPosting posting;
        private ImportFailure failure;

        @Override
        public boolean existsFileHash(long storeId, String fileHash) {
            this.lastHash = fileHash;
            return fileHashExists;
        }

        @Override
        public boolean existsPostedSalesBatch(long storeId, LocalDate businessDate) {
            this.askedPostedDate = businessDate;
            return postedBatchExists;
        }

        @Override
        public Map<String, Long> findProductIdsByBarcodes(List<String> barcodes) {
            askedBarcodes.addAll(barcodes);
            return filter(productIds, barcodes);
        }

        @Override
        public Map<String, Long> findCategoryIdsByNames(List<String> categoryNames) {
            askedCategoryNames.addAll(categoryNames);
            return filter(categoryIds, categoryNames);
        }

        @Override
        public Map<String, Long> findSupplierIdsByNames(List<String> supplierNames) {
            askedSupplierNames.addAll(supplierNames);
            return filter(supplierIds, supplierNames);
        }

        @Override
        public long postBatch(DailySalesPosting value) {
            this.posting = value;
            return 7L;
        }

        @Override
        public long saveFailedBatch(ImportFailure value) {
            this.failure = value;
            return 4L;
        }

        private Map<String, Long> filter(Map<String, Long> source, List<String> keys) {
            Map<String, Long> found = new LinkedHashMap<>();
            for (String key : keys) {
                if (source.containsKey(key)) {
                    found.put(key, source.get(key));
                }
            }
            return found;
        }
    }

    private static final class StubSalesParser implements DailySalesFileParser {

        private List<ParsedSalesRow> rows = List.of();
        private ImportFileFormatException failWith;

        @Override
        public ParsedSalesFile parse(byte[] content, String fileName) {
            if (failWith != null) {
                throw failWith;
            }
            return new ParsedSalesFile(rows);
        }
    }
}
