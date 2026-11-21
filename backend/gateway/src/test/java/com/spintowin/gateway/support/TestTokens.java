package com.spintowin.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 以測試金鑰簽發 Keycloak 形狀的 access token（RS256、kid、{@code realm_access.roles}）。 */
public final class TestTokens {

    /** 瀏覽器看到的 Keycloak 位址，也就是 token 的 {@code iss}。 */
    public static final String PUBLIC_ISSUER = "http://localhost:8080/realms/spintowin";

    private final RSAKey key;

    private TestTokens(RSAKey key) {
        this.key = key;
    }

    public static TestTokens generate() {
        try {
            return new TestTokens(new RSAKeyGenerator(2048).keyID("test-key").generate());
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public RSAKey publicKey() {
        return key.toPublicJWK();
    }

    public String accessToken(String issuer, String subject, String... realmRoles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
