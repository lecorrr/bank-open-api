package dev.tkachuk.provider.bank;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/** A key pair as the provider's: RSA, 2048 bits, as scripts/cluster-up.sh makes it. */
final class TestKeys {

    private TestKeys() {
    }

    static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
