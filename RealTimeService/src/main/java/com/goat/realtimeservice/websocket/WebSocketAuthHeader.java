package com.goat.realtimeservice.websocket;

import com.goat.common.constant.CommonConstant;
import com.goat.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;


@RequiredArgsConstructor
public class WebSocketAuthHeader extends ChannelInboundHandlerAdapter {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)  {
        if (msg instanceof FullHttpRequest request){
            String authHeader = request.headers().get("Authorization");
            if (authHeader == null || authHeader.isEmpty()) {
                ctx.close();
                return;
            }
            try {
                Claims claims = JwtUtil.parse(authHeader);
                if (claims == null) {
                    ctx.close();
                    return;
                }

                String userId = claims.getSubject();
                if (userId == null || userId.isEmpty()) {
                    ctx.close();
                    return;
                }

                String storedToken = stringRedisTemplate.opsForValue().get(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
                if (StringUtils.isEmpty(storedToken) || !authHeader.equals(storedToken)) {
                    ctx.close();
                    return;
                }

                // 3. 绑定用户与 channel
                ChannelManager.addUserChannel(userId, ctx.channel());
                ChannelManager.addChannelUser(userId, ctx.channel());
                ctx.fireChannelRead(msg);
            } catch (Exception e) {
                // 记录日志
                ctx.close();
            }
            /*ctx.fireChannelRead(msg);*/
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
