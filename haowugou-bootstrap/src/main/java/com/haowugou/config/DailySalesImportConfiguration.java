package com.haowugou.config;

import com.haowugou.application.salesimport.PostDailySalesImport;
import com.haowugou.domain.salesimport.DailySalesFileParser;
import com.haowugou.domain.salesimport.DailySalesImportRepository;
import com.haowugou.domain.store.StoreRepository;
import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 每日销售导入模块的组装点。
 *
 * <p>应用层只依赖 Repository 与解析端口；启动模块在这里注入基础设施 Adapter 与
 * EasyExcel 解析实现。{@code LocalDate::now} 用于校验业务日期不晚于今天。
 */
@Configuration(proxyBeanMethods = false)
public class DailySalesImportConfiguration {

    @Bean
    PostDailySalesImport postDailySalesImport(
            StoreRepository storeRepository,
            DailySalesImportRepository dailySalesImportRepository,
            DailySalesFileParser dailySalesFileParser) {
        return new PostDailySalesImport(
                storeRepository,
                dailySalesImportRepository,
                dailySalesFileParser,
                LocalDate::now);
    }
}
