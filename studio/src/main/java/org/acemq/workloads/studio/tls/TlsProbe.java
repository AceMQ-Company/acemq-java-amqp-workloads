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

import java.net.URI;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.springframework.stereotype.Component;

/**
 * Completes a TLS handshake, and says what it found.
 *
 * <p>A TCP connect proves nothing about TLS. Port 5671 answers a plain socket whether or not the
 * certificate on it is one this client will accept, so a studio that reported "reachable" on a
 * successful connect would send somebody to press Run and meet the real failure a minute later,
 * with a stack trace instead of a sentence.
 *
 * <p>So this does the handshake. What comes back is the certificate the broker presented, whether
 * this client trusts it, and whether the broker asked for one in return — which is the difference
 * between "TLS is on" and "you will need a client certificate too", and is not something anybody
 * can tell by looking at a URL.
 */
@Component
public class TlsProbe {

    private static final int TIMEOUT_MS = 4000;

    /**
     * What a handshake found.
     *
     * @param completed whether the handshake finished
     * @param protocol the version agreed, when it did
     * @param cipherSuite the cipher agreed
     * @param trusted whether this client accepts the chain presented
     * @param clientCertificateRequested whether the broker asked this client to identify itself
     * @param clientCertificateProvided whether one was presented
     * @param chain what the broker presented, outermost first
     * @param problem what went wrong, in a sentence somebody can act on
     */
    public record Result(
            boolean completed,
            String protocol,
            String cipherSuite,
            boolean trusted,
            boolean clientCertificateRequested,
            boolean clientCertificateProvided,
            List<TlsMaterial.Description> chain,
            String problem) {
    }

    /**
     * Shakes hands with a broker.
     *
     * @param url an amqps:// or https:// URL
     * @param settings what to trust and what to present
     * @param workingDirectory where generated keystores may be written
     * @return what the handshake found
     */
    public Result probe(String url, TlsSettings settings, Path workingDirectory) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());

        // The chain is captured whether or not it is trusted, because "what did it present" is
        // the useful half of the answer when the answer is no.
        List<X509Certificate> presented = new ArrayList<>();
        boolean[] trusted = {false};

        try {
            SSLContext context = context(settings, workingDirectory, presented, trusted);
            SSLSocketFactory factory = context.getSocketFactory();

            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), TIMEOUT_MS);
                socket.setSoTimeout(TIMEOUT_MS);
                socket.startHandshake();

                var session = socket.getSession();
                // A broker that wants mutual TLS asks during the handshake. The JDK exposes the
                // question as "which certificate did we send", so this reports what was sent
                // rather than guessing at what was asked.
                boolean provided = session.getLocalCertificates() != null;

                return new Result(true, session.getProtocol(), session.getCipherSuite(),
                        trusted[0], settings.isMutual(), provided, describe(presented), null);
            }
        } catch (SSLHandshakeException e) {
            return new Result(false, null, null, trusted[0], settings.isMutual(), false,
                    describe(presented), explain(e, presented, settings));
        } catch (TlsMaterial.TlsException e) {
            return new Result(false, null, null, false, settings.isMutual(), false, List.of(),
                    e.getMessage());
        } catch (Exception e) {
            return new Result(false, null, null, false, settings.isMutual(), false,
                    describe(presented),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * The context to shake hands with: the trust the settings describe, wrapped so the chain is
     * recorded on the way past.
     */
    private SSLContext context(TlsSettings settings, Path workingDirectory,
            List<X509Certificate> presented, boolean[] trusted) throws Exception {
        X509TrustManager delegate = settings.trustAnyCertificate() ? null : trustManager(settings);

        X509TrustManager recording = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                presented.clear();
                presented.addAll(List.of(chain));
                if (delegate == null) {
                    // trustAnyCertificate: the handshake completes and nothing is proven, which
                    // is what the caller asked for and what the answer will say.
                    trusted[0] = false;
                    return;
                }
                delegate.checkServerTrusted(chain, authType);
                trusted[0] = true;
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return delegate == null ? new X509Certificate[0] : delegate.getAcceptedIssuers();
            }
        };

        KeyManagerFactory keys = null;
        if (settings.isMutual()) {
            Path directory = workingDirectory;
            TlsMaterial.write(directory, settings.authority(), settings.clientCertificate(),
                    settings.clientKey());
            KeyStore identity = KeyStore.getInstance("PKCS12");
            try (var in = java.nio.file.Files.newInputStream(directory.resolve("keystore.p12"))) {
                identity.load(in, TlsMaterial.storePassword());
            }
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(identity, TlsMaterial.storePassword());
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(),
                new javax.net.ssl.TrustManager[] {recording}, null);
        return context;
    }

    private X509TrustManager trustManager(TlsSettings settings) throws Exception {
        KeyStore trust = null;
        if (settings.hasAuthority()) {
            trust = KeyStore.getInstance("PKCS12");
            trust.load(null, TlsMaterial.storePassword());
            List<X509Certificate> authorities = TlsMaterial.certificates(settings.authority());
            for (int i = 0; i < authorities.size(); i++) {
                trust.setCertificateEntry("ca-" + i, authorities.get(i));
            }
        }

        // A null keystore means the JVM's own, which is the right default for a broker with a
        // certificate from a public authority.
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("this JVM has no X509 trust manager");
    }

    /**
     * Why the handshake failed, in terms of what to do next.
     *
     * <p>The JDK's own message for the common case is "unable to find valid certification path to
     * requested target", which is true and tells nobody anything. What somebody needs to know is
     * that the broker presented a certificate signed by an authority they have not given the
     * studio — and, if it is one of the AceMQ development certificates, that it is refused on
     * purpose.
     */
    private String explain(SSLHandshakeException e, List<X509Certificate> presented,
            TlsSettings settings) {
        String message = String.valueOf(e.getMessage());

        if (!presented.isEmpty()) {
            TlsMaterial.Description certificate = TlsMaterial.Description.of(presented.get(0));
            if (certificate.expired()) {
                return "the broker's certificate expired on " + certificate.notAfter()
                        + " (" + certificate.subject() + ")";
            }
            if (!settings.hasAuthority()) {
                return "the broker presented a certificate signed by " + certificate.issuer()
                        + ", which is not in this machine's trust store. Give the studio that"
                        + " authority's certificate -- usually ca.pem or ca.crt -- or, for a"
                        + " first run against a broker whose certificate you cannot find, turn"
                        + " on trust any certificate";
            }
            if (message.contains("certification path")) {
                return "the broker presented a certificate signed by " + certificate.issuer()
                        + ", which the authority you gave did not sign";
            }
            if (message.contains("No name matching") || message.contains("No subject alternative")) {
                return "the certificate is for " + certificate.subject()
                        + ", which is not the host in the URL. That is a real mismatch, not a"
                        + " formality: it is what stops a connection to the wrong machine";
            }
        }

        if (message.contains("bad_certificate") || message.contains("certificate_required")) {
            return "the broker requires a client certificate and none was presented. This is"
                    + " mutual TLS: give the studio the client certificate and its private key";
        }
        return message;
    }

    private static List<TlsMaterial.Description> describe(List<X509Certificate> chain) {
        List<TlsMaterial.Description> described = new ArrayList<>();
        for (X509Certificate certificate : chain) {
            described.add(TlsMaterial.Description.of(certificate));
        }
        return described;
    }

    private static int defaultPort(String scheme) {
        return switch (scheme == null ? "" : scheme) {
            case "amqps" -> 5671;
            case "https" -> 15671;
            case "http" -> 15672;
            default -> 5672;
        };
    }
}
