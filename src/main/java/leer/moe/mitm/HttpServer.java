package leer.moe.mitm;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;

import javax.net.ssl.SSLEngine;

import java.security.cert.X509Certificate;
import java.util.Arrays;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpServer {
    private int port;

    private boolean tls;

    public HttpServer(int port, boolean tls) {
        this.port = port;
        this.tls = tls;
    }

    public HttpServer(int port) {
        this(port, false);
    }

    public void run() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate();
        SslContext sslContext = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build();

        NettyTemplate.newNioServer(port, new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                SSLEngine engine = null;
                if (tls) {
                    engine = sslContext.newEngine(ch.alloc());
                    p.addFirst("ssl", new SslHandler(engine));
                }
                p.addLast(new HttpServerCodec());
                p.addLast(new HttpObjectAggregator(512 * 1024));
                p.addLast(new FullHttpRequestChannelInboundHandler(engine));
            }
        });
    }


    public static void main(String[] args) throws Exception {
        HttpServer httpServer = new HttpServer(8089);
        httpServer.run();
    }

    private static class FullHttpRequestChannelInboundHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final SSLEngine engine;

        public FullHttpRequestChannelInboundHandler(SSLEngine engine) {
            this.engine = engine;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx,
                                    FullHttpRequest fullHttpRequest) throws Exception {
            System.out.println("Uri:" + fullHttpRequest.uri());
            System.out.println("Headers:" + fullHttpRequest.headers().toString());
            System.out.println("Body:" + fullHttpRequest.content().toString(CharsetUtil.UTF_8));
            if (null != engine) {
                System.out.println("TLS version:" + engine.getSession().getProtocol());
                System.out.println("Cipher suite:" + engine.getSession().getCipherSuite());
                X509Certificate x509Certificate = (X509Certificate) engine.getSession().getLocalCertificates()[0];
                System.out.println("Server certificate serverIssuer:" + x509Certificate.getIssuerX500Principal());
                System.out.println("Server certificate subject:" + x509Certificate.getSubjectX500Principal());
                System.out.println("Server certificate valid from:" + x509Certificate.getNotBefore() + " to:" + x509Certificate.getNotAfter());
            }

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
            cause.printStackTrace();
        }
    }
}
