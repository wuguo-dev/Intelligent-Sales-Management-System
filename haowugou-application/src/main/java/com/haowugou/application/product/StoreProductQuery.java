package com.haowugou.application.product;

import com.haowugou.application.operating.StoreNotFoundException;
import com.haowugou.domain.product.StoreProductDetail;
import com.haowugou.domain.product.StoreProductQueryCriteria;
import com.haowugou.domain.product.StoreProductQueryRepository;
import com.haowugou.domain.store.Store;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import com.haowugou.domain.warehouse.WarehouseSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 门店范围商品、库存、仓库和期间销售查询的应用用例。 */
public final class StoreProductQuery {

    private final StoreRepository storeRepository;
    private final WarehouseRepository warehouseRepository;
    private final StoreProductQueryRepository productRepository;

    public StoreProductQuery(
            StoreRepository storeRepository,
            WarehouseRepository warehouseRepository,
            StoreProductQueryRepository productRepository) {
        this.storeRepository = Objects.requireNonNull(storeRepository);
        this.warehouseRepository = Objects.requireNonNull(warehouseRepository);
        this.productRepository = Objects.requireNonNull(productRepository);
    }

    public StoreProductPageResult listProducts(StoreProductQueryCriteria criteria) {
        validateCriteria(criteria);
        Store store = requireActiveStore(criteria.storeId());
        if (criteria.warehouseId() != null
                && !warehouseRepository.existsByStoreIdAndId(criteria.storeId(), criteria.warehouseId())) {
            throw new WarehouseNotInStoreException(criteria.storeId(), criteria.warehouseId());
        }
        return new StoreProductPageResult(store, productRepository.findPage(criteria));
    }

    public StoreProductDetailResult findProduct(
            long storeId,
            long productId,
            LocalDate startDate,
            LocalDate endDate) {
        requirePositive(storeId, "门店ID");
        requirePositive(productId, "商品ID");
        validateDateRange(startDate, endDate);
        Store store = requireActiveStore(storeId);
        StoreProductDetail product = productRepository
                .findDetail(storeId, productId, startDate, endDate)
                .orElseThrow(() -> new StoreProductNotFoundException(storeId, productId));
        return new StoreProductDetailResult(store, product);
    }

    public List<WarehouseSummary> listWarehouses(long storeId) {
        requirePositive(storeId, "门店ID");
        requireActiveStore(storeId);
        return List.copyOf(warehouseRepository.findAllActiveByStoreId(storeId));
    }

    private void validateCriteria(StoreProductQueryCriteria criteria) {
        if (criteria == null) {
            throw new InvalidStoreProductQueryException("查询条件不能为空");
        }
        requirePositive(criteria.storeId(), "门店ID");
        requireOptionalPositive(criteria.categoryId(), "品类ID");
        requireOptionalPositive(criteria.supplierId(), "供应商ID");
        requireOptionalPositive(criteria.warehouseId(), "仓库ID");
        if (criteria.page() < 0) {
            throw new InvalidStoreProductQueryException("页码不能小于0");
        }
        if (criteria.size() < 1 || criteria.size() > 100) {
            throw new InvalidStoreProductQueryException("每页数量必须在1到100之间");
        }
        validateStockRange(criteria.minStock(), criteria.maxStock());
        validateDateRange(criteria.startDate(), criteria.endDate());
    }

    private Store requireActiveStore(long storeId) {
        return storeRepository.findActiveById(storeId)
                .orElseThrow(() -> new StoreNotFoundException(storeId));
    }

    private void requirePositive(long value, String label) {
        if (value <= 0) {
            throw new InvalidStoreProductQueryException(label + "必须大于0");
        }
    }

    private void requireOptionalPositive(Long value, String label) {
        if (value != null) {
            requirePositive(value, label);
        }
    }

    private void validateStockRange(BigDecimal minStock, BigDecimal maxStock) {
        if (minStock != null && maxStock != null && minStock.compareTo(maxStock) > 0) {
            throw new InvalidStoreProductQueryException("最小库存不能大于最大库存");
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if ((startDate == null) != (endDate == null)) {
            throw new InvalidStoreProductQueryException("开始日期和结束日期必须同时提供");
        }
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new InvalidStoreProductQueryException("开始日期不能晚于结束日期");
        }
    }
}
