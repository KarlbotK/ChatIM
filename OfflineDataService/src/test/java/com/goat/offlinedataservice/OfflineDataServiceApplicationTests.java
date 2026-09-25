package com.goat.offlinedataservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "canal.enabled=false")
class OfflineDataServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
