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
package org.acemq.workloads.studio;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import org.acemq.workloads.studio.net.Where;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the studio is configured, and the two decisions that matter.
 *
 * <p><strong>Where it listens.</strong> On a machine it binds to localhost: the studio has no
 * accounts, and a tool that can point load at any broker it can reach should not be listening on
 * a network by accident. In a container that would make it unreachable through a published port,
 * so there it binds to everything — and pays for that by requiring a token.
 *
 * <p><strong>The token.</strong> Set {@code ACEMQ_STUDIO_TOKEN} and that is the token. Leave it
 * unset on a machine and there is none, because there is nothing to protect against on a loopback
 * interface. Leave it unset in a container and one is generated and printed at startup, which is
 * the only combination that is both usable and not open.
 */
@ConfigurationProperties(prefix = "acemq.studio")
public class StudioProperties {

    private String address;
    private String token;
    private String database;
    private boolean allowRemoteWithoutToken;

    /** @return the address to bind to */
    public String address() {
        if (address != null && !address.isBlank()) {
            return address;
        }
        return Where.detect().localhostIsMisleading() ? "0.0.0.0" : "127.0.0.1";
    }

    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * @return the access token, generated when the studio is exposed and nobody set one
     */
    public String token() {
        if (token != null && !token.isBlank()) {
            return token;
        }
        if (!"127.0.0.1".equals(address()) && !allowRemoteWithoutToken) {
            token = generateToken();
            return token;
        }
        return null;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /**
     * Turns the token off for an exposed studio.
     *
     * <p>There is a legitimate case — a private network, an ingress that already authenticates —
     * and a switch that has to be set deliberately is better than one nobody can turn off. It is
     * not the default, and the startup line says what it means.
     *
     * @param allow whether to run without a token on a non-loopback address
     */
    public void setAllowRemoteWithoutToken(boolean allow) {
        this.allowRemoteWithoutToken = allow;
    }

    public boolean isAllowRemoteWithoutToken() {
        return allowRemoteWithoutToken;
    }

    /**
     * Where the state lives.
     *
     * <p>One SQLite file under the user's home by default: copyable, inspectable with any sqlite
     * client, and deletable when somebody wants to start again. In a container it belongs on a
     * volume, which is what {@code ACEMQ_STUDIO_DATABASE} is for.
     *
     * @return the database file
     */
    public Path databasePath() {
        if (database != null && !database.isBlank()) {
            return Path.of(database);
        }
        return Path.of(System.getProperty("user.home"), ".acemq", "workloads-studio.db");
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
