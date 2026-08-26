package com.haowugou.config;

import com.haowugou.application.product.StoreProductQuery;
import com.haowugou.domain.product.StoreProductQueryRepository;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 门店商品查询模块的组装点。
 *
 * <p>应用层只依赖 Repository 接口；启动模块在这里注入基础设施 Adapter，保持依赖方向单向。
 */
@Configuration(proxyBeanMethods = false)
public class StoreProductConfiguration {

    @Bean
    StoreProductQuery storeProductQuery(
            StoreRepository storeRepository,
            WarehouseRepository warehouseRepository,
            StoreProductQueryRepository productRepository) {
        return new StoreProductQuery(storeRepository, warehouseRepository, productRepository);
    }
}