import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.Base64;

public final class PackManifestSigner {
  public static void main(String[] args) throws Exception {
    if (args.length < 4) throw new IllegalArgumentException("info|sign <keystore> <type> <alias> [manifest] [signature]");
    char[] storePassword = requiredEnv("AG_KEYSTORE_PASSWORD").toCharArray();
    char[] keyPassword = System.getenv().getOrDefault("AG_KEY_PASSWORD", new String(storePassword)).toCharArray();
    KeyStore store = KeyStore.getInstance(args[2]);
    try (InputStream input = Files.newInputStream(Path.of(args[1]))) { store.load(input, storePassword); }
    Certificate certificate = store.getCertificate(args[3]);
    if (certificate == null) throw new IllegalArgumentException("Alias has no certificate");
    PublicKey publicKey = certificate.getPublicKey();
    String algorithm = switch (publicKey.getAlgorithm().toUpperCase()) {
      case "RSA" -> "SHA256withRSA";
      case "EC", "ECDSA" -> "SHA256withECDSA";
      default -> throw new IllegalArgumentException("Unsupported signing-key algorithm");
    };
    String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
    if (args[0].equals("info")) {
      System.out.println(algorithm + "\t" + fingerprint);
      return;
    }
    if (!args[0].equals("sign") || args.length != 6) throw new IllegalArgumentException("Invalid sign command");
    PrivateKey privateKey = (PrivateKey) store.getKey(args[3], keyPassword);
    Signature signer = Signature.getInstance(algorithm);
    signer.initSign(privateKey);
    signer.update(Files.readAllBytes(Path.of(args[4])));
    Files.writeString(Path.of(args[5]), Base64.getEncoder().encodeToString(signer.sign()), java.nio.charset.StandardCharsets.US_ASCII);
  }

  private static String requiredEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
