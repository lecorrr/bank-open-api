package dev.tkachuk.openbanking.security;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * The provider's client certificate as Traefik passes it on. Traefik ends the mutual TLS and puts the certificate
 * into a header (its passTLSClientCert middleware with pem: true): the PEM body without its BEGIN and END lines
 * and without line breaks, a comma before each further certificate of the chain.
 */
final class ClientCertificates {

    static final String HEADER = "X-Forwarded-Tls-Client-Cert";

    private ClientCertificates() {
    }

    /** The provider's own certificate, the first in the header. */
    static X509Certificate parse(String headerValue) {
        String first = headerValue.split(",", 2)[0].strip();
        if (first.isEmpty()) {
            throw new IllegalArgumentException("The header holds no certificate");
        }
        try {
            byte[] der = Base64.getDecoder().decode(first);
            // An X.509 factory makes X.509 certificates only.
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (IllegalArgumentException | CertificateException e) {
            throw new IllegalArgumentException("The header holds no readable certificate", e);
        }
    }

    /** The certificate's SHA-256 thumbprint as RFC 8705 puts it into a token: base64url without padding. */
    static String thumbprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            // Every JDK has SHA-256, and a parsed certificate encodes again.
            throw new IllegalStateException("Cannot compute the certificate thumbprint", e);
        }
    }
}
