package leer.moe.mitm.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import leer.moe.mitm.NettyTemplate;
import leer.moe.mitm.cert.CertUtil;
import leer.moe.mitm.cert.HttpProxyCertConfig;

import java.net.InetSocketAddress;

/**
 * @author leereindeer
 * @date 5/2/24, Thursday
 **/
public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {
    private final static InternalLogger LOGGER = InternalLoggerFactory.getInstance(HttpProxyServerHandler.class);

    private HttpProxyCertConfig certConfig;

    public HttpProxyServerHandler(HttpProxyCertConfig certConfig) {
        this.certConfig = certConfig;
    }

    private ChannelFuture tunnelChannel;
    private String originHost;
    private int originPort = -1;
    private boolean isHttps;


    @Override
    public void channelRead(ChannelHandlerContext ctx, final Object clientMsg) throws Exception {
        Channel clientChannel = ctx.channel();
        if (clientMsg instanceof FullHttpRequest httpRequest) {
            LOGGER.info("URI:" + httpRequest.uri());
            LOGGER.info("Headers:" + httpRequest.headers().entries());
            LOGGER.info("Method:" + httpRequest.method());
            String host = httpRequest.headers().get(HttpHeaderNames.HOST);
            LOGGER.info("Host:" + host);
            if (StringUtil.isNullOrEmpty(this.originHost) || originPort == -1) {
                extractHostAndPort(httpRequest, host);
            }

            if (httpRequest.method().equals(HttpMethod.CONNECT)) {
                // HTTPS: blind forwarding proxy tunnel using CONNECT
                tunnelProxyResponse(ctx, clientMsg, clientChannel);
                ctx.pipeline().remove("httpServerCodec");
                ctx.pipeline().remove("httpObjectAggregator");
                ReferenceCountUtil.release(clientMsg);
            } else {
                LOGGER.info("isHttps:" + isHttps);
                // relay client request to target server
                tunnelProxyRelay(ctx, clientMsg, clientChannel, isHttps);
            }
        } else {
            ByteBuf clientMsgByteBuf = (ByteBuf) clientMsg;
            // https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml#tls-parameters-5
            // TLS ContentType: 22 handshake
            if (clientMsgByteBuf.getByte(0) == 22) {
                isHttps = true;
                LOGGER.info("SSL handshake request:" + ((ByteBuf) clientMsg).readableBytes() + " bytes");
                int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
                // build fake server cert
                SslContext sslCtx = SslContextBuilder.forServer(certConfig.serverPriKey(), CertUtil.getCert(port, originHost, certConfig)).build();
                // as fake https server
                // sslHandler ->  httpServerCodec -> httpObjectAggregator -> httpProxyServerHandler
                ctx.pipeline().addFirst("httpObjectAggregator", new HttpObjectAggregator(512 * 1024));
                ctx.pipeline().addFirst("httpServerCodec", new HttpServerCodec());
                ctx.pipeline().addFirst("sslHandle", sslCtx.newHandler(ctx.alloc()));
                // 重新过一遍pipeline，拿到解密后的的http报文
                ctx.pipeline().fireChannelRead(clientMsg);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.channel().close();
        cause.printStackTrace();
    }

    /**
     * @param ctx
     * @param clientMsg
     * @param clientChannel
     * @throws Exception
     */
    private void tunnelProxyRelay(ChannelHandlerContext ctx, Object clientMsg, Channel clientChannel, boolean isHttps) throws Exception {
        if (null == tunnelChannel) {
            SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            tunnelChannel = NettyTemplate.newNioClient(originHost, originPort, ctx.channel().eventLoop(), new RelayClientInitializer(clientChannel, isHttps ? sslCtx : null));
            tunnelChannel.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    LOGGER.info("Tunnel connected: " + originHost + ":" + originPort);
                    LOGGER.info("Tunnel request:" + clientMsg);
                    future.channel().writeAndFlush(clientMsg);
                } else {
                    ctx.channel().close();
                }
            });
        } else {
            if (tunnelChannel.channel().isActive()) {
                LOGGER.info("Reused Tunnel request:" + ((ByteBuf) clientMsg).readableBytes() + " bytes");
                tunnelChannel.channel().writeAndFlush(clientMsg);
            } else {
                LOGGER.info("Tunnel closed: " + originHost + ":" + originPort);
                tunnelChannel.channel().close();
                clientChannel.close();
            }
        }
    }

    /**
     * HTTPS usually use CONNECT tunnel proxy, but HTTP also can use it
     *
     * @param ctx
     * @param clientMsg
     * @param clientChannel
     */
    private static void tunnelProxyResponse(ChannelHandlerContext ctx, Object clientMsg, Channel clientChannel) {
        clientChannel.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        LOGGER.info("CONNECT tunnel established, response 200");
    }

    private void extractHostAndPort(FullHttpRequest httpRequest, String host) {
        String[] hostAndPortArr = host.split(":");
        originHost = hostAndPortArr[0];
        if (hostAndPortArr.length == 2) {
            originPort = Integer.parseInt(hostAndPortArr[1]);
        } else {
            originPort = 80;
            LOGGER.error("invalid host: " + host + ", use default port: " + originPort);
        }
        LOGGER.info("target host: " + originHost + ": " + originPort);
    }
}
