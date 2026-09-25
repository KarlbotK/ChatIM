package com.goat.realtimeservice.websocket;

import com.goat.common.constant.CommonConstant;
import com.goat.common.utils.AuthTokenUtil;
import com.goat.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class WebSocketAuthHeader extends ChannelInboundHandlerAdapter {

    private final StringRedisTemplate stringRedisTemplate;

    private final WebSocketRouteService webSocketRouteService;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        if (!CommonConstant.NETTY_SERVICE_URI.equals(decoder.path())) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            String userId = authenticate(request, decoder);
            if (userId == null) {
                reject(ctx, msg);
                return;
            }

            request.setUri(decoder.path());
            ChannelManager.addUserChannel(userId, ctx.channel());
            ChannelManager.addChannelUser(userId, ctx.channel());
            webSocketRouteService.bind(userId, ctx.channel());
            ctx.fireChannelRead(msg);
        } catch (Exception exception) {
            reject(ctx, msg);
        }
    }

    private String authenticate(FullHttpRequest request, QueryStringDecoder decoder) {
        String ticket = firstValue(decoder.parameters(), "ticket");
        if (ticket != null) {
            return authenticateTicket(request, decoder.path(), ticket);
        }
        return authenticateHeader(request.headers().get(HttpHeaderNames.AUTHORIZATION));
    }

    private String authenticateHeader(String authorization) {
        String accessToken = AuthTokenUtil.extract(authorization);
        Claims claims = JwtUtil.parse(accessToken);
        if (claims == null || claims.getSubject() == null || claims.getSubject().isBlank()) {
            return null;
        }

        String userId = claims.getSubject();
        String storedToken = stringRedisTemplate.opsForValue()
                .get(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
        return accessToken.equals(storedToken) ? userId : null;
    }

    private String authenticateTicket(FullHttpRequest request, String path, String ticket) {
        if (ticket.isBlank()) {
            return null;
        }

        String ticketValue = stringRedisTemplate.opsForValue()
                .getAndDelete(CommonConstant.WEBSOCKET_TICKET_PREFIX + ticket);
        if (ticketValue == null) {
            return null;
        }

        String[] ticketParts = ticketValue.split("\\|", -1);
        if (ticketParts.length != 3) {
            return null;
        }

        String userId = ticketParts[0];
        String storedToken = stringRedisTemplate.opsForValue()
                .get(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
        if (storedToken == null
                || !ticketParts[1].equals(AuthTokenUtil.fingerprint(storedToken))) {
            return null;
        }

        String host = request.headers().get(HttpHeaderNames.HOST);
        if (host == null
                || !ticketParts[2].equals(AuthTokenUtil.webSocketTargetFingerprint(host + path))) {
            return null;
        }

        Claims claims = JwtUtil.parse(storedToken);
        return claims != null && userId.equals(claims.getSubject()) ? userId : null;
    }

    private String firstValue(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private void reject(ChannelHandlerContext ctx, Object msg) {
        ReferenceCountUtil.release(msg);
        ctx.close();
    }
}
