package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.iam.IamSigningKey;
import cn.byteforce.seedj.pki.PkiProvider;
import cn.byteforce.seedj.pki.PkiProvider.IssuedCertificate;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class PkiIamSigningKeyStoreTest {

    @Test
    void wrapsPkiSigningIdentityAsSingleActiveRsaKey() throws Exception {
        String serial = "987654321";
        IssuedCertificate issued = issuedCertificate(serial);
        PkiProvider pki = Mockito.mock(PkiProvider.class);
        when(pki.signingIdentity("iam-jwt-signer")).thenReturn(issued);

        PkiIamSigningKeyStore store = new PkiIamSigningKeyStore(pki, "iam-jwt-signer");

        IamSigningKey active = store.activeKey();
        assertThat(active.kid()).isEqualTo(serial);
        assertThat(active.algorithm()).isEqualTo("RS256");
        assertThat(active.active()).isTrue();
        assertThat(active.canSign()).isTrue();
        assertThat(active.retiredAt()).isNull();
        assertThat(active.privateJwk()).contains("\"kty\":\"RSA\"").contains("\"d\":");
        assertThat(active.publicJwk()).contains("\"kty\":\"RSA\"").contains("\"n\":")
                .doesNotContain("\"d\":");
        assertThat(store.keys()).containsExactly(active);
    }

    @Test
    void rotationIsOwnedByThePkiProvider() throws Exception {
        PkiProvider pki = Mockito.mock(PkiProvider.class);
        when(pki.signingIdentity("iam-jwt-signer")).thenReturn(issuedCertificate("1"));
        PkiIamSigningKeyStore store = new PkiIamSigningKeyStore(pki, "iam-jwt-signer");

        assertThatThrownBy(store::rotate)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PKI signing keys rotate through PkiProvider");
    }

    private static IssuedCertificate issuedCertificate(String serial) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        Instant notBefore = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant notAfter = notBefore.plus(365, ChronoUnit.DAYS);
        X500Name subject = new X500Name("CN=iam-jwt-signer");
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(subject, new BigInteger(serial),
                        Date.from(notBefore), Date.from(notAfter), subject, keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA")
                                .build(keyPair.getPrivate())));
        return new IssuedCertificate(certificate, List.of(certificate), keyPair.getPrivate(),
                serial, notBefore, notAfter);
    }
}
