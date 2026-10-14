package pl.chargeflow.session.config;

import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
class LocalDevAuthConfig {

    @Bean
    RSAKey localDevRsaKey() throws Exception {
        return new RSAKeyGenerator(2048)
                .keyID("local-dev-key")
                .keyUse(KeyUse.SIGNATURE)
                .generate();
    }
}
