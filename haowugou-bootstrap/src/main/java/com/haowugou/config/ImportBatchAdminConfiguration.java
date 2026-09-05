package com.haowugou.config;

import com.haowugou.application.importbatch.ImportBatchQuery;
import com.haowugou.application.importbatch.ReverseImportBatch;
import com.haowugou.domain.importbatch.ImportBatchQueryRepository;
import com.haowugou.domain.importbatch.ImportBatchReversalRepository;
import com.haowugou.domain.store.StoreRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 导入批次管理模块（查询与撤销）的组装点。
 *
 * <p>两条导入链路的批次共用这一套查询与撤销用例：撤销只依赖 {@code inventory_movement}
 * 的原流水，与批次是初始库存还是每日销售无关。
 */
@Configuration(proxyBeanMethods = false)
public class ImportBatchAdminConfiguration {

    @Bean
    ImportBatchQuery importBatchQuery(
            StoreRepository storeRepository,
            ImportBatchQueryRepository importBatchQueryRepository) {
        return new ImportBatchQuery(storeRepository, importBatchQueryRepository);
    }

    @Bean
    ReverseImportBatch reverseImportBatch(
            StoreRepository storeRepository,
            ImportBatchQueryRepository importBatchQueryRepository,
            ImportBatchReversalRepository importBatchReversalRepository) {
        return new ReverseImportBatch(
                storeRepository, importBatchQueryRepository, importBatchReversalRepository);
    }
}
