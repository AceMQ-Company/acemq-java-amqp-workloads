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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the PEM files people actually have into the keystores the library wants.
 *
 * <p>{@code Security.fromKeystore(directory)} reads {@code keystore.p12} and
 * {@code truststore.p12}. Nobody has those. What a broker hands out is
 * {@code ca.pem}, {@code client.crt} and {@code client.key} — that is what tls-gen produces,
 * what RabbitMQ's own documentation tells people to make, and what is sitting in the directory
 * they were given by whoever set the broker up. Asking somebody to run three {@code keytool}
 * incantations before they can point a load generator at their own broker is how a tool goes
 * unused.
 *
 * <p>So the studio reads the PEMs and writes the two stores itself, into a directory it owns.
 *
 * <p><strong>The private key never goes back to the browser.</strong> It is written to a file
 * only this user can read, and the API returns what the certificate says about itself and
 * nothing else.
 */
public final class TlsMaterial {

    /**
     * The password on the generated stores.
     *
     * <p>Not a secret and not pretending to be one: PKCS#12 requires a password, the file is
     * already readable only by this user, and a password stored beside the thing it protects
     * protects nothing. It exists because the format demands one.
     */
    private static final char[] STORE_PASSWORD = "acemq-studio".toCharArray();

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    private TlsMaterial() {
    }

    /**
     * What a certificate says about itself, which is all the browser is told.
     *
     * @param subject who it is for
     * @param issuer who signed it
     * @param notBefore when it starts being valid, as ISO-8601
     * @param notAfter when it stops
     * @param expired whether that has already happened
     * @param development whether it carries the marker the AceMQ generator stamps on a
     *        certificate that must never be trusted in production
     */
    public record Description(String subject, String issuer, String notBefore, String notAfter,
            boolean expired, boolean development) {

        static Description of(X509Certificate certificate) {
            String subject = certificate.getSubjectX500Principal().getName();
            return new Description(
                    subject,
                    certificate.getIssuerX500Principal().getName(),
                    certificate.getNotBefore().toInstant().toString(),
                    certificate.getNotAfter().toInstant().toString(),
                    certificate.getNotAfter().toInstant().isBefore(java.time.Instant.now()),
                    subject.contains("DEVELOPMENT") || subject.contains("DO NOT TRUST")
                            || certificate.getIssuerX500Principal().getName().contains("DEVELOPMENT"));
        }
    }

    /**
     * Writes a keystore directory from PEM material.
     *
     * @param directory where to write, created if it is not there
     * @param caPem one or more CA certificates, or null to trust the JVM's own store
     * @param clientCertificatePem the client certificate chain, or null for one-way TLS
     * @param clientKeyPem the private key for it, or null
     * @throws TlsException if any of it cannot be read
     */
    public static void write(Path directory, String caPem, String clientCertificatePem,
            String clientKeyPem) {
        try {
            Files.createDirectories(directory);
            restrictToOwner(directory);

            // The trust store: who this client will accept. An empty one would trust nothing,
            // so it is written only when a CA was given -- without it the library falls back to
            // the JVM's trust store, which is the right answer for a broker with a real
            // certificate.
            if (caPem != null && !caPem.isBlank()) {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, STORE_PASSWORD);
                List<X509Certificate> authorities = certificates(caPem);
                if (authorities.isEmpty()) {
                    throw new TlsException("that CA file contains no certificate. A CA file looks"
                            + " like -----BEGIN CERTIFICATE----- and is usually called ca.pem or"
                            + " ca.crt");
                }
                for (int i = 0; i < authorities.size(); i++) {
                    trust.setCertificateEntry("ca-" + i, authorities.get(i));
                }
                store(trust, directory.resolve("truststore.p12"));
            } else {
                Files.deleteIfExists(directory.resolve("truststore.p12"));
            }

            // The key store: who this client says it is.
            //
            // Written even when there is no client identity, empty. Security.fromKeystore loads
            // both files whenever a directory is given, so a missing keystore.p12 fails one-way
            // TLS with "keystore.p12 does not exist" -- an error about a file that should not
            // need to exist, for a connection that presents no certificate. An empty store
            // produces a key manager with nothing in it, which is exactly one-way TLS.
            if (clientCertificatePem != null && !clientCertificatePem.isBlank()) {
                if (clientKeyPem == null || clientKeyPem.isBlank()) {
                    throw new TlsException("a client certificate without its private key cannot"
                            + " prove anything. Both are needed for mutual TLS");
                }
                List<X509Certificate> chain = certificates(clientCertificatePem);
                if (chain.isEmpty()) {
                    throw new TlsException("that client certificate file contains no certificate");
                }
                PrivateKey key = privateKey(clientKeyPem);

                KeyStore identity = KeyStore.getInstance("PKCS12");
                identity.load(null, STORE_PASSWORD);
                identity.setKeyEntry("client", key, STORE_PASSWORD,
                        chain.toArray(new Certificate[0]));
                store(identity, directory.resolve("keystore.p12"));
            } else {
                KeyStore empty = KeyStore.getInstance("PKCS12");
                empty.load(null, STORE_PASSWORD);
                store(empty, directory.resolve("keystore.p12"));
            }
        } catch (TlsException e) {
            throw e;
        } catch (Exception e) {
            throw new TlsException("the TLS material could not be read: " + e.getMessage(), e);
        }
    }

    /** @return the password the written stores carry */
    public static char[] storePassword() {
        return STORE_PASSWORD.clone();
    }

    /**
     * @param pem certificate material
     * @return what each certificate in it says about itself
     */
    public static List<Description> describe(String pem) {
        List<Description> described = new ArrayList<>();
        for (X509Certificate certificate : certificates(pem)) {
            described.add(Description.of(certificate));
        }
        return described;
    }

    /**
     * @param pem one or more PEM certificates
     * @return them, parsed
     */
    public static List<X509Certificate> certificates(String pem) {
        List<X509Certificate> parsed = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Matcher matcher = PEM_BLOCK.matcher(pem);
            while (matcher.find()) {
                if (!matcher.group(1).contains("CERTIFICATE")) {
                    continue;
                }
                byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
                parsed.add((X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(der)));
            }
        } catch (Exception e) {
            throw new TlsException("that does not look like a PEM certificate: " + e.getMessage(), e);
        }
        return parsed;
    }

    /**
     * Reads a private key.
     *
     * <p>PKCS#8 ({@code BEGIN PRIVATE KEY}) is what the JDK reads without help. PKCS#1
     * ({@code BEGIN RSA PRIVATE KEY}) is what openssl wrote for years and what half the
     * directories in the world contain, so it is wrapped into PKCS#8 here rather than met with
     * "invalid key format" — an error that tells somebody nothing about what to do next.
     *
     * <p>Encrypted keys are refused with the command that decrypts one. Reading them would mean
     * asking for a passphrase, holding it, and being the thing that leaked it.
     */
    static PrivateKey privateKey(String pem) {
        Matcher matcher = PEM_BLOCK.matcher(pem);
        while (matcher.find()) {
            String type = matcher.group(1);
            byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
            try {
                if (type.equals("PRIVATE KEY")) {
                    return fromPkcs8(der);
                }
                if (type.equals("RSA PRIVATE KEY")) {
                    return fromPkcs8(wrapPkcs1InPkcs8(der));
                }
                if (type.equals("ENCRYPTED PRIVATE KEY")) {
                    throw new TlsException("that private key is encrypted. Decrypt it first:"
                            + " openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-decrypted.pem");
                }
            } catch (TlsException e) {
                throw e;
            } catch (Exception e) {
                throw new TlsException("that private key could not be read: " + e.getMessage(), e);
            }
        }
        throw new TlsException("no private key found. A key file looks like"
                + " -----BEGIN PRIVATE KEY----- or -----BEGIN RSA PRIVATE KEY-----");
    }

    private static PrivateKey fromPkcs8(byte[] der) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : new String[] {"RSA", "EC", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception next) {
                // The key does not say which algorithm it is in a way that is cheap to read
                // here, so each is tried. The last failure is reported.
            }
        }
        throw new TlsException("that private key is not RSA, EC or Ed25519, or it is malformed");
    }

    /**
     * Wraps a PKCS#1 RSA key in the PKCS#8 envelope the JDK expects.
     *
     * <p>The envelope is a fixed prefix followed by the key: a SEQUENCE holding version 0, the
     * rsaEncryption algorithm identifier, and the original key as an OCTET STRING. Written by
     * hand because pulling in a crypto library to prepend twenty-six bytes would be a poor
     * trade in a tool whose whole dependency list fits on a screen.
     */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] header = new byte[] {
            0x30, (byte) 0x82, 0, 0,                                  // SEQUENCE, length filled in
            0x02, 0x01, 0x00,                                          // INTEGER 0 (version)
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48,           // rsaEncryption OID
            (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
            0x04, (byte) 0x82, 0, 0,                                   // OCTET STRING, length
        };
        byte[] wrapped = new byte[header.length + pkcs1.length];
        System.arraycopy(header, 0, wrapped, 0, header.length);
        System.arraycopy(pkcs1, 0, wrapped, header.length, pkcs1.length);

        int innerLength = pkcs1.length;
        wrapped[header.length - 2] = (byte) (innerLength >> 8);
        wrapped[header.length - 1] = (byte) innerLength;

        int outerLength = wrapped.length - 4;
        wrapped[2] = (byte) (outerLength >> 8);
        wrapped[3] = (byte) outerLength;

        return wrapped;
    }

    private static void store(KeyStore keystore, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            keystore.store(out, STORE_PASSWORD);
        }
        restrictToOwner(file);
    }

    /**
     * Nobody else on the machine reads a private key.
     *
     * <p>Best effort: a filesystem without POSIX permissions says so by throwing, and a studio
     * that refused to start on Windows because it could not chmod would be worse than one that
     * writes the file and carries on.
     */
    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path)
                    ? java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                    : java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX filesystem.
        }
    }

    /** Something in the TLS material could not be read, with what to do about it. */
    public static class TlsException extends RuntimeException {

        public TlsException(String message) {
            super(message);
        }

        public TlsException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param path a file the studio was pointed at
     * @return its contents
     */
    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TlsException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }
}
