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

import org.acemq.workloads.studio.net.Where;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The studio.
 *
 * <pre>{@code
 * java -jar acemq-workloads-studio.jar
 * }</pre>
 *
 * <p>One jar, one file of state, no other moving parts. It binds to localhost when it is running
 * on somebody's machine and to every interface when it is in a container — because in a container
 * localhost is useless — and in that case it will not start without an access token, since a tool
 * that can generate load against any broker it can reach should not be on an open port.
 */
@SpringBootApplication
@EnableConfigurationProperties(StudioProperties.class)
public class StudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudioApplication.class, args);
    }

    /**
     * Says where it is and how to reach it.
     *
     * <p>Spring's own line says the port and nothing else. What somebody needs on the first run is
     * the URL to open, the token if there is one, and — the part that saves an hour — what
     * {@code localhost} is going to mean from where this is running.
     */
    @Component
    static class Banner {

        private final StudioProperties properties;
        private final Environment environment;

        Banner(StudioProperties properties, Environment environment) {
            this.properties = properties;
            this.environment = environment;
        }

        @EventListener(ApplicationReadyEvent.class)
        void announce() {
            String port = environment.getProperty("local.server.port", "8480");
            Where where = Where.detect();

            StringBuilder out = new StringBuilder("\n");
            out.append("  AceMQ workloads studio\n");
            out.append("  http://localhost:").append(port).append('\n');

            if (properties.token() != null && !properties.token().isBlank()) {
                out.append("  access token: ").append(properties.token()).append('\n');
                out.append("  (open http://localhost:").append(port)
                        .append("/?token=").append(properties.token()).append(")\n");
            }

            out.append("  ").append(where.describe()).append('\n');
            if (where.localhostIsMisleading()) {
                out.append("  a broker on your machine is not at localhost from here --")
                        .append(" the studio will find it and say so\n");
            }
            out.append("  state: ").append(properties.databasePath()).append('\n');

            System.out.println(out);
        }
    }
}
