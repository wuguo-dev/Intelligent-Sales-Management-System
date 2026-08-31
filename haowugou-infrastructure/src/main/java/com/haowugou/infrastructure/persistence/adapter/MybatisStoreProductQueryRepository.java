package com.haowugou.infrastructure.persistence.adapter;

import com.haowugou.domain.pagination.PageResult;
import com.haowugou.domain.product.PeriodSalesMetrics;
import com.haowugou.domain.product.StoreProductDetail;
import com.haowugou.domain.product.StoreProductListItem;
import com.haowugou.domain.product.StoreProductQueryCriteria;
import com.haowugou.domain.product.StoreProductQueryRepository;
import com.haowugou.infrastructure.persistence.data.ProductSalesMetricsRow;
import com.haowugou.infrastructure.persistence.data.ProductSupplierRow;
import com.haowugou.infrastructure.persistence.data.StoreProductRow;
import com.haowugou.infrastructure.persistence.mapper.StoreProductQueryMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用固定数量的批量查询实现门店商品 Repository，避免 N+1。 */
@Repository
public class MybatisStoreProductQueryRepository implements StoreProductQueryRepository {

    private static final PeriodSalesMetrics ZERO_METRICS = new PeriodSalesMetrics(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);

    private final StoreProductQueryMapper mapper;

    public MybatisStoreProductQueryRepository(StoreProductQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageResult<StoreProductListItem> findPage(StoreProductQueryCriteria criteria) {
        long totalElements = mapper.countProducts(criteria);
        if (totalElements == 0) {
            return new PageResult<>(List.of(), criteria.page(), criteria.size(), 0, 0);
        }

        long offset = (long) criteria.page() * criteria.size();
        List<StoreProductRow> rows = mapper.findProductPage(criteria, offset, criteria.size());
        List<Long> productIds = rows.stream().map(StoreProductRow::getProductId).toList();
        Map<Long, List<String>> suppliers = suppliersByProduct(criteria.storeId(), productIds);
        Map<Long, PeriodSalesMetrics> metrics = criteria.hasSalesPeriod()
                ? metricsByProduct(criteria.storeId(), productIds, criteria.startDate(), criteria.endDate())
                : Map.of();

        List<StoreProductListItem> items = rows.stream()
                .map(row -> toListItem(
                        row,
                        suppliers.getOrDefault(row.getProductId(), List.of()),
                        criteria.hasSalesPeriod()
                                ? metrics.getOrDefault(row.getProductId(), ZERO_METRICS)
                                : null))
                .toList();
        int totalPages = (int) ((totalElements + criteria.size() - 1) / criteria.size());
        return new PageResult<>(items, criteria.page(), criteria.size(), totalElements, totalPages);
    }

    @Override
    public Optional<StoreProductDetail> findDetail(
            long storeId,
            long productId,
            LocalDate startDate,
            LocalDate endDate) {
        StoreProductRow row = mapper.findProductDetail(storeId, productId);
        if (row == null) {
            return Optional.empty();
        }

        List<Long> productIds = List.of(productId);
        List<String> suppliers = suppliersByProduct(storeId, productIds)
                .getOrDefault(productId, List.of());
        PeriodSalesMetrics metrics = startDate == null
                ? null
                : metricsByProduct(storeId, productIds, startDate, endDate)
                        .getOrDefault(productId, ZERO_METRICS);
        return Optional.of(toDetail(row, suppliers, metrics));
    }

    private Map<Long, List<String>> suppliersByProduct(long storeId, List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> suppliers = new LinkedHashMap<>();
        for (ProductSupplierRow row : mapper.findSuppliers(storeId, productIds)) {
            suppliers.computeIfAbsent(row.getProductId(), ignored -> new java.util.ArrayList<>())
                    .add(row.getSupplierName());
        }
        return suppliers;
    }

    private Map<Long, PeriodSalesMetrics> metricsByProduct(
            long storeId,
            List<Long> productIds,
            LocalDate startDate,
            LocalDate endDate) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PeriodSalesMetrics> metrics = new LinkedHashMap<>();
        for (ProductSalesMetricsRow row : mapper.findSalesMetrics(storeId, productIds, startDate, endDate)) {
            metrics.put(row.getProductId(), new PeriodSalesMetrics(
                    row.getSalesQuantity(),
                    row.getSalesAmount(),
                    row.getGrossProfitAmount()));
        }
        return metrics;
    }

    private StoreProductListItem toListItem(
            StoreProductRow row,
            List<String> supplierNames,
            PeriodSalesMetrics metrics) {
        return new StoreProductListItem(
                row.getProductId(),
                row.getBarcode(),
                row.getProductName(),
                row.getUnit(),
                row.getCategoryId(),
                row.getCategoryName(),
                row.getWarehouseId(),
                row.getWarehouseCode(),
                row.getWarehouseName(),
                supplierNames,
                row.getCurrentQuantity(),
                row.getInventoryStatus(),
                row.getDataStatus(),
                metrics);
    }

    private StoreProductDetail toDetail(
            StoreProductRow row,
            List<String> supplierNames,
            PeriodSalesMetrics metrics) {
        return new StoreProductDetail(
                row.getProductId(),
                row.getBarcode(),
                row.getProductName(),
                row.getUnit(),
                row.getCategoryId(),
                row.getCategoryCode(),
                row.getCategoryName(),
                row.getWarehouseId(),
                row.getWarehouseCode(),
                row.getWarehouseName(),
                row.getTaxCostPrice(),
                row.getSalePrice(),
                row.getRemarks(),
                supplierNames,
                row.getCurrentQuantity(),
                row.getInventoryStatus(),
                row.getDataStatus(),
                metrics);
    }
}
