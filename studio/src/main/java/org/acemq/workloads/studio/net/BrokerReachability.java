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
package org.acemq.workloads.studio.net;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Finds the broker somebody meant.
 *
 * <p>The problem this exists for: the studio may be a container, and a container's
 * {@code localhost} is itself. Somebody types {@code amqp://localhost:5672}, sees "connection
 * refused", and is looking straight at a broker that is running. Telling them "connection
 * refused" is technically true and completely useless.
 *
 * <p>So when the host in a URL cannot be reached and this process is containerised, the same port
 * is tried on the names that <em>do</em> reach the machine outside — Docker Desktop's
 * {@code host.docker.internal}, Podman's {@code host.containers.internal}, and on Linux the
 * default gateway, which is the host on a bridge network. What answers is reported, with the URL
 * rewritten to it.
 *
 * <p>Nothing is rewritten silently. The studio shows what it tried and what answered, because a
 * tool that quietly connects somewhere other than where it was told is worse than one that fails.
 */
@Component
public class BrokerReachability {

    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(700);

    private final Where where;

    public BrokerReachability() {
        this(Where.detect());
    }

    BrokerReachability(Where where) {
        this.where = where;
    }

    /**
     * What happened when the studio tried to reach a broker.
     *
     * @param requestedUrl the URL as it was given
     * @param reachableUrl a URL that answered, or null when none did
     * @param attempts every host and port tried, in order, and what happened
     * @param where where the studio is running
     */
    public record Probe(String requestedUrl, String reachableUrl, List<Attempt> attempts,
            Where where) {

        /** @return whether anything answered */
        public boolean isReachable() {
            return reachableUrl != null;
        }

        /** @return whether the URL had to be changed to reach the broker */
        public boolean wasRewritten() {
            return isReachable() && !reachableUrl.equals(requestedUrl);
        }

        /**
         * @return what to tell somebody, in a sentence
         */
        public String explain() {
            if (!isReachable()) {
                if (where.localhostIsMisleading()) {
                    return "nothing answered. The studio is " + where.describe()
                            + ", so a broker on your machine is not at localhost -- "
                            + (where == Where.KUBERNETES
                                    ? "use the broker's service name, such as"
                                            + " rabbitmq.default.svc.cluster.local"
                                    : "try host.docker.internal, or the broker's container name if"
                                            + " it shares a network with this one");
                }
                return "nothing answered at " + requestedUrl;
            }
            if (wasRewritten()) {
                return "the studio is " + where.describe() + ", so " + hostOf(requestedUrl)
                        + " is not your machine. " + hostOf(reachableUrl) + " answered instead";
            }
            return "reachable";
        }
    }

    /**
     * One host and port that was tried.
     *
     * @param url the URL that was tried
     * @param reachable whether the port answered
     * @param detail what happened, when it did not
     */
    public record Attempt(String url, boolean reachable, String detail) {
    }

    /**
     * Tries a broker URL, and the alternatives that make sense from here.
     *
     * @param url an AMQP or HTTP URL
     * @return what answered, and everything that was tried
     */
    public Probe probe(String url) {
        List<Attempt> attempts = new ArrayList<>();
        String first = tryUrl(url, attempts);
        if (first != null) {
            return new Probe(url, first, attempts, where);
        }

        // Only worth guessing when the name given is one that means "me".
        String host = hostOf(url);
        if (!where.localhostIsMisleading() || !isLoopback(host)) {
            return new Probe(url, null, attempts, where);
        }

        for (String candidate : hostCandidates()) {
            String rewritten = withHost(url, candidate);
            String reachable = tryUrl(rewritten, attempts);
            if (reachable != null) {
                return new Probe(url, reachable, attempts, where);
            }
        }
        return new Probe(url, null, attempts, where);
    }

    /**
     * The names that reach the machine outside this container, best first.
     *
     * @return the candidates, empty when there is no host to reach
     */
    public List<String> hostCandidates() {
        if (where == Where.KUBERNETES) {
            // There is no "host" worth reaching from a pod, and pretending otherwise sends people
            // down a road with nothing at the end of it.
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        // Docker Desktop on macOS and Windows, and recent Docker Engine on Linux with
        // --add-host=host.docker.internal:host-gateway.
        candidates.add("host.docker.internal");
        // Podman's equivalent.
        candidates.add("host.containers.internal");
        // On a Linux bridge network the default gateway is the host.
        defaultGateway().ifPresent(candidates::add);
        return List.copyOf(candidates);
    }

    /**
     * The default gateway, read from the routing table.
     *
     * <p>On a bridge network this is the host. Read from {@code /proc/net/route} rather than by
     * running {@code ip route}, because the image the studio ships in has no {@code ip}.
     *
     * @return the gateway address, when there is one
     */
    Optional<String> defaultGateway() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                java.nio.file.Files.newInputStream(java.nio.file.Path.of("/proc/net/route")),
                StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] columns = line.split("\\s+");
                if (columns.length > 2 && "00000000".equals(columns[1])) {
                    // The gateway is little-endian hexadecimal, which is why this looks the way
                    // it does rather than being a parse of a dotted quad.
                    long value = Long.parseLong(columns[2], 16);
                    return Optional.of((value & 0xFF) + "." + ((value >> 8) & 0xFF) + "."
                            + ((value >> 16) & 0xFF) + "." + ((value >> 24) & 0xFF));
                }
            }
        } catch (Exception e) {
            // No /proc, no route table, no guess. Not an error: this is one candidate of several.
        }
        return Optional.empty();
    }

    private String tryUrl(String url, List<Attempt> attempts) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());
            if (host == null) {
                attempts.add(new Attempt(url, false, "no host in the URL"));
                return null;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), (int) PROBE_TIMEOUT.toMillis());
                attempts.add(new Attempt(url, true, "answered"));
                return url;
            }
        } catch (Exception e) {
            attempts.add(new Attempt(url, false,
                    e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
            return null;
        }
    }

    private static int defaultPort(String scheme) {
        if (scheme == null) {
            return 5672;
        }
        return switch (scheme) {
            case "amqps" -> 5671;
            case "http" -> 15672;
            case "https" -> 15671;
            default -> 5672;
        };
    }

    private static boolean isLoopback(String host) {
        return host != null
                && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                        || host.equals("0.0.0.0"));
    }

    /**
     * @param url a URL
     * @return the host in it, or the whole thing when it will not parse
     */
    public static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String withHost(String url, String host) {
        try {
            URI uri = URI.create(url);
            String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo() + "@";
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return uri.getScheme() + "://" + userInfo + host + port + path;
        } catch (RuntimeException e) {
            return url;
        }
    }
}
