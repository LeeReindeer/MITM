package leer.moe.mitm.cert;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CertUtil {

    /**
     * port -> host -> cert
     */
    private static Map<Integer, Map<String, X509Certificate>> certCache = new WeakHashMap<>();

    private static KeyFactory keyFactory = null;

    private static KeyFactory getKeyFactory() {
        if (keyFactory == null) {
            try {
                keyFactory = KeyFactory.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                // Unexpected anomalies
                throw new IllegalStateException(e);
            }
        }
        return keyFactory;
    }

    public static X509Certificate getCert(Integer port, String host, HttpProxyCertConfig serverConfig)
            throws Exception {
        X509Certificate cert = null;
        if (host != null) {
            Map<String, X509Certificate> portCertCache = certCache.get(port);
            if (portCertCache == null) {
                portCertCache = new HashMap<>();
                certCache.put(port, portCertCache);
            }
            String key = host.trim().toLowerCase();
            if (portCertCache.containsKey(key)) {
                return portCertCache.get(key);
            } else {
                cert = CertUtil.genCert(serverConfig.serverIssuer(), serverConfig.caPriKey(),
                        serverConfig.caNotBefore(), serverConfig.caNotAfter(),
                        serverConfig.serverPubKey(), key);
                portCertCache.put(key, cert);
            }
        }
        return cert;
    }

    public static void clear() {
        certCache.clear();
    }

    private static final BouncyCastleCertGenerator bouncyCastleCertGenerator = new BouncyCastleCertGenerator();

    /**
     * 动态生成服务器证书,并进行CA签授
     *
     * @param issuer 颁发机构
     */
    public static X509Certificate genCert(String issuer, PrivateKey caPriKey, Date caNotBefore,
                                          Date caNotAfter, PublicKey serverPubKey,
                                          String... hosts) throws Exception {
        return bouncyCastleCertGenerator.generateServerCert(issuer, caPriKey, caNotBefore, caNotAfter, serverPubKey, hosts);
    }

    /**
     * 生成CA服务器证书
     */
    public static X509Certificate genCACert(String subject, Date caNotBefore, Date caNotAfter,
                                            KeyPair keyPair) throws Exception {
        return bouncyCastleCertGenerator.generateCaCert(subject, caNotBefore, caNotAfter, keyPair);
    }

    /**
     * 生成RSA公私密钥对,长度为2048
     */
    public static KeyPair genKeyPair() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
        keyPairGen.initialize(2048, new SecureRandom());
        return keyPairGen.genKeyPair();
    }

    /**
     * 从文件加载RSA私钥
     * openssl pkcs8 -topk8 -nocrypt -inform PEM -outform DER -in ca.key -out ca_private.der
     */
    public static PrivateKey loadPriKey(byte[] bts)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(bts);
        return getKeyFactory().generatePrivate(privateKeySpec);
    }

    /**
     * 从文件加载RSA私钥
     * openssl pkcs8 -topk8 -nocrypt -inform PEM -outform DER -in ca.key -out ca_private.der
     */
    public static PrivateKey loadPriKey(InputStream inputStream)
            throws IOException, InvalidKeySpecException, NoSuchAlgorithmException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] bts = new byte[1024];
        int len;
        while ((len = inputStream.read(bts)) != -1) {
            outputStream.write(bts, 0, len);
        }
        inputStream.close();
        outputStream.close();
        return loadPriKey(outputStream.toByteArray());
    }

    /**
     * 从文件加载证书
     */
    public static X509Certificate loadCert(InputStream inputStream) throws CertificateException, IOException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(inputStream);
        } finally {
            inputStream.close();
        }
    }

    /**
     * 读取ssl证书使用者信息
     */
    public static String getSubject(X509Certificate certificate) throws Exception {
        //读出来顺序是反的需要反转下
        List<String> tempList = Arrays.asList(certificate.getIssuerDN().toString().split(", "));
        return IntStream.rangeClosed(0, tempList.size() - 1)
                .mapToObj(i -> tempList.get(tempList.size() - i - 1)).collect(Collectors.joining(", "));
    }


    public static void main(String[] args) throws Exception {
        //生成ca证书和私钥
        KeyPair keyPair = CertUtil.genKeyPair();
        File caCertFile = new File("./ca.crt");
        if (caCertFile.exists()) {
            caCertFile.delete();
        }

        Files.write(Paths.get(caCertFile.toURI()),
                CertUtil.genCACert(
                                "C=CN, ST=Shanghai, L=Shanghai, O=leereindeer, OU=mitm, CN=ca",
                                new Date(),
                                new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650)),
                                keyPair)
                        .getEncoded());

        File caPriKeyFile = new File("./ca_private.der");
        if (caPriKeyFile.exists()) {
            caPriKeyFile.delete();
        }

        Files.write(caPriKeyFile.toPath(),
                new PKCS8EncodedKeySpec(keyPair.getPrivate().getEncoded()).getEncoded());
    }

}
