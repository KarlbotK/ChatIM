package com.goat.realtimeservice.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.NettyRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
@RequiredArgsConstructor
public class NettyService {


    private final int port = 9101;

    //*1(大堂经理)
    private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);

    //核心线程数*2 (餐厅服务员)
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(NettyRuntime.availableProcessors() * 2);

    private final StringRedisTemplate stringRedisTemplate;

    private final KafkaTemplate<String, String> kafkaTemplate;


    @PostConstruct
    public void start() throws InterruptedException {
        run();
    }


    public void run() throws InterruptedException {

        // 1. 创建Netty服务端启动器
        ServerBootstrap serverBootstrap = new ServerBootstrap();

        serverBootstrap
                // 2. bossGroup接收新连接，workerGroup处理连接上的消息
                .group(bossGroup, workerGroup)
                // 3. 使用Java NIO实现服务端监听Channel
                .channel(NioServerSocketChannel.class)
                // 4. 每当接收到一个客户端连接，就初始化它的Pipeline
                .childHandler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        // 5. 获取这个客户端连接自己的Pipeline
                        ChannelPipeline channelPipeline = socketChannel.pipeline();
                        //读心跳
                        channelPipeline.addLast(new IdleStateHandler(600000,0,0));
                        // 6. HTTP请求解码 + HTTP响应编码
                        channelPipeline.addLast(new HttpServerCodec());
                        // 7. 把分段HTTP请求聚合成完整HTTP请求
                        channelPipeline.addLast(new HttpObjectAggregator(65536));
                        //7.5 验证Token
                        channelPipeline.addLast(new WebSocketAuthHeader(stringRedisTemplate));
                        // 8. 把HTTP连接升级为WebSocket连接
                        channelPipeline.addLast(new WebSocketServerProtocolHandler("/ws/netty"));
                        // 9. 处理真正的WebSocket聊天消息
                        channelPipeline.addLast(new WebSocketHandler(stringRedisTemplate,kafkaTemplate));

                    }
                });
        // 10. 绑定端口，并等待绑定完成
        serverBootstrap.bind(port).sync();
    }


    @PreDestroy
    public void destroy() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
