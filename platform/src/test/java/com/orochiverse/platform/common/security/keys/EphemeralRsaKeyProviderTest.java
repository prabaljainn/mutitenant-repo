package com.orochiverse.platform.common.security.keys;

import static org.assertj.core.api.Assertions.assertThat;

import javax.crypto.Cipher;

import org.junit.jupiter.api.Test;

class EphemeralRsaKeyProviderTest {

    @Test
    void generates_a_2048_bit_rsa_keypair() {
        var provider = new EphemeralRsaKeyProvider();

        assertThat(provider.signingKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(provider.verificationKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(provider.signingKey().getModulus().bitLength()).isEqualTo(2048);
        assertThat(provider.verificationKey().getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    void key_id_is_unique_per_jvm_and_marked_ephemeral() {
        var a = new EphemeralRsaKeyProvider();
        var b = new EphemeralRsaKeyProvider();

        assertThat(a.activeKeyId()).startsWith("ephemeral-");
        assertThat(b.activeKeyId()).startsWith("ephemeral-");
        assertThat(a.activeKeyId()).isNotEqualTo(b.activeKeyId());
    }

    @Test
    void public_and_private_keys_are_a_matching_pair() throws Exception {
        var provider = new EphemeralRsaKeyProvider();

        // Encrypt with the public key, decrypt with the private key —
        // round-tripping data is the cheapest "this is the same keypair"
        // assertion that doesn't pull in the JWT layer.
        byte[] payload = "phase-1.5".getBytes();
        var enc = Cipher.getInstance("RSA");
        enc.init(Cipher.ENCRYPT_MODE, provider.verificationKey());
        byte[] cipher = enc.doFinal(payload);

        var dec = Cipher.getInstance("RSA");
        dec.init(Cipher.DECRYPT_MODE, provider.signingKey());
        byte[] back = dec.doFinal(cipher);

        assertThat(back).isEqualTo(payload);
    }
}
