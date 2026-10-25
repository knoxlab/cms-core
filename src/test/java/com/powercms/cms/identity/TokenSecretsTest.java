package com.powercms.cms.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenSecretsTest {

    @Test
    void issuedTokenParsesAndMatchesHash() {
        TokenSecrets.Issued issued = TokenSecrets.issue();
        assertThat(TokenSecrets.publicIdOf(issued.plaintext())).contains(issued.publicId());
        assertThat(TokenSecrets.matches(issued.plaintext(), issued.sha256Hex())).isTrue();
        assertThat(TokenSecrets.matches(issued.plaintext() + "x", issued.sha256Hex())).isFalse();
        assertThat(TokenSecrets.publicIdOf("not-a-token")).isEmpty();
    }
}
