/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.workloads.studio.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;

import org.acemq.amqp.security.dev.DevelopmentCertificates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the PEM files people actually have.
 *
 * <p>The certificates here are generated rather than checked in, because a test fixture with a
 * private key in it is a private key in a public repository, and because a certificate that
 * expires is a test that fails one morning for no reason anybody changed.
 */
@DisplayName("TLS material")
class TlsMaterialTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("writes both stores from a CA and a client identity")
    void writesBothStores() throws Exception {
        Fixture fixture = Fixture.create();

        TlsMaterial.write(directory, fixture.certificatePem, fixture.certificatePem,
                fixture.keyPem);

        assertThat(directory.resolve("truststore.p12")).exists();
        assertThat(directory.resolve("keystore.p12")).exists();

        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(directory.resolve("keystore.p12"))) {
            keystore.load(in, TlsMaterial.storePassword());
        }
        assertThat(keystore.isKeyEntry("client")).isTrue();
    }

    // Security.fromKeystore loads both files whenever a directory is given, so one-way TLS -- a
    // CA and no client identity -- fails with "keystore.p12 does not exist" unless an empty one
    // is written. An error about a file that should not need to exist.
    @Test
    @DisplayName("writes an empty keystore when there is no client identity")
    void writesAnEmptyKeystoreForOneWayTls() throws Exception {
        Fixture fixture = Fixture.create();

        TlsMaterial.write(directory, fixture.certificatePem, null, null);

        assertThat(directory.resolve("keystore.p12")).exists();
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(directory.resolve("keystore.p12"))) {
            keystore.load(in, TlsMaterial.storePassword());
        }
        assertThat(keystore.size()).isZero();
    }

    @Test
    @DisplayName("refuses a client certificate with no key, and says why")
    void refusesACertificateWithoutItsKey() throws Exception {
        Fixture fixture = Fixture.create();

        assertThatThrownBy(() ->
                TlsMaterial.write(directory, fixture.certificatePem, fixture.certificatePem, null))
                .isInstanceOf(TlsMaterial.TlsException.class)
                .hasMessageContaining("cannot prove anything");
    }

    @Test
    @DisplayName("refuses an encrypted key with the command that decrypts it")
    void refusesAnEncryptedKey() {
        String encrypted = "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(new byte[64])
                + "\n-----END ENCRYPTED PRIVATE KEY-----\n";

        assertThatThrownBy(() -> TlsMaterial.privateKey(encrypted))
                .isInstanceOf(TlsMaterial.TlsException.class)
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt");
    }

    @Test
    @DisplayName("says what a certificate is, including that it is development-only")
    void describesACertificate() throws Exception {
        Fixture fixture = Fixture.create();

        var described = TlsMaterial.describe(fixture.certificatePem);

        assertThat(described).hasSize(1);
        assertThat(described.get(0).subject()).contains("AceMQ development CA");
        assertThat(described.get(0).expired()).isFalse();
        assertThat(described.get(0).notAfter()).isNotBlank();
        // The property that matters: the generator stamps its certificates, and both the
        // library and the studio read that stamp. A studio that showed one as ordinary would
        // be hiding the single most important thing about it.
        assertThat(described.get(0).development()).isTrue();
    }

    @Test
    @DisplayName("is handed a certificate and the key that belongs to it")
    void fixtureIsAMatchingPair() throws Exception {
        Fixture fixture = Fixture.create();

        var certificate = TlsMaterial.certificates(fixture.certificatePem).get(0);
        var key = TlsMaterial.privateKey(fixture.keyPem);
        var signature = java.security.Signature.getInstance(certificate.getSigAlgName());
        signature.initSign(key);
        signature.update(new byte[] {1, 2, 3});
        byte[] signed = signature.sign();
        signature.initVerify(certificate.getPublicKey());
        signature.update(new byte[] {1, 2, 3});
        assertThat(signature.verify(signed)).isTrue();
    }

    @Test
    @DisplayName("explains a file that is not a certificate at all")
    void refusesSomethingThatIsNotACertificate() {
        assertThatThrownBy(() -> TlsMaterial.write(directory, "hello", null, null))
                .isInstanceOf(TlsMaterial.TlsException.class)
                .hasMessageContaining("contains no certificate");
    }

    @Test
    @DisplayName("turns settings into the policy they describe")
    void buildsThePolicy() throws Exception {
        Fixture fixture = Fixture.create();

        assertThat(TlsSettings.none().toSecurity(directory)).isNull();

        var trustAnything = new TlsSettings(true, null, null, null, null, null, null, true, false);
        assertThat(trustAnything.toSecurity(directory)).isNotNull();

        Path caFile = directory.resolve("ca.pem");
        Files.writeString(caFile, fixture.certificatePem);
        var withAuthority = new TlsSettings(true, caFile.toString(), null, null, null, null, null,
                false, true);
        assertThat(withAuthority.toSecurity(directory)).isNotNull();
        assertThat(directory.resolve("truststore.p12")).exists();
    }

    @Test
    @DisplayName("says which file is missing, and that a container has its own paths")
    void namesAMissingFile() {
        var settings = new TlsSettings(true, "/no/such/ca.pem", null, null, null, null, null,
                false, false);

        assertThatThrownBy(() -> settings.toSecurity(directory))
                .isInstanceOf(TlsMaterial.TlsException.class)
                .hasMessageContaining("/no/such/ca.pem")
                .hasMessageContaining("mounted into it");
    }

    /**
     * A CA, a broker certificate and a client certificate, from the library's own generator.
     *
     * <p>The same tool the documentation tells people to use, so these tests read the material
     * the studio will really be handed -- and nothing is checked in, so no private key is in the
     * repository and no fixture expires.
     */
    private record Fixture(String certificatePem, String keyPem) {

        static Fixture create() throws Exception {
            Path generated = Files.createTempDirectory("acemq-test-certs");
            DevelopmentCertificates.Result result = new DevelopmentCertificates()
                    .generate(generated, "localhost", "acemq-dev".toCharArray(),
                            java.time.Duration.ofDays(1));

            // The generator writes PEMs beside the keystores, which is what a broker is given
            // and therefore what somebody will point the studio at. They are read by the names
            // the generator gives them: picking "the first file that looks like a key" from a
            // directory listing depends on the file system's order, and on ext4 that order
            // changes from one machine to the next, so it sometimes picked keystore.p12 -- a
            // binary file -- and failed with MalformedInputException.
            return new Fixture(Files.readString(result.directory().resolve("ca.crt")),
                    Files.readString(result.directory().resolve("ca.key")));
        }
    }
}
