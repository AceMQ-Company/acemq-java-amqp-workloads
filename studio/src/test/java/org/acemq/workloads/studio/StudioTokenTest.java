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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An exposed studio with a token: the console, every asset it loads and every API it calls are
 * refused without the token, and served with it, whether it arrives as a header, in the URL or in
 * the cookie the first navigation sets. Only health stays open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("an exposed studio")
class StudioTokenTest {

    private static final String TOKEN = "s3cret-token";

    /** Every page, asset, API and stream the console touches. */
    private static final String[] PATHS = {"/", "/console", "/console/", "/console/index.html",
        "/console/console.css", "/console/console.js", "/console/scenarios.js", "/acemq-mark.svg",
        "/api/presets", "/api/scenarios", "/api/runs", "/api/runs/current",
        "/api/console/overview", "/api/console/loads", "/api/console/evidence"};

    @Autowired
    private TestRestTemplate http;

    static String[] paths() {
        return PATHS;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path root = Files.createTempDirectory("acemq-token-test");
        registry.add("acemq.studio.database", () -> root.resolve("studio.db").toString());
        registry.add("acemq.studio.workloads-dir", () -> root.resolve("workloads").toString());
        registry.add("acemq.studio.token", () -> TOKEN);
    }

    @ParameterizedTest
    @MethodSource("paths")
    @DisplayName("refuses every page, asset and API without the token")
    void refusesWithoutTheToken(String path) {
        ResponseEntity<String> r = http.getForEntity(path, String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody()).contains("needs an access token");
    }

    @Test
    @DisplayName("refuses a wrong token, and a write as much as a read")
    void refusesAWrongToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-it");
        assertThat(http.exchange("/api/presets", HttpMethod.GET, new HttpEntity<>(headers),
                String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/scenarios/check", "{}", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/api/runs/x/stream", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/api/runs/x/report.html", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("serves everything with the token as a header, a parameter or the cookie")
    void servesWithTheToken() {
        for (String path : PATHS) {
            HttpHeaders bearer = new HttpHeaders();
            bearer.setBearerAuth(TOKEN);
            assertThat(http.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer), byte[].class)
                    .getStatusCode()).as("%s with the header", path).isEqualTo(HttpStatus.OK);

            HttpHeaders cookie = new HttpHeaders();
            cookie.add(HttpHeaders.COOKIE, "acemq-studio-token=" + TOKEN);
            assertThat(http.exchange(path, HttpMethod.GET, new HttpEntity<>(cookie), byte[].class)
                    .getStatusCode()).as("%s with the cookie", path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("sets the cookie on the first navigation, so the page's own requests carry it")
    void setsTheCookieFromTheUrl() {
        ResponseEntity<String> r = http.getForEntity("/?token=" + TOKEN, String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("/console/scenarios.js");
        assertThat(r.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(c -> assertThat(c).startsWith("acemq-studio-token=" + TOKEN)
                        .contains("HttpOnly"));
    }

    @Test
    @DisplayName("leaves health open, for a liveness probe that has no secret")
    void healthIsOpen() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
