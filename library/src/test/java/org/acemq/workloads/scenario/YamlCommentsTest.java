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

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("substitution and comments")
class YamlCommentsTest {

    private static final Function<String, String> NOTHING_SET = name -> null;
    private static final Function<String, String> ENVIRONMENT =
            Map.of("BROKER_PASSWORD", "s3cret")::get;

    @Nested
    @DisplayName("a placeholder in a comment")
    class InAComment {

        @Test
        @DisplayName("does not stop the run when it is not set")
        void unsetInCommentIsNotAnError() {
            // The fault this pins: a file explaining its own syntax to the next reader could
            // not write the syntax down. The comment never reaches the parser, so nothing in
            // it has any business deciding whether the run happens.
            String text = """
                    # Export ${BROKER_PASSWORD} before running this.
                    name: documented
                    broker: amqp://guest:guest@localhost:5672
                    """;

            assertThat(ScenarioReader.substitute(text, NOTHING_SET, true))
                    .contains("${BROKER_PASSWORD}")
                    .contains("amqp://guest:guest@localhost:5672");
        }

        @Test
        @DisplayName("is left as written even when it is set")
        void setInCommentIsStillLeftAlone() {
            // Resolving it would put the password into the comment, which is a file somebody
            // pastes into a ticket.
            String text = "# use ${BROKER_PASSWORD}\nname: n\n";

            assertThat(ScenarioReader.substitute(text, ENVIRONMENT, true))
                    .contains("# use ${BROKER_PASSWORD}")
                    .doesNotContain("s3cret");
        }

        @Test
        @DisplayName("at the end of a line of real configuration")
        void trailingComment() {
            String text = "broker: amqp://guest:${BROKER_PASSWORD}@h  # or ${OTHER_ONE}\n";

            assertThat(ScenarioReader.substitute(text, ENVIRONMENT, true))
                    .isEqualTo("broker: amqp://guest:s3cret@h  # or ${OTHER_ONE}\n");
        }
    }

    @Nested
    @DisplayName("a placeholder outside a comment")
    class OutsideAComment {

        @Test
        @DisplayName("is still resolved, and still refused when unset")
        void unsetOutsideCommentStillFails() {
            assertThatThrownBy(() -> ScenarioReader.substitute(
                    "broker: amqp://guest:${BROKER_PASSWORD}@h\n", NOTHING_SET, true))
                    .isInstanceOf(ScenarioReader.ScenarioFormatException.class)
                    .hasMessageContaining("${BROKER_PASSWORD}")
                    .hasMessageContaining("not set");
        }

        @Test
        @DisplayName("when a hash is part of a routing key rather than a comment")
        void hashInsideAValue() {
            // "orders.#" is an ordinary topic wildcard, and it is not preceded by a space, so
            // it does not start a comment. Getting this wrong would silently stop substituting
            // everything after any binding in the file.
            String text = """
                    queues:
                      - bindings: [ { exchange: ex, routingKey: orders.# } ]
                    broker: amqp://guest:${BROKER_PASSWORD}@h
                    """;

            assertThat(ScenarioReader.substitute(text, ENVIRONMENT, true)).contains("s3cret");
        }

        @Test
        @DisplayName("when a hash is inside a quoted string")
        void hashInsideQuotes() {
            String text = """
                    routingKey: "orders. # not a comment"
                    broker: amqp://guest:${BROKER_PASSWORD}@h
                    """;

            assertThat(ScenarioReader.substitute(text, ENVIRONMENT, true)).contains("s3cret");
        }

        @Test
        @DisplayName("in JSON, where a hash is never a comment")
        void jsonHasNoComments() {
            // JSON cannot carry a comment, so a # is a character in a string and nothing else.
            String text = "{\"routingKey\": \"# ${BROKER_PASSWORD}\"}";

            assertThat(ScenarioReader.substitute(text, ENVIRONMENT, false)).contains("s3cret");
        }
    }

    @Nested
    @DisplayName("the mask itself")
    class Mask {

        @Test
        @DisplayName("a comment ends with its line")
        void commentsEndAtTheLineBreak() {
            boolean[] comment = YamlComments.mask("a: 1 # here\nb: 2\n");

            assertThat(comment[0]).isFalse();
            assertThat(comment["a: 1 ".length()]).isTrue();
            assertThat(comment["a: 1 # here\nb".length() - 1]).isFalse();
        }

        @Test
        @DisplayName("a quote does not carry across a line break")
        void quotesDoNotSpanLines() {
            // An unbalanced quote on one line would otherwise swallow the rest of the file,
            // and every comment below it would stop being a comment.
            boolean[] comment = YamlComments.mask("a: it's fine\n# and this is a comment\n");

            assertThat(comment["a: it's fine\n".length()]).isTrue();
        }
    }
}
