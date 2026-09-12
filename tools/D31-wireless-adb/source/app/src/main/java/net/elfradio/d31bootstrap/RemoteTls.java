package net.elfradio.d31bootstrap;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.*;

public final class RemoteTls {
    private static SSLSocketFactory cached;
    public static synchronized SSLSocketFactory factory() throws Exception {
        if (cached != null) return cached;
        X509Certificate root;
        try (InputStream in = RemoteTls.class.getResourceAsStream("/isrgrootx1.pem")) {
            if (in == null) throw new CertificateException("公开根证书资源缺失");
            root = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(root.getEncoded()))
            hash.append(String.format(java.util.Locale.US, "%02x", b & 255));
        if (!hash.toString().equals("96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"))
            throw new CertificateException("公开根证书摘要不符");
        KeyStore additions = KeyStore.getInstance(KeyStore.getDefaultType()); additions.load(null);
        additions.setCertificateEntry("isrg-root-x1", root);
        X509TrustManager system = manager(null), extra = manager(additions);
        // 补充公开根，不跳过证书链验证；域名仍由Paho执行HTTPS规则校验。
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] left = system.getAcceptedIssuers(), right = extra.getAcceptedIssuers();
                X509Certificate[] all = Arrays.copyOf(left, left.length + right.length);
                System.arraycopy(right, 0, all, left.length, right.length); return all;
            }
            public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                system.checkClientTrusted(chain, type);
            }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try { system.checkServerTrusted(chain, type); }
                catch (CertificateException unavailable) { extra.checkServerTrusted(chain, type); }
            }
        };
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(null, new TrustManager[]{trust}, null);
        cached = context.getSocketFactory(); return cached;
    }

    private static X509TrustManager manager(KeyStore store) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager value : factory.getTrustManagers()) if (value instanceof X509TrustManager) return (X509TrustManager) value;
        throw new CertificateException("系统证书验证器不可用");
    }
}
