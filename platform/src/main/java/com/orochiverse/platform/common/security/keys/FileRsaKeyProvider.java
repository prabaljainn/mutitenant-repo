package com.orochiverse.platform.common.security.keys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads an RSA keypair from PEM files on disk. The private key must be
 * PKCS#8-encoded ({@code -----BEGIN PRIVATE KEY-----}) and the public key
 * X.509 SubjectPublicKeyInfo ({@code -----BEGIN PUBLIC KEY-----}). Generate
 * with:
 *
 * <pre>{@code
 *   openssl genpkey -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:2048
 *   openssl rsa -in private.pem -pubout -out public.pem
 * }</pre>
 *
 * <p>Both files are read once at construction. Failures are fatal — we'd
 * rather refuse to boot than silently fall back to ephemeral keys and
 * mint tokens that nothing else trusts.
 */
public final class FileRsaKeyProvider implements JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(FileRsaKeyProvider.class);

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public FileRsaKeyProvider(Path privateKeyPath, Path publicKeyPath, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("key id must be set when using file-based keys");
        }
        this.keyId = keyId;
        this.privateKey = readPrivateKey(privateKeyPath);
        this.publicKey = readPublicKey(publicKeyPath);
        log.info("FileRsaKeyProvider active — loaded keypair (kid={}) from {} / {}",
                keyId, privateKeyPath, publicKeyPath);
    }

    @Override
    public RSAPrivateKey signingKey() {
        return privateKey;
    }

    @Override
    public RSAPublicKey verificationKey() {
        return publicKey;
    }

    @Override
    public String activeKeyId() {
        return keyId;
    }

    private static RSAPrivateKey readPrivateKey(Path path) {
        byte[] der = decodePem(readPem(path), "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to parse RSA private key from " + path, e);
        }
    }

    private static RSAPublicKey readPublicKey(Path path) {
        byte[] der = decodePem(readPem(path), "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to parse RSA public key from " + path, e);
        }
    }

    private static String readPem(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read PEM file: " + path, e);
        }
    }

    /**
     * Strips the BEGIN/END markers and base64-decodes the body. We accept any
     * marker that contains the expected label (e.g. {@code "PRIVATE KEY"}) so
     * that PKCS#8 ("BEGIN PRIVATE KEY") and other compatible variants both
     * work — but we reject the legacy {@code "RSA PRIVATE KEY"} (PKCS#1)
     * because {@code KeyFactory} can't parse it directly.
     */
    private static byte[] decodePem(String pem, String requiredLabel) {
        String trimmed = pem.replaceAll("\\r", "").trim();
        if (!trimmed.startsWith("-----BEGIN " + requiredLabel + "-----")) {
            throw new IllegalArgumentException(
                    "Expected PEM with label '" + requiredLabel + "'. "
                            + "If you have a legacy 'RSA PRIVATE KEY' (PKCS#1), convert with: "
                            + "openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem");
        }
        String body = trimmed
                .replace("-----BEGIN " + requiredLabel + "-----", "")
                .replace("-----END " + requiredLabel + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }
}
