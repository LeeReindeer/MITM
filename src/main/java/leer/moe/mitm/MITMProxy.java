package leer.moe.mitm;

import org.bouncycastle.tls.TlsServerProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public class MITMProxy {
    public static void main(String[] args) {
        int proxyPort = 10000; // 代理服务器端口

        try (ServerSocket serverSocket = new ServerSocket(proxyPort)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (clientSocket;
             InputStream clientInput = clientSocket.getInputStream();
             OutputStream clientOutput = clientSocket.getOutputStream()) {
            byte[] clientReqBuffer = new byte[1024];
            // 读取客户端请求
            int bytesRead = clientInput.read(clientReqBuffer);
            if (bytesRead >= 0 && clientReqBuffer[0] == 0x05) { // 检查 SOCKS5 协议版本
                clientOutput.write(new byte[]{0x05, 0x00}); // 告知客户端无需认证

                bytesRead = clientInput.read(clientReqBuffer);
                if (bytesRead >= 0 && clientReqBuffer[0] == 0x05 && clientReqBuffer[1] == 0x01 && clientReqBuffer[2] == 0x00) {
                    // 解析目标地址和端口
                    HostAndPort hostAndPort = getHostAndPort(clientReqBuffer);
                    if (!("cn.bing.com".equalsIgnoreCase(hostAndPort.host))) {
                        return;
                    }
                    System.out.println("host: " + hostAndPort.host() + ", port: " + hostAndPort.port());
                    // 连接目标服务器
                    try (Socket targetSocket = new Socket(hostAndPort.host(), hostAndPort.port());
                         InputStream targetInput = targetSocket.getInputStream();
                         OutputStream targetOutput = targetSocket.getOutputStream()) {
                        // 响应客户端连接成功
                        clientOutput.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                        // 启动TLS代理
                    TlsServerProtocol tlsHandler = new TlsServerProtocol(targetInput, targetOutput);
//                    DefaultTlsServer tlsServer = new DefaultTlsServer();
//                    tlsHandler.connect(tlsServer);
                        // 在此可以对TLS连接进行拦截和修改

                        // 将客户端和目标服务器之间的流进行转发
                        // clientInput -> targetOutput
                        // clientOutput <-- targetInput
                        Thread clientToTarget = new Thread(() -> {
                            try {
                                byte[] buf = new byte[1024];
                                int len;
                                while ((len = clientInput.read(buf)) != -1) {
                                    targetOutput.write(buf, 0, len);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                        clientToTarget.start();

                        Thread targetToClient = new Thread(() -> {
                            try {
                                byte[] buf = new byte[1024];
                                int len;
//                                byte[] bytes = targetInput.readNBytes(targetInput.available());
//                                System.out.println("Response:" + new String(bytes));
//                                clientOutput.write(bytes, 0, bytes.length);
                                while ((len = targetInput.read(buf)) != -1) {
                                    System.out.println("Response slice:" + new String(buf));
                                    clientOutput.write(buf, 0, len);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
                        targetToClient.start();

                        clientToTarget.join();
                        targetToClient.join();
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static HostAndPort getHostAndPort(byte[] buffer) {
        String host = null;
        int port = 0;
        if (buffer[3] == 0x01) { // IPv4 地址
            host = String.format("%d.%d.%d.%d", buffer[4] & 0xFF, buffer[5] & 0xFF, buffer[6] & 0xFF, buffer[7] & 0xFF);
            port = ((buffer[8] & 0xFF) << 8) | (buffer[9] & 0xFF);
        } else if (buffer[3] == 0x03) { // 域名地址
            int hostLength = buffer[4];
            host = new String(Arrays.copyOfRange(buffer, 5, 5 + hostLength));
            port = ((buffer[5 + hostLength] & 0xFF) << 8) | (buffer[6 + hostLength] & 0xFF);
        }
        return new HostAndPort(host, port);
    }

    private record HostAndPort(String host, int port) {
    }
}
