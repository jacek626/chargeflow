package pl.chargeflow.session.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@Profile("local")
@RequestMapping("/dev")
class LocalDevController {

    private final RSAKey rsaKey;

    LocalDevController(RSAKey localDevRsaKey) {
        this.rsaKey = localDevRsaKey;
    }

    @GetMapping("/jwks.json")
    Map<String, Object> jwks() throws Exception {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

    @PostMapping("/token")
    Map<String, String> token(
            @RequestParam(defaultValue = "CUSTOMER") String role,
            @RequestParam(defaultValue = "local@chargeflow.local") String email
    ) throws Exception {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .subject("local-user-" + role.toLowerCase())
                .claim("email", email)
                .claim("cognito:groups", List.of(role))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(86400)))
                .build();

        var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID())
                .build();

        var jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(rsaKey));
        String token = jwt.serialize();

        return Map.of(
                "access_token", token,
                "id_token", token,
                "expires_in", "86400"
        );
    }
}
