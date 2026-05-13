package com.orochiverse.platform.common.security.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRsaKeyProviderTest {

    private static Path privatePem;
    private static Path publicPem;

    @BeforeAll
    static void writeKeypairToDisk(@TempDir Path dir) throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        privatePem = dir.resolve("private.pem");
        publicPem = dir.resolve("public.pem");
        Files.writeString(privatePem, pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Files.writeString(publicPem, pem("PUBLIC KEY", kp.getPublic().getEncoded()));
    }

    @Test
    void loads_a_pem_keypair_from_disk() {
        var provider = new FileRsaKeyProvider(privatePem, publicPem, "test-kid-001");

        assertThat(provider.activeKeyId()).isEqualTo("test-kid-001");
        assertThat(provider.signingKey().getModulus()).isEqualTo(provider.verificationKey().getModulus());
    }

    @Test
    void rejects_blank_key_id() {
        assertThatThrownBy(() -> new FileRsaKeyProvider(privatePem, publicPem, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key id");
    }

    @Test
    void rejects_a_pem_with_the_wrong_label(@TempDir Path dir) throws IOException {
        Path wrong = dir.resolve("wrong.pem");
        Files.writeString(wrong, pem("CERTIFICATE", new byte[]{1, 2, 3}));

        assertThatThrownBy(() -> new FileRsaKeyProvider(wrong, publicPem, "kid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRIVATE KEY");
    }

    @Test
    void surfaces_a_clear_error_for_legacy_pkcs1_keys(@TempDir Path dir) throws IOException {
        // Mimic what `openssl genrsa` (PKCS#1) produces — the BEGIN line
        // says "RSA PRIVATE KEY", which our reader rejects with hint text.
        Path legacy = dir.resolve("legacy.pem");
        Files.writeString(legacy, pem("RSA PRIVATE KEY", new byte[]{1, 2, 3}));

        assertThatThrownBy(() -> new FileRsaKeyProvider(legacy, publicPem, "kid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openssl pkcs8");
    }

    @Test
    void missing_file_fails_loud() {
        Path nope = Path.of("/does/not/exist.pem");

        assertThatThrownBy(() -> new FileRsaKeyProvider(nope, publicPem, "kid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read PEM");
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
