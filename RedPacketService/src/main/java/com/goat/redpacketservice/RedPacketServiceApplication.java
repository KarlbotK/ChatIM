package com.goat.redpacketservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.goat.redpacketservice", "com.goat.common"})
@EnableFeignClients(basePackages = "com.goat.redpacketservice.client")
@EnableScheduling
public class RedPacketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedPacketServiceApplication.class, args);
    }

}
