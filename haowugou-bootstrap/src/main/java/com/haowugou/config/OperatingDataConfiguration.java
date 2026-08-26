package com.haowugou.config;

import com.haowugou.application.operating.OperatingDataQuery;
import com.haowugou.domain.inventory.InventoryRepository;
import com.haowugou.domain.sales.StoreDailySalesRepository;
import com.haowugou.domain.store.StoreRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 经营数据模块的组装点。

 * <p>应用层只依赖 Repository 接口；启动模块在这里注入基础设施 Adapter，保持依赖方向单向。
 */
@Configuration(proxyBeanMethods = false)
public class OperatingDataConfiguration {

    @Bean
    OperatingDataQuery operatingDataQuery(
            StoreRepository storeRepository,
            StoreDailySalesRepository salesRepository,
            InventoryRepository inventoryRepository) {
        return new OperatingDataQuery(storeRepository, salesRepository, inventoryRepository);
    }
}
