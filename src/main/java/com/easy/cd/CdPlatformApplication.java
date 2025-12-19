package com.easy.cd;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.easy.cd.mapper")
@EnableScheduling
public class CdPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(CdPlatformApplication.class, args);
    }
}
