package com.haowugou.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.application.salesimport.DuplicateSalesFileException;
import com.haowugou.application.salesimport.PostDailySalesImport;
import com.haowugou.application.salesimport.PostedSalesBatchExistsException;
import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.salesimport.DailySalesImportResult;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.infrastructure.fileimport.PosDailySalesExcelFileParser;
import com.haowugou.infrastructure.persistence.salesimport.DailySalesImportMapper;
import com.haowugou.infrastructure.persistence.salesimport.MybatisDailySalesImportRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 每日销售导入的 MySQL 全链路集成测试：真实解析器 + 真实用例 + 真实 MyBatis Adapter，
 * 门店用 JDBC 直查替身。夹具使用高位 ID（8.2e18+）与 {@code IT-} 前缀，
 * 结束后通过 JDBC Connection 整体回滚，不留测试数据。
 */
class DailySalesImportIntegrationTest {

    /** POS《商品销售汇总》实测 15 列表头。 */
    private static final List<String> POS_HEADERS = List.of(
            "条码", "商品名称", "本期|销售数量", "选中机构库存数量", "当前机构最后进价",
            "当前机构售价", "销售毛利率", "本期|销售收入", "销售占比", "日均销售",
            "同期|销售收入", "同期|销售毛利率", "同期|销售数量", "品类名称", "供应商名称");

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 29);
    private static final String FILE_NAME = "IT-商品销售汇总.xls";

    private SqlSession session;
    private Connection connection;
    private PostDailySalesImport useCase;
    private TestIds ids;

    @BeforeEach
    void setUp() throws Exception {
        String password = System.getenv("HAOWUGOU_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "设置 HAOWUGOU_DB_PASSWORD 后执行真实 MySQL 集成测试");
        String url = System.getenv().getOrDefault(
                "HAOWUGOU_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/haowugou?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("HAOWUGOU_DB_USERNAME", "root");

        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, username, password);
        Configuration configuration = new Configuration(new Environment(
                "mysql-integration-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Resources.getResourceAsStream("mapper/DailySalesImportMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DailySalesImportMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(false);
        connection = session.getConnection();
        ids = TestIds.create();
        insertFixtures(connection, ids);

        MybatisDailySalesImportRepository repository = new MybatisDailySalesImportRepository(
                session.getMapper(DailySalesImportMapper.class));
        useCase = new PostDailySalesImport(
                storeRepository(),
                repository,
                new PosDailySalesExcelFileParser(new ObjectMapper()),
                () -> BUSINESS_DATE);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            try {
                connection.rollback();
            } catch (SQLException exception) {
                throw new IllegalStateException("回滚 MySQL 集成测试数据失败", exception);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void postsSalesFactsInventoryDeductionAndMovementsAtomically() throws Exception {
        byte[] content = excel(
                sale(ids.productA(), "商品甲", "3", "36.00", "25.5", "天和日化"),
                sale(ids.productB(), "商品乙", "2.5", "20.00", "10", "粮油商行"),
                sale(ids.productC(), "商品丙", "0", "0", "", "天和日化"));

        DailySalesImportResult result =
                useCase.importDailySales(ids.store(), BUSINESS_DATE, FILE_NAME, content);

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(3, result.totalRows());
        assertEquals(3, result.successRows());
        assertEquals(0, result.errorRows());
        assertEquals(2, result.salesRows()); // 数量与收入同时为 0 的丙不构成销售事实
        assertEquals(0, result.pendingProductsCreated());
        assertEquals(2, result.deductedProducts());

        BatchRow batch = batch(result.batchId());
        assertEquals("DAILY_SALES", batch.importType());
        assertEquals("POSTED", batch.status());
        assertEquals(BUSINESS_DATE, batch.dataDate());
        assertEquals(BUSINESS_DATE, batch.activeSalesDate()); // 生成列：唯一约束据此挡重复过账
        assertEquals(sha256(content), batch.fileHash());
        assertTrue(batch.postedAtNotNull());

        assertEquals(List.of(
                new RawRow(2, "6900" + ids.productA(), "VALID", null),
                new RawRow(3, "6900" + ids.productB(), "VALID", null),
                new RawRow(4, "6900" + ids.productC(), "VALID", null)),
                rawRows(result.batchId()));
        String raw = rawData(result.batchId(), 2);
        assertTrue(raw.contains("条码"), "原始行 JSON 应含条码列: " + raw);
        assertTrue(raw.contains("6900" + ids.productA()), "原始行 JSON 应含条码值: " + raw);

        assertEquals(List.of(
                new Sales(ids.productA(), ids.supplier(), new BigDecimal("3.000"),
                        new BigDecimal("36.00"), new BigDecimal("9.18"), new BigDecimal("25.5000")),
                new Sales(ids.productB(), null, new BigDecimal("2.500"),
                        new BigDecimal("20.00"), new BigDecimal("2.00"), new BigDecimal("10.0000"))),
                sales(result.batchId()));
        assertEquals(BUSINESS_DATE, salesBusinessDate(result.batchId()));

        // 甲期初 5 卖 3 剩 2；乙期初 0 卖 2.5 变 -2.5（数据库允许负库存）
        Inventory inventoryA = inventory(ids.store(), ids.productA());
        assertEquals(new BigDecimal("2.000"), inventoryA.quantity());
        assertEquals(1, inventoryA.version());
        assertEquals(ids.warehouse(), inventoryA.warehouseId()); // 扣减不改动仓库分配
        Inventory inventoryB = inventory(ids.store(), ids.productB());
        assertEquals(new BigDecimal("-2.500"), inventoryB.quantity());
        assertNull(inventoryB.warehouseId()); // 新建库存行不猜仓库
        assertNull(inventory(ids.store(), ids.productC())); // 零销售行不建库存

        assertEquals(List.of(
                new Movement(ids.productA(), "SALE_OUT", BUSINESS_DATE,
                        new BigDecimal("-3.000"), new BigDecimal("5.000"), new BigDecimal("2.000")),
                new Movement(ids.productB(), "SALE_OUT", BUSINESS_DATE,
                        new BigDecimal("-2.500"), new BigDecimal("0.000"), new BigDecimal("-2.500"))),
                movements(result.batchId()));
    }

    /** 退货行净销量为正数变化量，写 SALE_RETURN，库存加回去。 */
    @Test
    void netReturnWritesSaleReturnMovementAndAddsStockBack() throws Exception {
        byte[] content = excel(
                sale(ids.productA(), "商品甲", "-2", "-24.00", "25.5", "天和日化"));

        DailySalesImportResult result =
                useCase.importDailySales(ids.store(), BUSINESS_DATE, FILE_NAME, content);

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(new BigDecimal("7.000"), inventory(ids.store(), ids.productA()).quantity());
        assertEquals(List.of(new Movement(ids.productA(), "SALE_RETURN", BUSINESS_DATE,
                        new BigDecimal("2.000"), new BigDecimal("5.000"), new BigDecimal("7.000"))),
                movements(result.batchId()));

        Sales sales = sales(result.batchId()).getFirst();
        assertEquals(new BigDecimal("-2.000"), sales.quantity());
        assertEquals(new BigDecimal("-24.00"), sales.amount());
        assertEquals(new BigDecimal("-6.12"), sales.grossProfit());
    }

    /** 未知条码建 PENDING 商品并按名称挂品类，销售与库存照常入账。 */
    @Test
    void unknownBarcodeCreatesPendingProductAndStillPosts() throws Exception {
        String newBarcode = "6900" + ids.base();
        byte[] content = excel(new DataRow(
                newBarcode, "新到商品", "4", "50.00", "20", "3.5", "12.5", "IT-品类", "天和日化"));

        DailySalesImportResult result =
                useCase.importDailySales(ids.store(), BUSINESS_DATE, FILE_NAME, content);

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(1, result.pendingProductsCreated());
        assertEquals(1, result.salesRows());
        assertEquals(1, result.deductedProducts());

        ProductRow product = product(newBarcode);
        assertNotNull(product, "未知条码应建出待完善商品: " + newBarcode);
        assertEquals("新到商品", product.productName());
        assertEquals("PENDING", product.dataStatus());
        assertEquals(ids.category(), product.categoryId());
        assertEquals(new BigDecimal("3.5000"), product.taxCostPrice());
        assertEquals(new BigDecimal("12.5000"), product.salePrice());

        assertEquals(new BigDecimal("-4.000"), inventory(ids.store(), product.id()).quantity());
        assertEquals(List.of(new Movement(product.id(), "SALE_OUT", BUSINESS_DATE,
                        new BigDecimal("-4.000"), new BigDecimal("0.000"), new BigDecimal("-4.000"))),
                movements(result.batchId()));
    }

    @Test
    void rowErrorFailsWholeBatchWithoutSalesOrInventoryChanges() throws Exception {
        byte[] content = excel(
                sale(ids.productA(), "商品甲", "1", "12.00", "25.5", "天和日化"),
                sale(ids.productB(), "商品乙", "abc", "20.00", "10", "粮油商行"));

        DailySalesImportResult result =
                useCase.importDailySales(ids.store(), BUSINESS_DATE, FILE_NAME, content);

        assertEquals(ImportBatchResult.STATUS_FAILED, result.status());
        assertEquals(2, result.totalRows());
        assertEquals(0, result.successRows());
        assertEquals(1, result.errorRows());
        assertEquals(1, result.errors().size());

        BatchRow batch = batch(result.batchId());
        assertEquals("FAILED", batch.status());
        assertNull(batch.activeSalesDate()); // 失败批次不占用当日唯一约束
        assertFalse(batch.postedAtNotNull());
        assertTrue(batch.errorMessage().contains("销售数量无法解析"));

        List<RawRow> rows = rawRows(result.batchId());
        assertEquals(2, rows.size());
        assertEquals("VALID", rows.get(0).parseStatus());
        assertNull(rows.get(0).errorMessage());
        assertEquals("INVALID", rows.get(1).parseStatus());
        assertTrue(rows.get(1).errorMessage().contains("销售数量无法解析"));

        assertEquals(0, countSales(result.batchId()));
        assertEquals(0, countMovements(result.batchId()));
        assertEquals(new BigDecimal("5.000"), inventory(ids.store(), ids.productA()).quantity());
        assertNull(inventory(ids.store(), ids.productB()));
    }

    @Test
    void rejectsDuplicateFileAndSecondPostedBatchForSameDate() throws Exception {
        byte[] content = excel(sale(ids.productA(), "商品甲", "1", "12.00", "25.5", "天和日化"));
        useCase.importDailySales(ids.store(), BUSINESS_DATE, FILE_NAME, content);

        DuplicateSalesFileException duplicate = assertThrows(DuplicateSalesFileException.class,
                () -> useCase.importDailySales(ids.store(), BUSINESS_DATE, FILE_NAME, content));
        assertEquals("该销售文件已导入过: " + FILE_NAME, duplicate.getMessage());

        byte[] other = excel(sale(ids.productA(), "商品甲", "2", "24.00", "25.5", "天和日化"));
        PostedSalesBatchExistsException posted = assertThrows(PostedSalesBatchExistsException.class,
                () -> useCase.importDailySales(ids.store(), BUSINESS_DATE, "IT-另一份.xls", other));
        assertEquals("门店 " + ids.store() + " 在 " + BUSINESS_DATE + " 已有有效销售批次",
                posted.getMessage());
    }

    /** 同一门店不同日期可以各过一次；累计扣减叠加在同一库存行上。 */
    @Test
    void anotherBusinessDatePostsAgainAndAccumulatesDeduction() throws Exception {
        useCase.importDailySales(ids.store(), BUSINESS_DATE,
                FILE_NAME, excel(sale(ids.productA(), "商品甲", "1", "12.00", "25.5", "天和日化")));

        LocalDate previousDay = BUSINESS_DATE.minusDays(1);
        DailySalesImportResult result = useCase.importDailySales(ids.store(), previousDay,
                "IT-前一天.xls", excel(sale(ids.productA(), "商品甲", "2", "24.00", "25.5", "天和日化")));

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(previousDay, batch(result.batchId()).activeSalesDate());

        Inventory inventory = inventory(ids.store(), ids.productA());
        assertEquals(new BigDecimal("2.000"), inventory.quantity());
        assertEquals(2, inventory.version());
        assertEquals(List.of(new Movement(ids.productA(), "SALE_OUT", previousDay,
                        new BigDecimal("-2.000"), new BigDecimal("4.000"), new BigDecimal("2.000"))),
                movements(result.batchId()));
    }

    /** 失败批次不占当日额度，修正后可以重导。 */
    @Test
    void failedBatchDoesNotBlockCorrectedReimport() throws Exception {
        byte[] broken = excel(sale(ids.productA(), "商品甲", "x", "12.00", "25.5", "天和日化"));
        useCase.importDailySales(ids.store(), BUSINESS_DATE, "IT-坏文件.xls", broken);

        byte[] fixed = excel(sale(ids.productA(), "商品甲", "1", "12.00", "25.5", "天和日化"));
        DailySalesImportResult result =
                useCase.importDailySales(ids.store(), BUSINESS_DATE, "IT-修正.xls", fixed);

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(new BigDecimal("4.000"), inventory(ids.store(), ids.productA()).quantity());
    }

    /** 同商品不同供应商各存一条销售事实，库存按商品合并扣减一次。 */
    @Test
    void sameProductFromTwoSuppliersKeepsTwoFactsAndOneMovement() throws Exception {
        byte[] content = excel(
                sale(ids.productA(), "商品甲", "1", "12.00", "25", "天和日化"),
                sale(ids.productA(), "商品甲", "2", "24.00", "25", ""));

        DailySalesImportResult result =
                useCase.importDailySales(ids.store(), BUSINESS_DATE, FILE_NAME, content);

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status());
        assertEquals(2, result.salesRows());
        assertEquals(1, result.deductedProducts());

        // 查询按 supplier_key 排序，无供应商行的 supplier_key 为 0，排在真实供应商前
        assertEquals(List.of(
                new Sales(ids.productA(), null, new BigDecimal("2.000"),
                        new BigDecimal("24.00"), new BigDecimal("6.00"), new BigDecimal("25.0000")),
                new Sales(ids.productA(), ids.supplier(), new BigDecimal("1.000"),
                        new BigDecimal("12.00"), new BigDecimal("3.00"), new BigDecimal("25.0000"))),
                sales(result.batchId()));
        assertEquals(List.of(new Movement(ids.productA(), "SALE_OUT", BUSINESS_DATE,
                        new BigDecimal("-3.000"), new BigDecimal("5.000"), new BigDecimal("2.000"))),
                movements(result.batchId()));
    }

    private StoreRepository storeRepository() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                throw new UnsupportedOperationException("集成测试不使用");
            }

            @Override
            public Optional<Store> findActiveById(long storeId) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, store_code, store_name FROM store WHERE id = ? AND is_active = 1")) {
                    statement.setLong(1, storeId);
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next()
                                ? Optional.of(new Store(
                                        result.getLong("id"),
                                        result.getString("store_code"),
                                        result.getString("store_name")))
                                : Optional.empty();
                    }
                } catch (SQLException exception) {
                    throw new IllegalStateException("查询门店失败", exception);
                }
            }

            @Override
            public boolean existsActiveById(long storeId) {
                throw new UnsupportedOperationException("集成测试不使用");
            }
        };
    }

    private void insertFixtures(Connection connection, TestIds ids) throws SQLException {
        update(connection, "INSERT INTO store (id, store_code, store_name, is_active) VALUES (?, ?, ?, 1)",
                ids.store(), "IT-S-" + ids.suffix(), "集成测试销售店-" + ids.suffix());
        update(connection, "INSERT INTO warehouse (id, store_id, warehouse_code, warehouse_name, status) "
                        + "VALUES (?, ?, ?, ?, 'ENABLED')",
                ids.warehouse(), ids.store(), "W1-" + ids.suffix(), "一仓");
        update(connection, "INSERT INTO category (id, category_code, category_name) VALUES (?, ?, ?)",
                ids.category(), "IT-C-" + ids.suffix(), "IT-品类");
        update(connection, "INSERT INTO supplier (id, supplier_name) VALUES (?, ?)",
                ids.supplier(), "天和日化");
        update(connection, "INSERT INTO product (id, barcode, product_name, data_status) VALUES "
                        + "(?, ?, ?, 'ACTIVE'), (?, ?, ?, 'ACTIVE'), (?, ?, ?, 'ACTIVE')",
                ids.productA(), "6900" + ids.productA(), "销售商品甲-" + ids.suffix(),
                ids.productB(), "6900" + ids.productB(), "销售商品乙-" + ids.suffix(),
                ids.productC(), "6900" + ids.productC(), "销售商品丙-" + ids.suffix());
        update(connection, "INSERT INTO store_product_inventory "
                        + "(store_id, product_id, warehouse_id, current_quantity) VALUES (?, ?, ?, 5.000)",
                ids.store(), ids.productA(), ids.warehouse());
    }

    private BatchRow batch(long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT import_type, status, data_date, active_sales_date, file_hash, "
                        + "error_message, posted_at FROM import_batch WHERE id = ?")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "批次应存在: " + batchId);
                return new BatchRow(
                        result.getString("import_type"),
                        result.getString("status"),
                        result.getObject("data_date", LocalDate.class),
                        result.getObject("active_sales_date", LocalDate.class),
                        result.getString("file_hash"),
                        result.getString("error_message"),
                        result.getTimestamp("posted_at") != null);
            }
        }
    }

    private List<RawRow> rawRows(long batchId) throws SQLException {
        List<RawRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT `row_number`, barcode, parse_status, error_message FROM import_raw_row "
                        + "WHERE batch_id = ? ORDER BY `row_number`")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new RawRow(
                            result.getLong("row_number"),
                            result.getString("barcode"),
                            result.getString("parse_status"),
                            result.getString("error_message")));
                }
            }
        }
        return rows;
    }

    private String rawData(long batchId, long rowNumber) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT raw_data FROM import_raw_row WHERE batch_id = ? AND `row_number` = ?")) {
            statement.setLong(1, batchId);
            statement.setLong(2, rowNumber);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "原始行应存在: batchId=" + batchId + ", rowNumber=" + rowNumber);
                return result.getString(1);
            }
        }
    }

    private List<Sales> sales(long batchId) throws SQLException {
        List<Sales> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT product_id, supplier_id, sales_quantity, sales_amount, gross_profit_amount, "
                        + "reported_gross_profit_rate FROM daily_product_sales "
                        + "WHERE batch_id = ? ORDER BY product_id, supplier_key")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long supplierId = result.getLong("supplier_id");
                    // wasNull() 只对最近一次读取有效，必须紧跟 getLong 记录下来
                    Long nullableSupplierId = result.wasNull() ? null : supplierId;
                    rows.add(new Sales(
                            result.getLong("product_id"),
                            nullableSupplierId,
                            result.getBigDecimal("sales_quantity"),
                            result.getBigDecimal("sales_amount"),
                            result.getBigDecimal("gross_profit_amount"),
                            result.getBigDecimal("reported_gross_profit_rate")));
                }
            }
        }
        return rows;
    }

    private LocalDate salesBusinessDate(long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT business_date FROM daily_product_sales WHERE batch_id = ?")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "销售事实应存在: " + batchId);
                LocalDate businessDate = result.getObject(1, LocalDate.class);
                assertFalse(result.next(), "同批次销售事实业务日期应唯一");
                return businessDate;
            }
        }
    }

    private int countSales(long batchId) throws SQLException {
        return count("SELECT COUNT(*) FROM daily_product_sales WHERE batch_id = ?", batchId);
    }

    private Inventory inventory(long storeId, long productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT warehouse_id, current_quantity, version FROM store_product_inventory "
                        + "WHERE store_id = ? AND product_id = ?")) {
            statement.setLong(1, storeId);
            statement.setLong(2, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                long warehouseId = result.getLong("warehouse_id");
                return new Inventory(
                        result.wasNull() ? null : warehouseId,
                        result.getBigDecimal("current_quantity"),
                        result.getLong("version"));
            }
        }
    }

    private ProductRow product(String barcode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, product_name, category_id, tax_cost_price, sale_price, data_status "
                        + "FROM product WHERE barcode = ?")) {
            statement.setString(1, barcode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                long categoryId = result.getLong("category_id");
                Long nullableCategoryId = result.wasNull() ? null : categoryId;
                return new ProductRow(
                        result.getLong("id"),
                        result.getString("product_name"),
                        nullableCategoryId,
                        result.getBigDecimal("tax_cost_price"),
                        result.getBigDecimal("sale_price"),
                        result.getString("data_status"));
            }
        }
    }

    private List<Movement> movements(long batchId) throws SQLException {
        List<Movement> movements = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT product_id, movement_type, business_date, quantity_change, "
                        + "balance_before, balance_after FROM inventory_movement "
                        + "WHERE batch_id = ? ORDER BY product_id")) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    movements.add(new Movement(
                            result.getLong("product_id"),
                            result.getString("movement_type"),
                            result.getObject("business_date", LocalDate.class),
                            result.getBigDecimal("quantity_change"),
                            result.getBigDecimal("balance_before"),
                            result.getBigDecimal("balance_after")));
                }
            }
        }
        return movements;
    }

    private int countMovements(long batchId) throws SQLException {
        return count("SELECT COUNT(*) FROM inventory_movement WHERE batch_id = ?", batchId);
    }

    private int count(String sql, long batchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, batchId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private DataRow sale(
            long productId,
            String productName,
            String quantity,
            String amount,
            String rate,
            String supplierName) {
        return new DataRow("6900" + productId, productName, quantity, amount, rate,
                "", "", "", supplierName);
    }

    private byte[] excel(DataRow... rows) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("商品销售汇总");
            Row header = sheet.createRow(0);
            for (int column = 0; column < POS_HEADERS.size(); column++) {
                header.createCell(column).setCellValue(POS_HEADERS.get(column));
            }
            for (int index = 0; index < rows.length; index++) {
                DataRow data = rows[index];
                Row row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue(data.barcode());
                row.createCell(1).setCellValue(data.productName());
                row.createCell(2).setCellValue(data.quantity());
                row.createCell(4).setCellValue(data.costPrice());
                row.createCell(5).setCellValue(data.salePrice());
                row.createCell(6).setCellValue(data.rate());
                row.createCell(7).setCellValue(data.amount());
                row.createCell(13).setCellValue(data.categoryName());
                row.createCell(14).setCellValue(data.supplierName());
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private record TestIds(
            long base,
            String suffix,
            long store,
            long warehouse,
            long category,
            long supplier,
            long productA,
            long productB,
            long productC) {

        static TestIds create() {
            long base = 8_200_000_000_000_000_000L
                    + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L) * 10_000L;
            String suffix = Long.toUnsignedString(base, 36);
            return new TestIds(
                    base, suffix,
                    base + 1, base + 10, base + 20, base + 30,
                    base + 100, base + 101, base + 102);
        }
    }

    private record DataRow(
            String barcode,
            String productName,
            String quantity,
            String amount,
            String rate,
            String costPrice,
            String salePrice,
            String categoryName,
            String supplierName) {
    }

    private record BatchRow(
            String importType,
            String status,
            LocalDate dataDate,
            LocalDate activeSalesDate,
            String fileHash,
            String errorMessage,
            boolean postedAtNotNull) {
    }

    private record RawRow(long rowNumber, String barcode, String parseStatus, String errorMessage) {
    }

    private record Sales(
            long productId,
            Long supplierId,
            BigDecimal quantity,
            BigDecimal amount,
            BigDecimal grossProfit,
            BigDecimal reportedRate) {
    }

    private record Inventory(Long warehouseId, BigDecimal quantity, long version) {
    }

    private record ProductRow(
            long id,
            String productName,
            Long categoryId,
            BigDecimal taxCostPrice,
            BigDecimal salePrice,
            String dataStatus) {
    }

    private record Movement(
            long productId,
            String movementType,
            LocalDate businessDate,
            BigDecimal quantityChange,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter) {
    }
}
