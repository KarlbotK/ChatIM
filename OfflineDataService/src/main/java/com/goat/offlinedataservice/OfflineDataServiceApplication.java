package com.goat.offlinedataservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;


@SpringBootApplication(scanBasePackages = {"com.goat.offlinedataservice", "com.goat.common"})
@EnableFeignClients(basePackages = "com.goat.offlinedataservice.client")
public class OfflineDataServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfflineDataServiceApplication.class, args);
    }

}
