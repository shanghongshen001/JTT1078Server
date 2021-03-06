package com.tsingtech.jtt1078.live.handler;

import com.tsingtech.jtt1078.config.JTT1078ServerProperties;
import com.tsingtech.jtt1078.handler.Jtt1078ServerChannelInitializer;
import com.tsingtech.jtt1078.live.publish.PublishManager;
import com.tsingtech.jtt1078.live.subscriber.AbstractSubscriber;
import com.tsingtech.jtt1078.live.subscriber.AudioSubscriber;
import com.tsingtech.jtt1078.live.subscriber.VideoSubscriber;
import com.tsingtech.jtt1078.util.BeanUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Author: chrisliu
 * Date: 2020-03-02 09:19
 * Mail: gwarmdll@gmail.com
 */
@Slf4j
@ChannelHandler.Sharable
public class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static String app;
    static {
        app = BeanUtil.getBean(JTT1078ServerProperties.class).getApp();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        try {
            handleHttpRequest(ctx, msg);
        } catch (TypeMismatchException e) {
            FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST);
            sendHttpResponse(ctx, msg, res);
            e.printStackTrace();
        } catch (Exception e) {
            sendHttpResponse(ctx, msg, new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR));
            e.printStackTrace();
        }
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        // Handle a bad request.
        if (!req.decoderResult().isSuccess()) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
            return;
        }

        // Allow only GET methods.
        if (req.method() != GET) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
            return;
        }

        HttpHeaders headers = req.headers();
        String host = headers.get(HttpHeaderNames.HOST);
        if (StringUtils.isEmpty(host)) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        String path = decoder.path();

        Channel channel = ctx.channel();

        if (!path.startsWith(app) || path.length() < app.length()) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND));
            return;
        }

        String streamId = path.substring(app.length());
        Map<String, List<String>> parameters = decoder.parameters();
        List<String> type = parameters.get("type");
        List<String> duration = parameters.get("duration");
        if (CollectionUtils.isEmpty(type) || StringUtils.isEmpty(type.get(0))) {
            log.warn("The parameter type is absent.");
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
            return;
        }

        double doubleDuration = 0;

        if (type.get(0).equals("2")) {
            if (CollectionUtils.isEmpty(duration)) {
                log.warn("The parameter duration is absent.");
                sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
                return;
            } else {
                try {
                    doubleDuration = Double.parseDouble(duration.get(0));
                } catch (NumberFormatException e) {
                    log.warn("parameter duration can not convert to double java type, duration = {}", duration.get(0));
                    sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
                    return;
                }
            }
        }

        if (!req.headers().contains(UPGRADE) || !req.headers().contains(SEC_WEBSOCKET_KEY) || !req.headers().contains(SEC_WEBSOCKET_VERSION)) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
            return;
        }

        // Handshake
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req), null, true, 5 * 1024 * 1024);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel);
        } else {
            ChannelPipeline pipeline = ctx.pipeline();
            pipeline.remove(ctx.name());
            pipeline.addLast(new IdleStateHandler(0, 10, 0));
            pipeline.addLast(new WebSocketServerCompressionHandler());
            double finalDoubleDuration = doubleDuration;
            handshaker.handshake(channel, req).addListener(future -> {
                if (future.isSuccess()) {
                    AttributeKey<AbstractSubscriber> subscriberKey = AttributeKey.valueOf("subscriber");
                    AbstractSubscriber abstractSubscriber = null;
                    if (type.get(0).equals("1")) {
                        abstractSubscriber = new VideoSubscriber(channel, streamId);
                        PublishManager.INSTANCE.subscribe(abstractSubscriber);
                    } else if (type.get(0).equals("2")) {
                        abstractSubscriber = new AudioSubscriber(channel, streamId, finalDoubleDuration);
                        PublishManager.INSTANCE.subscribe(abstractSubscriber);
                    }
                    Optional.ofNullable(abstractSubscriber).ifPresent(abstractSubscriber1 ->
                            channel.attr(subscriberKey).set(abstractSubscriber1));
                } else {
                    handshaker.close(channel, new CloseWebSocketFrame());
                }
            });
            pipeline.addLast(new WebSocketServerHandler(streamId)).addLast(new SrsFlvMuxerHandler(streamId))
                    .addLast(Jtt1078ServerChannelInitializer.exceptionHandler);
        }

    }

    private static void sendHttpResponse(
            ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
        // Generate an error page if response getStatus code is not OK (200).
        int statusCode = res.status().code();
        if (statusCode != OK.code() && res.content().readableBytes() == 0) {
            ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
        }
        HttpUtil.setContentLength(res, res.content().readableBytes());

        // Send the response and close the connection if necessary.
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!HttpUtil.isKeepAlive(req) || statusCode != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String getWebSocketLocation(FullHttpRequest req) {
        String location = req.headers().get(HttpHeaderNames.HOST) + req.uri();
        return "ws://" + location;
    }
}
