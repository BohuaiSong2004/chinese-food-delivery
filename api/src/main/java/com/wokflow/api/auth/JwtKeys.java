package com.wokflow.api.auth;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.wokflow.api.config.AppProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The ES256 signing key. Loaded from a PEM file holding a PKCS#8 "PRIVATE KEY"
 * block and its "PUBLIC KEY" block (see scripts/gen-jwt-key.sh). Every API
 * replica must use the same file; without one an ephemeral key is generated,
 * which is only acceptable for a single local process.
 */
@Component
public class JwtKeys {
    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String keyId;
    private final List<ECPublicKey> previous;

    public JwtKeys(AppProperties props) throws GeneralSecurityException, IOException {
        String file = props.jwt().privateKeyFile();
        String inline = props.jwt().privateKey();
        KeyPair pair;
        if (inline != null && !inline.isBlank()) {
            pair = parse(inline, "JWT_PRIVATE_KEY");
        } else if (file == null || file.isBlank()) {
            log.warn("JWT_PRIVATE_KEY_FILE is not set: using an ephemeral signing key (tokens die with this process)");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            pair = gen.generateKeyPair();
        } else if (!Files.exists(Path.of(file)) && props.jwt().generateIfMissing()) {
            pair = createShared(Path.of(file));
        } else {
            pair = read(Path.of(file));
        }
        this.publicKey = (ECPublicKey) pair.getPublic();
        this.privateKey = (ECPrivateKey) pair.getPrivate();
        this.keyId = kid(publicKey);
        String prevFile = props.jwt().previousPublicKeysFile();
        String prevText = props.jwt().previousPublicKeys();
        StringBuilder pems = new StringBuilder(prevText == null ? "" : prevText);
        if (prevFile != null && !prevFile.isBlank()) {
            pems.append('\n').append(Files.readString(Path.of(prevFile), StandardCharsets.US_ASCII));
        }
        this.previous = publicKeys(pems.toString()).stream().filter(k -> !k.equals(publicKey)).toList();
        if (!previous.isEmpty()) {
            log.info("Signing with key {}; also accepting {} retired key(s): {}", keyId, previous.size(),
                    previous.stream().map(JwtKeys::kid).toList());
        }
    }

    /** Key id: the first 16 base64url characters of the SHA-256 of the public key's DER encoding. */
    static String kid(ECPublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every "PUBLIC KEY" block in the text. */
    static List<ECPublicKey> publicKeys(String text) throws GeneralSecurityException {
        List<ECPublicKey> out = new ArrayList<>();
        Matcher m = PEM.matcher(text);
        KeyFactory kf = KeyFactory.getInstance("EC");
        while (m.find()) {
            if (m.group(1).equals("PUBLIC KEY")) {
                out.add((ECPublicKey) kf.generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(m.group(2).trim()))));
            }
        }
        return out;
    }

    /**
     * First start of a local stack: generate the key and publish it with
     * link(2), which fails if the file exists. Two replicas racing both
     * generate, exactly one link wins, and both then read the winner's file.
     * An EC key has no expiry, so nothing here goes stale.
     */
    static KeyPair createShared(Path path) throws IOException, GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair mine = gen.generateKeyPair();
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".jwt", ".tmp");
        try {
            Files.writeString(tmp, pem("PRIVATE KEY", mine.getPrivate().getEncoded()) + pem("PUBLIC KEY", mine.getPublic().getEncoded()));
            try {
                Files.createLink(path, tmp);
                log.info("Generated JWT signing key at {}", path);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                log.info("Another replica created {} first; using it", path);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return read(path);
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    static KeyPair read(Path path) throws IOException, GeneralSecurityException {
        return parse(Files.readString(path, StandardCharsets.US_ASCII), path.toString());
    }

    static KeyPair parse(String text, String source) throws GeneralSecurityException {
        byte[] priv = null;
        byte[] pub = null;
        Matcher m = PEM.matcher(text);
        while (m.find()) {
            byte[] der = Base64.getMimeDecoder().decode(m.group(2).trim());
            switch (m.group(1)) {
                case "PRIVATE KEY" -> priv = der;
                case "PUBLIC KEY" -> pub = der;
                default -> { }
            }
        }
        if (priv == null || pub == null) {
            throw new IllegalStateException(source + " must contain a PKCS#8 PRIVATE KEY and a PUBLIC KEY block");
        }
        KeyFactory kf = KeyFactory.getInstance("EC");
        return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(pub)), kf.generatePrivate(new PKCS8EncodedKeySpec(priv)));
    }

    public ECPublicKey publicKey() { return publicKey; }
    public String keyId() { return keyId; }

    public ECKey signingJwk() {
        return new ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey).keyID(keyId).build();
    }

    /** The signing key first, then retired keys; tokens name theirs in the "kid" header. */
    public JWKSet publicJwks() {
        List<com.nimbusds.jose.jwk.JWK> all = new ArrayList<>();
        all.add(new ECKey.Builder(Curve.P_256, publicKey).keyID(keyId).build());
        for (ECPublicKey k : previous) {
            all.add(new ECKey.Builder(Curve.P_256, k).keyID(kid(k)).build());
        }
        return new JWKSet(all);
    }
}
