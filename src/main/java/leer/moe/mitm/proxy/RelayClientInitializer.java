package leer.moe.mitm.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class RelayClientInitializer extends ChannelInitializer<SocketChannel> {
    private final static InternalLogger LOGGER = InternalLoggerFactory.getInstance(RelayClientInitializer.class);

    /**
     * proxy client ssl context
     */
    private final SslContext sslCtx;

    private final Channel clientChannel;

    public RelayClientInitializer(Channel clientChannel, SslContext sslCtx) {
        this.clientChannel = clientChannel;
        this.sslCtx = sslCtx;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
//        ch.pipeline().addLast(new LoggingHandler(LogLevel.INFO));
        // sslHandler -> httpClientCodec -> httpObjectAggregator -> relayHandler
        if (sslCtx != null) {
            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
        }
        ch.pipeline().addLast(new HttpClientCodec());
        ch.pipeline().addLast(new HttpObjectAggregator(512 * 1024));
        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                FullHttpResponse response = (FullHttpResponse) msg;
                response.headers().add("test", "from proxy");
                LOGGER.info("Tunnel Response: " + msg);
                clientChannel.writeAndFlush(msg);
            }

            @Override
            public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                ctx.channel().close();
                clientChannel.close();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                ctx.channel().close();
                clientChannel.close();
                cause.printStackTrace();
            }
        });
    }
}
