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

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.acemq.amqp.security.Security;

/**
 * What the studio was told about TLS.
 *
 * <p>Either paths on the machine the studio is running on, or the material pasted in. Paths are
 * what a container wants — mount the directory and give the studio three filenames — and pasting
 * is what somebody on a laptop with the files open in an editor wants. Both end in the same
 * place.
 *
 * @param enabled whether to use TLS at all
 * @param caPath a file holding the certificate authority, or null
 * @param caPem the authority pasted in, or null
 * @param clientCertificatePath a file holding the client certificate, for mutual TLS
 * @param clientCertificatePem the same, pasted in
 * @param clientKeyPath a file holding its private key
 * @param clientKeyPem the same, pasted in
 * @param trustAnyCertificate encrypt and verify nothing
 * @param allowDevelopmentCertificates accept a certificate stamped as development-only
 */
public record TlsSettings(
        boolean enabled,
        String caPath,
        String caPem,
        String clientCertificatePath,
        String clientCertificatePem,
        String clientKeyPath,
        String clientKeyPem,
        boolean trustAnyCertificate,
        boolean allowDevelopmentCertificates) {

    /** @return TLS off */
    public static TlsSettings none() {
        return new TlsSettings(false, null, null, null, null, null, null, false, false);
    }

    /** @return whether a client identity was given, which is what makes it mutual */
    @JsonIgnore
    public boolean isMutual() {
        return has(clientCertificatePath, clientCertificatePem);
    }

    /** @return whether an authority was given, rather than relying on the JVM's trust store */
    @JsonIgnore
    public boolean hasAuthority() {
        return has(caPath, caPem);
    }

    /** @return the authority, read from wherever it was given */
    @JsonIgnore
    public String authority() {
        return material(caPath, caPem);
    }

    /** @return the client certificate, read from wherever it was given */
    @JsonIgnore
    public String clientCertificate() {
        return material(clientCertificatePath, clientCertificatePem);
    }

    /** @return the client key, read from wherever it was given */
    @JsonIgnore
    public String clientKey() {
        return material(clientKeyPath, clientKeyPem);
    }

    /**
     * Turns this into the library's policy, writing the keystores it needs.
     *
     * @param workingDirectory where the generated keystores go
     * @return the policy, or null when TLS is off
     */
    public Security toSecurity(Path workingDirectory) {
        if (!enabled) {
            return null;
        }

        // Encrypt and prove nothing. Offered because it is the only way to get a first run
        // against a broker whose certificate nobody can find, and named so that nobody reaches
        // for it by accident.
        if (trustAnyCertificate) {
            return Security.insecure();
        }

        if (!hasAuthority() && !isMutual()) {
            // Nothing of our own to present or to trust: the JVM's trust store, which is right
            // for a broker with a certificate from a public authority.
            Security security = Security.required();
            return allowDevelopmentCertificates ? security.allowDevelopmentCertificates() : security;
        }

        TlsMaterial.write(workingDirectory, authority(), clientCertificate(), clientKey());
        Security security = Security.fromKeystore(workingDirectory)
                .keystorePassword(new String(TlsMaterial.storePassword()));
        return allowDevelopmentCertificates ? security.allowDevelopmentCertificates() : security;
    }

    private static boolean has(String path, String pem) {
        return (path != null && !path.isBlank()) || (pem != null && !pem.isBlank());
    }

    private static String material(String path, String pem) {
        if (pem != null && !pem.isBlank()) {
            return pem;
        }
        if (path == null || path.isBlank()) {
            return null;
        }
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            throw new TlsMaterial.TlsException(path + " does not exist."
                    + " Inside a container this is a path in the container, so the directory"
                    + " holding the certificates has to be mounted into it");
        }
        return TlsMaterial.read(file);
    }
}
