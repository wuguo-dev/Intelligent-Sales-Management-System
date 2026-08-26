package com.haowugou;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 好物购百货商场智能经营分析系统的 Spring Boot 启动入口。 */
@SpringBootApplication(scanBasePackages = "com.haowugou")
public class HaowugouApplication {

    public static void main(String[] args) {
        SpringApplication.run(HaowugouApplication.class, args);
    }
}
