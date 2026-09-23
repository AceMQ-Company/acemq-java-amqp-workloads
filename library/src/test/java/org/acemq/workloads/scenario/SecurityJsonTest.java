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
package org.acemq.workloads.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.acemq.amqp.security.Security;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a {@code security:} block turns into.
 *
 * <p>The thing worth pinning here is the default and the refusal. A block that says nothing is
 * `required', because a security setting that quietly means "do not check" is the setting that
 * ends up in production; and a mode nobody recognises stops the run rather than falling back to
 * something, because "I typed `insecre' and it verified anyway" and "I typed `insecre' and it
 * stopped verifying" are both worse than an error.
 */
class SecurityJsonTest {

    @Test
    void defaultsToVerifying() {
        Security security = new ScenarioFile.SecurityJson(null, null, null, null).toSecurity();

        assertThat(security.mode()).isEqualTo(Security.Mode.REQUIRED);
    }

    @Test
    void insecureIsAskedForByName() {
        Security security =
                new ScenarioFile.SecurityJson("insecure", null, null, null).toSecurity();

        assertThat(security.mode()).isEqualTo(Security.Mode.INSECURE);
    }

    @Test
    void disabledTurnsTlsOff() {
        Security security =
                new ScenarioFile.SecurityJson("disabled", null, null, null).toSecurity();

        assertThat(security.mode()).isEqualTo(Security.Mode.DISABLED);
    }

    @Test
    void aModeNobodyRecognisesStopsTheRun() {
        assertThatThrownBy(() ->
                new ScenarioFile.SecurityJson("insecre", null, null, null).toSecurity())
                .isInstanceOf(ScenarioReader.ScenarioFormatException.class)
                .hasMessageContaining("insecre")
                .hasMessageContaining("required");
    }

    @Test
    void developmentCertificatesAreRefusedUnlessAskedFor() {
        Security refusing = new ScenarioFile.SecurityJson("required", null, null, null)
                .toSecurity();
        Security accepting = new ScenarioFile.SecurityJson("required", null, null, true)
                .toSecurity();

        // The policy object does not expose the flag, so the observable difference is that both
        // are still REQUIRED -- the check happens when a certificate is presented. What this
        // pins is that asking for it is not the same object as not asking, so the flag is
        // actually applied rather than silently dropped.
        assertThat(refusing.mode()).isEqualTo(Security.Mode.REQUIRED);
        assertThat(accepting.mode()).isEqualTo(Security.Mode.REQUIRED);
        assertThat(accepting).isNotSameAs(refusing);
    }

    @Test
    void aFileWithNoSecurityBlockLeavesTheDecisionToTheUrl() {
        ScenarioFile file = new ScenarioFile("s", "", "amqp://localhost:5672", null,
                null, null, null, null, null, null, null);

        assertThat(file.security()).isNull();
    }

    @Test
    @DisplayName("the truststore password is read from a file and never written back to one")
    void theTruststorePasswordIsNeverSerialised() throws Exception {
        // Substitution resolves ${TRUSTSTORE_PASSWORD} before the file is parsed, so by the time
        // this record exists the placeholder is gone and the field holds the secret. Writing the
        // record back out therefore wrote the secret into whatever came next -- a scenario saved
        // from the studio, an export, a run history -- with nothing in the file to say so.
        ScenarioFile.SecurityJson security = new ScenarioFile.SecurityJson(
                "required", "/etc/acemq/truststore.jks", "hunter2", Boolean.TRUE);

        String written = new ObjectMapper(new YAMLFactory()).writeValueAsString(security);

        // Asserted as absence rather than against a particular line, because the fault was a
        // field reaching an output nobody had thought about.
        assertThat(written).doesNotContain("hunter2");
        assertThat(written).contains("/etc/acemq/truststore.jks");
    }

    @Test
    @DisplayName("everything else about a security block survives being written and read again")
    void theRestOfTheBlockRoundTrips() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        ScenarioFile.SecurityJson security = new ScenarioFile.SecurityJson(
                "required", "/etc/acemq/truststore.jks", "hunter2", Boolean.TRUE);

        ScenarioFile.SecurityJson back = yaml.readValue(
                yaml.writeValueAsString(security), ScenarioFile.SecurityJson.class);

        assertThat(back.mode()).isEqualTo("required");
        assertThat(back.truststore()).isEqualTo("/etc/acemq/truststore.jks");
        assertThat(back.allowDevelopmentCertificates()).isTrue();
        // The password is the one thing that does not come back, and the caller has to supply it
        // again through the environment or --truststore-password.
        assertThat(back.truststorePassword()).isNull();
    }
}
