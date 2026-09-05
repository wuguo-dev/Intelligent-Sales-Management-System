package com.haowugou.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haowugou.application.salesimport.PostDailySalesImport;
import com.haowugou.domain.importbatch.ImportBatchResult;
import com.haowugou.domain.salesimport.DailySalesImportResult;
import com.haowugou.domain.salesimport.ParsedSalesRow;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.infrastructure.fileimport.PosDailySalesExcelFileParser;
import com.haowugou.infrastructure.persistence.salesimport.DailySalesImportMapper;
import com.haowugou.infrastructure.persistence.salesimport.MybatisDailySalesImportRepository;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 用真实 POS 导出的《商品销售汇总》.xls 跑通整条销售导入链路。
 *
 * <p>文件路径来自环境变量 {@code HAOWUGOU_POS_SALES_FILE}，未设置或文件不存在时跳过——
 * 真实业务文件不进仓库。夹具门店用高位 ID，结束后回滚，不留测试数据；
 * 该文件的 892 个条码在库里基本不存在，因此本用例同时验证「未知条码批量建待完善商品」这条路径。
 */
class RealPosDailySalesFileImportTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 29);

    private SqlSession session;
    private Connection connection;
    private PostDailySalesImport useCase;
    private long storeId;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        String password = System.getenv("HAOWUGOU_DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "设置 HAOWUGOU_DB_PASSWORD 后执行真实 MySQL 集成测试");
        String path = System.getenv("HAOWUGOU_POS_SALES_FILE");
        Assumptions.assumeTrue(path != null && !path.isBlank(),
                "设置 HAOWUGOU_POS_SALES_FILE 指向真实《商品销售汇总》.xls 后执行");
        file = Path.of(path);
        Assumptions.assumeTrue(Files.isRegularFile(file), "POS 销售文件不存在: " + path);

        String url = System.getenv().getOrDefault(
                "HAOWUGOU_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/haowugou?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String username = System.getenv().getOrDefault("HAOWUGOU_DB_USERNAME", "root");

        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, username, password);
        Configuration configuration = new Configuration(new Environment(
                "mysql-real-file-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Resources.getResourceAsStream("mapper/DailySalesImportMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DailySalesImportMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(false);
        connection = session.getConnection();

        long base = 8_300_000_000_000_000_000L
                + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L) * 10_000L;
        storeId = base + 1;
        String suffix = Long.toUnsignedString(base, 36);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO store (id, store_code, store_name, is_active) VALUES (?, ?, ?, 1)")) {
            statement.setLong(1, storeId);
            statement.setString(2, "IT-S-" + suffix);
            statement.setString(3, "真实销售文件店-" + suffix);
            statement.executeUpdate();
        }

        useCase = new PostDailySalesImport(
                storeRepository(),
                new MybatisDailySalesImportRepository(session.getMapper(DailySalesImportMapper.class)),
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
    void importsRealPosSalesFileEndToEnd() throws Exception {
        byte[] content = Files.readAllBytes(file);
        // 独立复算期望的销售事实数：剔除全零行后按 (条码, 落库供应商) 归并，
        // 与唯一键 uk_daily_sales_batch_product_supplier 的 IFNULL(supplier_id, 0) 同口径。
        // 同条码的两个未知供应商都落 null，只能合成一条事实。
        List<ParsedSalesRow> parsedRows = new PosDailySalesExcelFileParser(new ObjectMapper())
                .parse(content, file.getFileName().toString()).rows();
        List<ParsedSalesRow> sellingRows = parsedRows.stream()
                .filter(row -> !(isZero(row.salesQuantity()) && isZero(row.salesAmount())))
                .toList();
        Set<String> knownSuppliers = existingSupplierNames(sellingRows);
        long expectedFacts = sellingRows.stream()
                .map(row -> row.barcode() + ' ' + resolvedSupplierKey(row.supplierName(), knownSuppliers))
                .distinct()
                .count();

        DailySalesImportResult result = useCase.importDailySales(
                storeId, BUSINESS_DATE, file.getFileName().toString(), content);

        assertEquals(parsedRows.size(), result.totalRows());
        assertEquals(expectedFacts, result.salesRows(),
                "销售事实数应等于非全零行按 (条码, 落库供应商) 归并后的条数");
        assertEquals(
                sellingRows.stream().map(ParsedSalesRow::barcode).distinct().count(),
                result.deductedProducts(),
                "本文件无净销量为 0 的商品，扣减商品数应等于非全零行的不同条码数");

        assertEquals(ImportBatchResult.STATUS_POSTED, result.status(),
                () -> "真实文件应整批过账，前几条错误: " + result.errors().stream().limit(5).toList());
        assertEquals(result.totalRows(), result.successRows());
        assertEquals(0, result.errorRows());
        // 防止文件被换成小样本后上面的相等断言变得没有意义
        assertTrue(result.totalRows() > 500, "实测 899 行数据: " + result.totalRows());
        // 全零行只留档，销售事实必然少于数据行
        assertTrue(result.salesRows() < result.totalRows(),
                "销售事实数应少于数据行数: " + result.salesRows());

        assertEquals(result.totalRows(), countRawRows(result.batchId()));
        assertEquals(result.salesRows(), countSales(result.batchId()));
        assertEquals(result.deductedProducts(), countMovements(result.batchId()));
        assertEquals(result.deductedProducts(), countInventoryRows(storeId));
        assertEquals(result.pendingProductsCreated(), countPendingProducts(result.batchId()));

        // 库存余额必须等于流水累计，否则扣减与流水不同源
        BigDecimal movementSum = sumMovementQuantity(result.batchId());
        assertEquals(movementSum, sumInventoryQuantity(storeId));
        // 门店此前无这些商品的库存，扣减后余额即为负的净销量
        assertEquals(0, movementSum.negate().compareTo(sumSalesQuantity(result.batchId())),
                "库存流水合计应等于净销量的相反数: " + movementSum);
        assertTrue(sumSalesAmount(result.batchId()).signum() > 0,
                "销售收入合计应为正: " + sumSalesAmount(result.batchId()));

        System.out.printf(
                "真实 POS 销售文件导入: 数据行=%d 有销售行=%d 销售事实=%d 待完善商品=%d 扣减商品=%d "
                        + "销售数量合计=%s 销售收入合计=%s%n",
                result.totalRows(), sellingRows.size(), result.salesRows(),
                result.pendingProductsCreated(), result.deductedProducts(),
                sumSalesQuantity(result.batchId()), sumSalesAmount(result.batchId()));
    }

    private boolean isZero(String text) {
        return text == null || text.isBlank() || new BigDecimal(text.trim()).signum() == 0;
    }

    /** 归并键：库里存在的供应商用名称区分，不存在的一律落 null，与用例的 supplier_key=0 一致。 */
    private String resolvedSupplierKey(String supplierName, Set<String> knownSuppliers) {
        String name = supplierName == null ? "" : supplierName.trim();
        return knownSuppliers.contains(name) ? name : "";
    }

    private Set<String> existingSupplierNames(List<ParsedSalesRow> rows) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        for (ParsedSalesRow row : rows) {
            if (row.supplierName() != null && !row.supplierName().isBlank()) {
                names.add(row.supplierName().trim());
            }
        }
        if (names.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", names.stream().map(name -> "?").toList());
        Set<String> existing = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT supplier_name FROM supplier WHERE supplier_name IN (" + placeholders + ")")) {
            int index = 1;
            for (String name : names) {
                statement.setString(index++, name);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    existing.add(result.getString(1));
                }
            }
        }
        return existing;
    }

    private StoreRepository storeRepository() {
        return new StoreRepository() {
            @Override
            public List<Store> findAllActive() {
                throw new UnsupportedOperationException("集成测试不使用");
            }

            @Override
            public Optional<Store> findActiveById(long id) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, store_code, store_name FROM store WHERE id = ? AND is_active = 1")) {
                    statement.setLong(1, id);
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
            public boolean existsActiveById(long id) {
                throw new UnsupportedOperationException("集成测试不使用");
            }
        };
    }

    private int countRawRows(long batchId) throws SQLException {
        return count("SELECT COUNT(*) FROM import_raw_row WHERE batch_id = ?", batchId);
    }

    private int countSales(long batchId) throws SQLException {
        return count("SELECT COUNT(*) FROM daily_product_sales WHERE batch_id = ?", batchId);
    }

    private int countMovements(long batchId) throws SQLException {
        return count("SELECT COUNT(*) FROM inventory_movement "
                + "WHERE batch_id = ? AND movement_type IN ('SALE_OUT', 'SALE_RETURN')", batchId);
    }

    private int countInventoryRows(long storeId) throws SQLException {
        return count("SELECT COUNT(*) FROM store_product_inventory WHERE store_id = ?", storeId);
    }

    /** 本批次新建的待完善商品：按批次内销售事实关联，只数 PENDING 的。 */
    private int countPendingProducts(long batchId) throws SQLException {
        return count("SELECT COUNT(DISTINCT p.id) FROM product p "
                + "JOIN daily_product_sales s ON s.product_id = p.id "
                + "WHERE s.batch_id = ? AND p.data_status = 'PENDING'", batchId);
    }

    private BigDecimal sumMovementQuantity(long batchId) throws SQLException {
        return sum("SELECT COALESCE(SUM(quantity_change), 0) FROM inventory_movement "
                + "WHERE batch_id = ?", batchId);
    }

    private BigDecimal sumInventoryQuantity(long storeId) throws SQLException {
        return sum("SELECT COALESCE(SUM(current_quantity), 0) FROM store_product_inventory "
                + "WHERE store_id = ?", storeId);
    }

    private BigDecimal sumSalesAmount(long batchId) throws SQLException {
        return sum("SELECT COALESCE(SUM(sales_amount), 0) FROM daily_product_sales "
                + "WHERE batch_id = ?", batchId);
    }

    private BigDecimal sumSalesQuantity(long batchId) throws SQLException {
        return sum("SELECT COALESCE(SUM(sales_quantity), 0) FROM daily_product_sales "
                + "WHERE batch_id = ?", batchId);
    }

    private int count(String sql, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private BigDecimal sum(String sql, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBigDecimal(1);
            }
        }
    }
}
