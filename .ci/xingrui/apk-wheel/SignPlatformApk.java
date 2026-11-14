import com.android.apksig.ApkSigner;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.*;
import java.util.*;

/** Local signing step. Only the exact certificate of the supplied APK is accepted. */
public class SignPlatformApk {
    public static void main(String[] args) throws Exception {
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(Path.of(args[1]))) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        if (!fingerprint.equals("c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"))
            throw new IllegalArgumentException("Does not match reference APK certificate");
        byte[] encoded = Files.readAllBytes(Path.of(args[0]));
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        Arrays.fill(encoded, (byte) 0);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("aosp-platform-development", key, List.of(certificate)).build();
        new ApkSigner.Builder(List.of(signer)).setInputApk(new File(args[2])).setOutputApk(new File(args[3]))
            .setAlignmentPreserved(true).setLibraryPageAlignmentBytes(4096)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign();
        System.out.println("Signed; certificate SHA256=" + fingerprint);
    }
}
