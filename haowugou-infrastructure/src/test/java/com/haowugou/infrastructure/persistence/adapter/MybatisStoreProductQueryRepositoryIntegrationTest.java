package com.haowugou.infrastructure.persistence.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haowugou.domain.pagination.PageResult;
import com.haowugou.domain.product.InventoryStatus;
import com.haowugou.domain.product.ProductDataStatus;
import com.haowugou.domain.product.StoreProductDetail;
import com.haowugou.domain.product.StoreProductListItem;
import com.haowugou.domain.product.StoreProductQueryCriteria;
import com.haowugou.infrastructure.persistence.mapper.StoreProductQueryMapper;
import com.haowugou.infrastructure.persistence.mapper.WarehouseQueryMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
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

class MybatisStoreProductQueryRepositoryIntegrationTest {

    private static final LocalDate START_DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 8, 7);

    private SqlSession session;
    private Connection connection;
    private MybatisStoreProductQueryRepository products;
    private MybatisWarehouseRepository warehouses;
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
        addMapperXml(configuration, "mapper/StoreProductQueryMapper.xml");
        addMapperXml(configuration, "mapper/WarehouseQueryMapper.xml");
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(false);

        ids = TestIds.create();
        connection = session.getConnection();
        insertFixtures(connection, ids);
        products = new MybatisStoreProductQueryRepository(session.getMapper(StoreProductQueryMapper.class));
        warehouses = new MybatisWarehouseRepository(session.getMapper(WarehouseQueryMapper.class));
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
    void isolatesInventoryWarehousesSuppliersAndPostedSalesByStore() {
        PageResult<StoreProductListItem> storeOne = products.findPage(criteria(
                ids.storeOne(), null, null, null, null, null, START_DATE, END_DATE, 0, 20));
        PageResult<StoreProductListItem> storeTwo = products.findPage(criteria(
                ids.storeTwo(), null, null, null, null, null, START_DATE, END_DATE, 0, 20));

        assertEquals(4, storeOne.totalElements());
        assertEquals(1, storeTwo.totalElements());
        StoreProductListItem productInStoreOne = item(storeOne, ids.sharedProduct());
        StoreProductListItem productInStoreTwo = item(storeTwo, ids.sharedProduct());
        assertEquals(new BigDecimal("5.000"), productInStoreOne.currentQuantity());
        assertEquals(ids.storeOneWarehouse(), productInStoreOne.warehouseId());
        // 售价是全局商品资料，两家门店读到同一个值；库存与仓库才是门店级的。
        assertEquals(new BigDecimal("5.0000"), productInStoreOne.salePrice());
        assertEquals(new BigDecimal("5.0000"), productInStoreTwo.salePrice());
        assertEquals(List.of("甲供应商-" + ids.suffix(), "乙供应商-" + ids.suffix()),
                productInStoreOne.supplierNames());
        assertEquals(new BigDecimal("5.000"), productInStoreOne.periodSalesMetrics().salesQuantity());
        assertEquals(new BigDecimal("50.00"), productInStoreOne.periodSalesMetrics().salesAmount());
        assertEquals(new BigDecimal("11.00"), productInStoreOne.periodSalesMetrics().grossProfitAmount());
        assertEquals(new BigDecimal("9.000"), productInStoreTwo.currentQuantity());
        assertEquals(ids.storeTwoWarehouse(), productInStoreTwo.warehouseId());
        assertEquals(new BigDecimal("9.000"), productInStoreTwo.periodSalesMetrics().salesQuantity());
        assertEquals(new BigDecimal("-1.000"),
                item(storeOne, ids.negativeProduct()).periodSalesMetrics().salesQuantity());
    }

    @Test
    void filtersByBarcodeOrNameCategoryAndStoreWarehouse() {
        StoreProductQueryCriteria barcode = new StoreProductQueryCriteria(
                ids.storeOne(),
                "6900" + ids.sharedProduct(),
                ids.category(),
                null,
                ids.storeOneWarehouse(),
                InventoryStatus.POSITIVE,
                ProductDataStatus.ACTIVE,
                null,
                null,
                null,
                null,
                0,
                20);
        StoreProductQueryCriteria name = new StoreProductQueryCriteria(
                ids.storeOne(),
                "待完善商品",
                ids.category(),
                null,
                null,
                null,
                ProductDataStatus.PENDING,
                null,
                null,
                null,
                null,
                0,
                20);

        assertEquals(List.of(ids.sharedProduct()), productIds(products.findPage(barcode)));
        assertEquals(List.of(ids.pendingProduct()), productIds(products.findPage(name)));
        assertTrue(products.findPage(new StoreProductQueryCriteria(
                ids.storeOne(),
                null,
                ids.category(),
                null,
                ids.storeTwoWarehouse(),
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                20)).items().isEmpty());
    }

    @Test
    void appliesAllFiltersWithoutDuplicatesAndKeepsStablePagination() {
        PageResult<StoreProductListItem> supplierFiltered = products.findPage(criteria(
                ids.storeOne(), ids.supplierOne(), null, null, null, null, null, null, 0, 20));
        PageResult<StoreProductListItem> pendingZero = products.findPage(criteria(
                ids.storeOne(), null, InventoryStatus.ZERO, ProductDataStatus.PENDING,
                null, null, null, null, 0, 20));
        PageResult<StoreProductListItem> negativeRange = products.findPage(criteria(
                ids.storeOne(), null, InventoryStatus.NEGATIVE, null,
                new BigDecimal("-2.000"), BigDecimal.ZERO, null, null, 0, 20));
        PageResult<StoreProductListItem> firstPage = products.findPage(criteria(
                ids.storeOne(), null, null, null, null, null, null, null, 0, 2));
        PageResult<StoreProductListItem> secondPage = products.findPage(criteria(
                ids.storeOne(), null, null, null, null, null, null, null, 1, 2));

        assertEquals(2, supplierFiltered.totalElements());
        assertEquals(2, supplierFiltered.items().size());
        assertEquals(List.of(ids.pendingProduct()), productIds(pendingZero));
        assertEquals(List.of(ids.negativeProduct()), productIds(negativeRange));
        assertEquals(4, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertTrue(firstPage.items().getLast().productId() < secondPage.items().getFirst().productId());
        assertFalse(productIds(firstPage).contains(ids.globalOnlyProduct()));
        assertFalse(productIds(secondPage).contains(ids.globalOnlyProduct()));
    }

    @Test
    void returnsStoreScopedDetailAndZeroMetricsForProductWithoutSales() {
        StoreProductDetail storeOneDetail = products.findDetail(
                ids.storeOne(), ids.sharedProduct(), START_DATE, END_DATE).orElseThrow();
        StoreProductDetail storeTwoDetail = products.findDetail(
                ids.storeTwo(), ids.sharedProduct(), START_DATE, END_DATE).orElseThrow();
        StoreProductDetail noSalesDetail = products.findDetail(
                ids.storeOne(), ids.pendingProduct(), START_DATE, END_DATE).orElseThrow();

        assertEquals(ids.storeOneWarehouse(), storeOneDetail.warehouseId());
        assertEquals(new BigDecimal("5.000"), storeOneDetail.currentQuantity());
        assertEquals(ids.storeTwoWarehouse(), storeTwoDetail.warehouseId());
        assertEquals(new BigDecimal("9.000"), storeTwoDetail.currentQuantity());
        assertEquals(BigDecimal.ZERO, noSalesDetail.periodSalesMetrics().salesQuantity());
        assertTrue(products.findDetail(ids.storeTwo(), ids.pendingProduct(), null, null).isEmpty());
        assertTrue(products.findDetail(ids.storeOne(), ids.globalOnlyProduct(), null, null).isEmpty());
    }

    @Test
    void listsOnlyActiveWarehousesWithinTheStoreButRecognizesDisabledOwnership() {
        assertEquals(List.of(ids.storeOneWarehouse()), warehouses.findAllActiveByStoreId(ids.storeOne()).stream()
                .map(warehouse -> warehouse.id())
                .toList());
        assertTrue(warehouses.existsByStoreIdAndId(ids.storeOne(), ids.storeOneWarehouse()));
        assertTrue(warehouses.existsByStoreIdAndId(ids.storeOne(), ids.disabledWarehouse()));
        assertFalse(warehouses.existsByStoreIdAndId(ids.storeOne(), ids.storeTwoWarehouse()));
    }

    @Test
    void coreQueriesUseStoreLeadingIndexes() throws SQLException {
        assertTrue(explainUsesAnyKey(
                "EXPLAIN SELECT v.product_id FROM v_product_inventory_query v "
                        + "WHERE v.store_id = ? ORDER BY v.product_id ASC LIMIT 20",
                List.of("PRIMARY", "idx_store_product_inventory_quantity"),
                ids.storeOne()));
        assertTrue(explainUsesAnyKey(
                "EXPLAIN SELECT sales.product_id, SUM(sales.sales_quantity) "
                        + "FROM v_posted_daily_product_sales sales "
                        + "WHERE sales.store_id = ? "
                        + "AND sales.business_date BETWEEN ? AND ? "
                        + "AND sales.product_id IN (?, ?) GROUP BY sales.product_id",
                List.of("idx_daily_sales_store_date_product", "idx_daily_sales_store_product_date"),
                ids.storeOne(), START_DATE, END_DATE, ids.sharedProduct(), ids.negativeProduct()));
    }

    private StoreProductQueryCriteria criteria(
            long storeId,
            Long supplierId,
            InventoryStatus inventoryStatus,
            ProductDataStatus dataStatus,
            BigDecimal minStock,
            BigDecimal maxStock,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int size) {
        return new StoreProductQueryCriteria(
                storeId,
                null,
                null,
                supplierId,
                null,
                inventoryStatus,
                dataStatus,
                minStock,
                maxStock,
                startDate,
                endDate,
                page,
                size);
    }

    private StoreProductListItem item(PageResult<StoreProductListItem> page, long productId) {
        return page.items().stream()
                .filter(item -> item.productId() == productId)
                .findFirst()
                .orElseThrow();
    }

    private List<Long> productIds(PageResult<StoreProductListItem> page) {
        return page.items().stream().map(StoreProductListItem::productId).toList();
    }

    private void addMapperXml(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private boolean explainUsesAnyKey(String sql, List<String> expectedKeys, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String key = result.getString("key");
                    if (key != null && expectedKeys.contains(key)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void insertFixtures(Connection connection, TestIds ids) throws SQLException {
        update(connection, "INSERT INTO store (id, store_code, store_name, is_active) VALUES "
                        + "(?, ?, ?, 1), (?, ?, ?, 1)",
                ids.storeOne(), "IT-S1-" + ids.suffix(), "集成测试一店-" + ids.suffix(),
                ids.storeTwo(), "IT-S2-" + ids.suffix(), "集成测试二店-" + ids.suffix());
        update(connection, "INSERT INTO warehouse "
                        + "(id, store_id, warehouse_code, warehouse_name, status) VALUES "
                        + "(?, ?, ?, ?, 'ENABLED'), (?, ?, ?, ?, 'DISABLED'), (?, ?, ?, ?, 'ENABLED')",
                ids.storeOneWarehouse(), ids.storeOne(), "W1-" + ids.suffix(), "一店启用仓",
                ids.disabledWarehouse(), ids.storeOne(), "WD-" + ids.suffix(), "一店停用仓",
                ids.storeTwoWarehouse(), ids.storeTwo(), "W2-" + ids.suffix(), "二店启用仓");
        update(connection, "INSERT INTO category (id, category_code, category_name, status) "
                        + "VALUES (?, ?, ?, 'ENABLED')",
                ids.category(), "C-" + ids.suffix(), "测试品类-" + ids.suffix());
        update(connection, "INSERT INTO supplier (id, supplier_code, supplier_name, status) VALUES "
                        + "(?, ?, ?, 'ENABLED'), (?, ?, ?, 'ENABLED')",
                ids.supplierOne(), "SUP1-" + ids.suffix(), "甲供应商-" + ids.suffix(),
                ids.supplierTwo(), "SUP2-" + ids.suffix(), "乙供应商-" + ids.suffix());
        insertProducts(connection, ids);
        update(connection, "INSERT INTO product_supplier (product_id, supplier_id, is_primary) VALUES "
                        + "(?, ?, 1), (?, ?, 0), (?, ?, 1)",
                ids.sharedProduct(), ids.supplierOne(),
                ids.sharedProduct(), ids.supplierTwo(),
                ids.otherProduct(), ids.supplierOne());
        update(connection, "INSERT INTO store_product_inventory "
                        + "(store_id, product_id, warehouse_id, current_quantity) VALUES "
                        + "(?, ?, ?, 5.000), (?, ?, ?, 9.000), (?, ?, NULL, 0.000), "
                        + "(?, ?, ?, -2.000), (?, ?, ?, 3.000)",
                ids.storeOne(), ids.sharedProduct(), ids.storeOneWarehouse(),
                ids.storeTwo(), ids.sharedProduct(), ids.storeTwoWarehouse(),
                ids.storeOne(), ids.pendingProduct(),
                ids.storeOne(), ids.negativeProduct(), ids.storeOneWarehouse(),
                ids.storeOne(), ids.otherProduct(), ids.storeOneWarehouse());
        insertSales(connection, ids);
    }

    private void insertProducts(Connection connection, TestIds ids) throws SQLException {
        update(connection, "INSERT INTO product "
                        + "(id, barcode, product_name, unit, category_id, tax_cost_price, sale_price, remarks, data_status) "
                        + "VALUES (?, ?, ?, '件', ?, 4.0000, 5.0000, '共享商品', 'ACTIVE'), "
                        + "(?, ?, ?, '件', ?, 2.0000, 3.0000, NULL, 'PENDING'), "
                        + "(?, ?, ?, '件', ?, 6.0000, 8.0000, NULL, 'ACTIVE'), "
                        + "(?, ?, ?, '件', ?, 1.0000, 2.0000, NULL, 'ACTIVE'), "
                        + "(?, ?, ?, '件', ?, 3.0000, 4.0000, NULL, 'ACTIVE')",
                ids.sharedProduct(), "6900" + ids.sharedProduct(), "共享商品-" + ids.suffix(), ids.category(),
                ids.pendingProduct(), "6900" + ids.pendingProduct(), "待完善商品-" + ids.suffix(), ids.category(),
                ids.negativeProduct(), "6900" + ids.negativeProduct(), "负库存商品-" + ids.suffix(), ids.category(),
                ids.globalOnlyProduct(), "6900" + ids.globalOnlyProduct(), "仅全局商品-" + ids.suffix(), ids.category(),
                ids.otherProduct(), "6900" + ids.otherProduct(), "其他商品-" + ids.suffix(), ids.category());
    }

    private void insertSales(Connection connection, TestIds ids) throws SQLException {
        update(connection, "INSERT INTO import_batch "
                        + "(id, store_id, import_type, data_date, file_name, file_hash, status, total_rows, success_rows) "
                        + "VALUES (?, ?, 'DAILY_SALES', '2026-08-01', ?, ?, 'POSTED', 1, 1), "
                        + "(?, ?, 'DAILY_SALES', '2026-08-02', ?, ?, 'REVERSED', 1, 1), "
                        + "(?, ?, 'DAILY_SALES', '2026-08-07', ?, ?, 'POSTED', 1, 1), "
                        + "(?, ?, 'DAILY_SALES', '2026-08-03', ?, ?, 'POSTED', 1, 1), "
                        + "(?, ?, 'DAILY_SALES', '2026-08-01', ?, ?, 'POSTED', 1, 1)",
                ids.batchOne(), ids.storeOne(), "posted-a.xlsx", hash(ids.batchOne()),
                ids.batchReversed(), ids.storeOne(), "reversed.xlsx", hash(ids.batchReversed()),
                ids.batchBoundary(), ids.storeOne(), "posted-boundary.xlsx", hash(ids.batchBoundary()),
                ids.batchNegative(), ids.storeOne(), "posted-negative.xlsx", hash(ids.batchNegative()),
                ids.batchOtherStore(), ids.storeTwo(), "posted-b.xlsx", hash(ids.batchOtherStore()));
        update(connection, "INSERT INTO daily_product_sales "
                        + "(id, batch_id, store_id, business_date, product_id, sales_quantity, sales_amount, gross_profit_amount) "
                        + "VALUES (?, ?, ?, '2026-08-01', ?, 2.000, 20.00, 5.00), "
                        + "(?, ?, ?, '2026-08-02', ?, 7.000, 70.00, 14.00), "
                        + "(?, ?, ?, '2026-08-07', ?, 3.000, 30.00, 6.00), "
                        + "(?, ?, ?, '2026-08-03', ?, -1.000, -10.00, -2.00), "
                        + "(?, ?, ?, '2026-08-01', ?, 9.000, 90.00, 30.00)",
                ids.salesOne(), ids.batchOne(), ids.storeOne(), ids.sharedProduct(),
                ids.salesReversed(), ids.batchReversed(), ids.storeOne(), ids.sharedProduct(),
                ids.salesBoundary(), ids.batchBoundary(), ids.storeOne(), ids.sharedProduct(),
                ids.salesNegative(), ids.batchNegative(), ids.storeOne(), ids.negativeProduct(),
                ids.salesOtherStore(), ids.batchOtherStore(), ids.storeTwo(), ids.sharedProduct());
    }

    private String hash(long value) {
        return String.format("%064x", value);
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
            long storeOne,
            long storeTwo,
            long storeOneWarehouse,
            long disabledWarehouse,
            long storeTwoWarehouse,
            long category,
            long supplierOne,
            long supplierTwo,
            long sharedProduct,
            long pendingProduct,
            long negativeProduct,
            long globalOnlyProduct,
            long otherProduct,
            long batchOne,
            long batchReversed,
            long batchBoundary,
            long batchNegative,
            long batchOtherStore,
            long salesOne,
            long salesReversed,
            long salesBoundary,
            long salesNegative,
            long salesOtherStore) {

        static TestIds create() {
            long base = 8_000_000_000_000_000_000L
                    + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000L) * 10_000L;
            String suffix = Long.toUnsignedString(base, 36);
            return new TestIds(
                    base, suffix,
                    base + 1, base + 2,
                    base + 10, base + 11, base + 20,
                    base + 30,
                    base + 40, base + 41,
                    base + 100, base + 101, base + 102, base + 103, base + 104,
                    base + 1000, base + 1001, base + 1002, base + 1003, base + 1004,
                    base + 2000, base + 2001, base + 2002, base + 2003, base + 2004);
        }
    }
}
