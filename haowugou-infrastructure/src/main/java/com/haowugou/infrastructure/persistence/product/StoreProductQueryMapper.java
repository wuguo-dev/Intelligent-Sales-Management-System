package com.haowugou.infrastructure.persistence.product;

import com.haowugou.domain.product.StoreProductQueryCriteria;
import com.haowugou.infrastructure.persistence.product.ProductSalesMetricsRow;
import com.haowugou.infrastructure.persistence.product.ProductSupplierRow;
import com.haowugou.infrastructure.persistence.product.StoreProductRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 门店商品分页、详情、供应商和期间销售的批量查询 Mapper。 */
@Mapper
public interface StoreProductQueryMapper {

    long countProducts(@Param("criteria") StoreProductQueryCriteria criteria);

    List<StoreProductRow> findProductPage(
            @Param("criteria") StoreProductQueryCriteria criteria,
            @Param("offset") long offset,
            @Param("limit") int limit);

    StoreProductRow findProductDetail(
            @Param("storeId") long storeId,
            @Param("productId") long productId);

    List<ProductSupplierRow> findSuppliers(
            @Param("storeId") long storeId,
            @Param("productIds") List<Long> productIds);

    List<ProductSalesMetricsRow> findSalesMetrics(
            @Param("storeId") long storeId,
            @Param("productIds") List<Long> productIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
