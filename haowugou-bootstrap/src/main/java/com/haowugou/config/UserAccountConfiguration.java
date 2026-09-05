package com.haowugou.config;

import com.haowugou.application.user.UserAccountQuery;
import com.haowugou.domain.store.StoreRepository;
import com.haowugou.domain.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户账号模块的组装点。
 *
 * <p>认证相关的基础设施 Bean（密码编码器、{@code UserDetailsService}、过滤器链）在
 * {@code SecurityConfiguration}；这里只装应用用例，保持「一个功能模块一个配置类」的惯例。
 */
@Configuration(proxyBeanMethods = false)
public class UserAccountConfiguration {

    @Bean
    UserAccountQuery userAccountQuery(
            UserRepository userRepository, StoreRepository storeRepository) {
        return new UserAccountQuery(userRepository, storeRepository);
    }
}
