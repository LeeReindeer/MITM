package leer.moe.mitm;

public class HttpsServer {

    public static void main(String[] args) throws Exception {
        HttpServer httpsServer = new HttpServer(8090, true);
        httpsServer.run();
    }
}
