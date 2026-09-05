package com.haowugou.config;

import com.haowugou.application.inventoryimport.PostInitialInventoryImport;
import com.haowugou.domain.importbatch.ImportBatchRepository;
import com.haowugou.domain.importbatch.ImportFileParser;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.warehouse.WarehouseRepository;
import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 初始库存导入模块的组装点。
 *
 * <p>应用层只依赖 Repository 与解析端口；启动模块在这里注入基础设施 Adapter 与
 * EasyExcel 解析实现，数据归属日期取导入当天。
 */
@Configuration(proxyBeanMethods = false)
public class InitialInventoryImportConfiguration {

    @Bean
    PostInitialInventoryImport postInitialInventoryImport(
            StoreRepository storeRepository,
            WarehouseRepository warehouseRepository,
            ImportBatchRepository importBatchRepository,
            ImportFileParser importFileParser) {
        return new PostInitialInventoryImport(
                storeRepository,
                warehouseRepository,
                importBatchRepository,
                importFileParser,
                LocalDate::now);
    }
}
