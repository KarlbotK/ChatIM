package com.goat.realtimeservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "realtime.redis-push.enabled=false")
class RealTimeServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
