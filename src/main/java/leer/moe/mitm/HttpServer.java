package leer.moe.mitm;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;

import javax.net.ssl.SSLEngine;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpServer {
    private int port;

    public HttpServer(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        SelfSignedCertificate cert = new SelfSignedCertificate();
        SslContext sslContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build();

        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
//                        p.addLast(new HttpRequestDecoder());
//                        p.addLast(new HttpResponseEncoder());
//                        p.addLast(new CustomHttpServerHandler());
                        SSLEngine engine = sslContext.newEngine(ch.alloc());
                        p.addFirst("ssl", new SslHandler(engine));
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(512 * 1024));
                        p.addLast(new FullHttpRequestChannelInboundHandler());
                    }
                });

        ChannelFuture f = b.bind(port).sync();
        f.channel().closeFuture().sync();
    }


    public static void main(String[] args) throws Exception {
        HttpServer httpServer = new HttpServer(8089);
        httpServer.run();
    }

    private static class FullHttpRequestChannelInboundHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx,
                                    FullHttpRequest fullHttpRequest) throws Exception {
            System.out.println("Uri:" + fullHttpRequest.uri());
            System.out.println("Headers:" + fullHttpRequest.headers().toString());
            System.out.println("Body:" + fullHttpRequest.content().toString(CharsetUtil.UTF_8));

            FullHttpResponse httpResponse = new DefaultFullHttpResponse(HTTP_1_1, OK,
                    Unpooled.copiedBuffer("Hello Netty", CharsetUtil.UTF_8));
            httpResponse.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            httpResponse.headers()
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(httpResponse)
                    .addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            System.out.println("error:" + cause.getMessage());
        }
    }
}
