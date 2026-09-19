package com.goat.userservice.loadbalancer;

import com.goat.common.constant.CommonConstant;
import jakarta.annotation.Resource;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NettyServiceLocator {


    @Resource
    private DiscoveryClient discoveryClient;


    public String getServiceInstance(String userId){
        List<ServiceInstance> instances = discoveryClient.getInstances(CommonConstant.DISCOVERY_CLIENT_NAME);
        if (instances.isEmpty()) {
            return null;
        }
        ServiceInstance instance = new UrlHashLoadBalancer().select(instances, userId);

        String nettyPort = instance.getMetadata() == null
                ? null
                : instance.getMetadata().get("netty-port");
        if (nettyPort == null || nettyPort.isBlank()) {
            throw new IllegalStateException("RealTimeService 缺少 netty-port metadata");
        }

        return instance.getHost() + ":" + nettyPort + CommonConstant.NETTY_SERVICE_URI;
    }
}
