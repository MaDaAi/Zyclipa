package io.zyclipra.remote;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * Self-signed TLS certificate for the dashboard, made on first use and kept in the data
 * folder. The keystore password is random and protected with the same {@link io.zyclipra.cloud.SecretStore}.
 * Browsers warn about self-signed certificates once; the fingerprint is shown in Settings
 * so it can be checked.
 */
final class TlsCertificates {
    private TlsCertificates() {}

    record Tls(SSLContext context, String fingerprint) { }

    static Tls load(Path keystore, io.zyclipra.cloud.SecretStore pass) throws Exception {
        String pw = pass.load();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (pw != null && Files.exists(keystore)) {
            try (InputStream in = Files.newInputStream(keystore)) { ks.load(in, pw.toCharArray()); }
            catch (Exception e) { ks = null; }
        } else ks = null;
        if (ks == null) {
            pw = io.zyclipra.cloud.Pkce.random(24);
            ks = create(pw);
            try (OutputStream out = Files.newOutputStream(keystore)) { ks.store(out, pw.toCharArray()); }
            pass.save(pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        X509Certificate cert = (X509Certificate) ks.getCertificate("zyclipra");
        String fp = HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        return new Tls(ctx, fp);
    }

    private static KeyStore create(String pw) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        KeyPair kp = g.generateKeyPair();
        X500Name name = new X500Name("CN=Zyclipra Dashboard");
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, new BigInteger(64, new SecureRandom()),
                new Date(now - 86_400_000L), new Date(now + 825L * 86_400_000L), name, kp.getPublic());
        List<GeneralName> sans = new ArrayList<>();
        sans.add(new GeneralName(GeneralName.dNSName, "localhost"));
        sans.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof java.net.Inet4Address) sans.add(new GeneralName(GeneralName.iPAddress, a.getHostAddress()));
            }
        }
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans.toArray(GeneralName[]::new)));
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate())));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("zyclipra", kp.getPrivate(), pw.toCharArray(), new java.security.cert.Certificate[]{cert});
        return ks;
    }
}
