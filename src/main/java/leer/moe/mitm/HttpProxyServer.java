package leer.moe.mitm;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class HttpProxyServer {
    private int port;

    public HttpProxyServer(int port) {
        this.port = port;
    }

    /**
     * start a netty http proxy
     */
    public void run() throws Exception {
        NettyTemplate.newNioServer(port, new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast("httpServerCodec", new HttpServerCodec());
                ch.pipeline().addLast("httpObjectAggregator", new HttpObjectAggregator(512 * 1024));
                ch.pipeline().addLast("httpProxyServerHandler", new HttpProxyServerHandler());
            }
        });
    }

    public static void main(String[] args) {
        HttpProxyServer httpProxyServer = new HttpProxyServer(8080);
        try {
            httpProxyServer.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
