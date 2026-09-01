package com.haowugou.infrastructure.persistence.sales;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.haowugou.domain.sales.StoreDailySales;
import com.haowugou.domain.sales.StoreDailySalesRepository;
import com.haowugou.infrastructure.persistence.sales.StoreDailySalesDataObject;
import com.haowugou.infrastructure.persistence.sales.StoreDailySalesMapper;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用 MyBatis 实现领域层门店日销售 Repository 接口的持久化 Adapter。 */
@Repository
public class MybatisStoreDailySalesRepository implements StoreDailySalesRepository {

    private final StoreDailySalesMapper mapper;

    public MybatisStoreDailySalesRepository(StoreDailySalesMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StoreDailySales> findByStoreAndDate(long storeId, LocalDate businessDate) {
        StoreDailySalesDataObject row = mapper.selectOne(
                Wrappers.<StoreDailySalesDataObject>lambdaQuery()
                        .eq(StoreDailySalesDataObject::getStoreId, storeId)
                        .eq(StoreDailySalesDataObject::getBusinessDate, businessDate));
        return Optional.ofNullable(row).map(this::toDomain);
    }

    private StoreDailySales toDomain(StoreDailySalesDataObject row) {
        return new StoreDailySales(
                row.getId(),
                row.getStoreId(),
                row.getBusinessDate(),
                row.getTotalSalesAmount(),
                row.getOrderCount(),
                row.getRefundAmount(),
                row.getGrossProfitAmount(),
                row.getDataOrigin());
    }
}
