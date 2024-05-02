package leer.moe.mitm;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

/**
 * @author leereindeer
 * @date 5/2/24, Thursday
 **/
public class HttpProxyServerHandler extends ChannelInboundHandlerAdapter {
    private ChannelFuture tunnelChannel;
    private String hostname;
    private int port;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object clientMsg) throws Exception {
        Channel clientChannel = ctx.channel();

        if (clientMsg instanceof FullHttpRequest httpRequest) {
            System.out.println("Headers:" + httpRequest.headers());
            System.out.println("Method:" + httpRequest.method());
            String host = httpRequest.headers().get(HttpHeaderNames.HOST);
            System.out.println("Host:" + host);
            extractHostAndPort(httpRequest, host);

            if (httpRequest.method().equals(HttpMethod.CONNECT)) {
                // blind forwarding proxy tunnel using CONNECT
                System.out.println("CONNECT request:" + clientMsg);
                clientChannel.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
                ctx.pipeline().remove("httpServerCodec");
                ctx.pipeline().remove("httpObjectAggregator");
                return;
            }

            // message-forwarding HTTP proxy
            ChannelFuture forwardChannelFuture = NettyTemplate.newNioClient(hostname, port, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast(new HttpClientCodec());
                    p.addLast(new HttpObjectAggregator(512 * 1024));
                    p.addLast(new ProxyModifyHandlerAdapter(clientChannel));
                }
            });

            forwardChannelFuture.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    System.out.println("Proxy request:" + clientMsg);
                    future.channel().writeAndFlush(clientMsg);
                } else {
                    clientChannel.close();
                }
            }).addListener((ChannelFutureListener.CLOSE_ON_FAILURE));
        } else {
            if (null == tunnelChannel) {
                ByteBuf clientMsgByteBuf = (ByteBuf) clientMsg;
                // blind forwarding proxy tunnel using CONNECT
                tunnelChannel = NettyTemplate.newNioClient(hostname, port, ctx.channel().eventLoop(), new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                System.out.println("Tunnel Response: " + clientMsgByteBuf.capacity());
                                clientChannel.writeAndFlush(msg);
                            }
                        });
                    }
                });
                tunnelChannel.addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        System.out.println("Tunnel request:" + ((ByteBuf) clientMsg).capacity());
                        future.channel().writeAndFlush(clientMsg);
                    } else {
                        ctx.channel().close();
                    }
                });
            } else {
                System.out.println("Reused Tunnel request:" + clientMsg);
                tunnelChannel.channel().writeAndFlush(clientMsg);
            }
        }
    }

    private void extractHostAndPort(FullHttpRequest httpRequest, String host) {
        String[] hostAndPortArr = host.split(":");
        hostname = hostAndPortArr[0];
        if (hostAndPortArr.length > 1) {
            port = Integer.parseInt(hostAndPortArr[1]);
        } else {
            if (httpRequest.uri().indexOf("https") == 0) {
                port = 443;
            } else {
                port = 80;
            }
        }
    }

    private static class ProxyModifyHandlerAdapter extends ChannelInboundHandlerAdapter {
        private final Channel clientChannel;

        public ProxyModifyHandlerAdapter(Channel clientChannel) {
            this.clientChannel = clientChannel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            FullHttpResponse response = (FullHttpResponse) msg;
            response.headers().add("test", "from proxy");
            System.out.println("Proxy Response:" + msg);
            clientChannel.writeAndFlush(msg);
        }
    }
}
