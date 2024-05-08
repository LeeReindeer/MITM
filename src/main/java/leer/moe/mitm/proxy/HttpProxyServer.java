package leer.moe.mitm.proxy;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import leer.moe.mitm.NettyTemplate;
import leer.moe.mitm.cert.CertUtil;
import leer.moe.mitm.cert.HttpProxyCertConfig;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class HttpProxyServer {
    private final static InternalLogger LOGGER = InternalLoggerFactory.getInstance(HttpProxyServer.class);

    private int port;

    private HttpProxyCertConfig certConfig;

    public HttpProxyServer(int port) {
        this.port = port;
    }

    /**
     * start a netty http proxy
     */
    public void run() throws Exception {
        certConfig = initCertConfig();

        NettyTemplate.newNioServer(port, new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast("httpServerCodec", new HttpServerCodec());
                ch.pipeline().addLast("httpObjectAggregator", new HttpObjectAggregator(512 * 1024));
                ch.pipeline().addLast("httpProxyServerHandler", new HttpProxyServerHandler(certConfig));
            }
        });
    }

    protected HttpProxyCertConfig initCertConfig() throws Exception {
        File caPriKeyFile = new File("./ca_private.der");
        File caCertFile = new File("./ca.crt");
        X509Certificate caCert = CertUtil.loadCert(new FileInputStream(caCertFile));
        PrivateKey caPriKey = CertUtil.loadPriKey(new FileInputStream(caPriKeyFile));

        // generate random pair for ssl certificate
        KeyPair keyPair = CertUtil.genKeyPair();
        HttpProxyCertConfig certConfig = new HttpProxyCertConfig(
                // use CA subject as server's serverIssuer
                CertUtil.getSubject(caCert), caCert.getNotBefore(), caCert.getNotAfter(), caPriKey, keyPair.getPrivate(), keyPair.getPublic()
        );
        LOGGER.info("Proxy cert config: " + certConfig);
        return certConfig;
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
