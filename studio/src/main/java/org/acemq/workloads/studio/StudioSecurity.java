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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The only two security decisions the studio makes, in one place.
 *
 * <p>It is a tool that generates load against brokers and stores connection details. That is
 * enough to be worth not leaving open, and not enough to justify accounts, roles and a login
 * page nobody wants on a laptop. So: bound to loopback where that works, and a shared token where
 * it does not.
 */
@Configuration
public class StudioSecurity {

    /**
     * Binds where the properties say, which is loopback unless this is a container.
     *
     * @param properties the studio's configuration
     * @return the customiser
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> bindAddress(
            StudioProperties properties) {
        return factory -> {
            try {
                factory.setAddress(java.net.InetAddress.getByName(properties.address()));
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException(
                        "cannot bind to " + properties.address(), e);
            }
        };
    }

    /**
     * Checks the token, when there is one.
     *
     * @param properties the studio's configuration
     * @return the filter
     */
    @Bean
    TokenFilter tokenFilter(StudioProperties properties) {
        return new TokenFilter(properties);
    }

    /** Lets a request through when it carries the token, and not otherwise. */
    static class TokenFilter extends OncePerRequestFilter {

        private final StudioProperties properties;

        TokenFilter(StudioProperties properties) {
            this.properties = properties;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String expected = properties.token();
            if (expected == null || expected.isBlank()) {
                chain.doFilter(request, response);
                return;
            }

            // Health is open on purpose: a container that will not answer a liveness probe
            // without a secret is a container that gets restarted for ever.
            String path = request.getRequestURI();
            if (path.startsWith("/actuator/health") || path.startsWith("/actuator/info")) {
                chain.doFilter(request, response);
                return;
            }

            String presented = presentedToken(request);
            if (presented != null && constantTimeEquals(expected, presented)) {
                chain.doFilter(request, response);
                return;
            }

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"this studio needs an access token."
                    + " It was printed when the studio started; open the URL it gave you, or send"
                    + " it as the Authorization: Bearer header\"}");
        }

        private static String presentedToken(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                return header.substring("Bearer ".length()).trim();
            }
            String parameter = request.getParameter("token");
            if (parameter != null && !parameter.isBlank()) {
                return parameter.trim();
            }
            // A cookie, so the token in the URL survives the first navigation and does not have
            // to be pasted into every subsequent request by hand.
            if (request.getCookies() != null) {
                for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                    if ("acemq-studio-token".equals(cookie.getName())) {
                        return cookie.getValue();
                    }
                }
            }
            return null;
        }

        /**
         * Compares without leaking how much of the token was right.
         *
         * <p>A plain equals returns as soon as two characters differ, and the time it took says
         * how long the matching prefix was. It is a small leak over a loopback interface and a
         * real one over a network, and the fix costs nothing.
         */
        private static boolean constantTimeEquals(String expected, String presented) {
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8));
        }
    }
}
