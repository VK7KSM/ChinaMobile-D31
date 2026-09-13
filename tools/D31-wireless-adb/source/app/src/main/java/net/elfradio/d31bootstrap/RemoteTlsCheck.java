package net.elfradio.d31bootstrap;

public final class RemoteTlsCheck {
    public static void main(String[] args) throws Exception {
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) RemoteTls.factory().createSocket()) {
            socket.connect(new java.net.InetSocketAddress("mqtt.elfradio.net", 8883), 15000);
            socket.setSoTimeout(15000); socket.startHandshake();
            javax.net.ssl.HostnameVerifier verifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
            boolean valid = verifier.verify("mqtt.elfradio.net", socket.getSession());
            boolean invalid = verifier.verify("invalid.example", socket.getSession());
            if (!valid || invalid) throw new java.io.IOException("域名校验未通过");
            System.out.println("TLS_CHAIN_OK HOST_MATCH_OK WRONG_HOST_REJECTED protocol=" + socket.getSession().getProtocol());
        }
    }
}
