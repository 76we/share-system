package com.we.sharesystem;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.we.sharesystem.mapper")
public class ShareSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShareSystemApplication.class, args);
    }

}
