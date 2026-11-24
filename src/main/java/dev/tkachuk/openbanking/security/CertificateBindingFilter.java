package dev.tkachuk.openbanking.security;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a provider's token through only with the certificate it is bound to (RFC 8705): the request must come with
 * that client certificate, still valid, and its SHA-256 thumbprint must be the token's cnf.x5t#S256. A stolen token
 * is of no use without the provider's private key. Runs right after Spring Security has checked the token.
 */
final class CertificateBindingFilter extends OncePerRequestFilter {

    /** Where the servlet specification keeps a request's client certificates. */
    static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final SecurityRefusals refusals;
    private final Clock clock;

    CertificateBindingFilter(SecurityRefusals refusals, Clock clock) {
        this.refusals = Objects.requireNonNull(refusals, "refusals");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // No provider token: the authorization rules answer that.
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(ClientCertificates.HEADER);
        if (header == null || header.isBlank()) {
            refuse(request, response, ErrorCode.CERTIFICATE_MISSING, ErrorCode.CERTIFICATE_MISSING.text());
            return;
        }
        X509Certificate certificate;
        try {
            certificate = ClientCertificates.parse(header);
            certificate.checkValidity(Date.from(clock.instant()));
        } catch (IllegalArgumentException unreadable) {
            refuse(request, response, ErrorCode.CERTIFICATE_INVALID, "The client certificate cannot be read");
            return;
        } catch (CertificateExpiredException expired) {
            refuse(request, response, ErrorCode.CERTIFICATE_EXPIRED, ErrorCode.CERTIFICATE_EXPIRED.text());
            return;
        } catch (CertificateNotYetValidException notYetValid) {
            refuse(request, response, ErrorCode.CERTIFICATE_INVALID, "The client certificate is not valid yet");
            return;
        }
        String boundTo = ProviderTokenValidator.certificateThumbprint(token.getToken());
        if (!ClientCertificates.thumbprint(certificate).equals(boundTo)) {
            refuse(request, response, ErrorCode.TOKEN_INVALID, "The access token is bound to another certificate");
            return;
        }
        request.setAttribute(CERTIFICATE_ATTRIBUTE, new X509Certificate[] {certificate});
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail) {
        // The token was fine, so the security context goes; nothing of this request may count as authenticated.
        SecurityContextHolder.clearContext();
        refusals.refuse(request, response, new ApiException(code, detail), null);
    }
}
