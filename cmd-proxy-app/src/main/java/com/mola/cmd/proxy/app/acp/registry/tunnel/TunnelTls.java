package com.mola.cmd.proxy.app.acp.registry.tunnel;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** 证书只在内存中生成；注册返回公钥证书，客户端精确信任该证书。 */
final class TunnelTls {
    final SslContext context;
    final String certificate;
    TunnelTls() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            X500Name name = new X500Name("CN=Starweave reverse tunnel");
            long now = System.currentTimeMillis();
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(name, new BigInteger(128, new SecureRandom()),
                            new Date(now - 86400000L), new Date(now + 10L * 365 * 86400000L), name, pair.getPublic())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
            context = SslContextBuilder.forServer(pair.getPrivate(), cert).sslProvider(SslProvider.JDK)
                    .protocols("TLSv1.2").build();
            certificate = Base64.getEncoder().encodeToString(cert.getEncoded());
        } catch (Exception e) { throw new IOException("无法初始化隧道加密", e); }
    }
    static SslContext client(String certificate) throws IOException {
        try {
            if (certificate == null || certificate.length() > 8192) throw new CertificateException();
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certificate)));
            return SslContextBuilder.forClient().sslProvider(SslProvider.JDK).trustManager(cert)
                    .protocols("TLSv1.2").build();
        } catch (Exception e) { throw new IOException("中心隧道证书无效，请更新两端后重新注册", e); }
    }
}
