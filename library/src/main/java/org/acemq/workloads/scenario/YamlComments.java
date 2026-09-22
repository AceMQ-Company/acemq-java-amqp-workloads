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

/**
 * Finds the comments in a YAML document, so that substitution can leave them alone.
 *
 * <h2>Why this is needed at all</h2>
 *
 * <p>{@code ${VAR}} is resolved over the whole file before anything parses it, which is the only
 * way to let a placeholder appear anywhere — a rate, a queue name, a URL — without teaching the
 * substituter the shape of the document. The cost of that was that it also ran over the
 * comments, so a file explaining its own syntax to the next reader could not write the syntax
 * down: {@code # export ${BROKER_PASSWORD} before running this} aborted the run with an
 * unset-variable error, and the workaround was to describe the placeholder in words instead.
 *
 * <p>A comment is not part of the document. Nothing in it reaches the parser, so nothing in it
 * has any business stopping the run.
 *
 * <h2>What counts as a comment</h2>
 *
 * <p>A {@code #} that starts a line or follows a space or a tab, up to the end of that line —
 * YAML's rule. A {@code #} inside a quoted scalar is a character like any other, so quoting is
 * tracked, and a quote does not carry across a line break.
 *
 * <p>Block scalars ({@code |} and {@code >}) are not tracked: a {@code #} inside one is literal
 * text to YAML and a comment to this. No file this tool reads has ever needed one, and the cost
 * of being wrong is a placeholder left unresolved inside a block of prose rather than anything
 * reaching a broker.
 */
public final class YamlComments {

    private YamlComments() {
    }

    /**
     * @param text a YAML document
     * @return one flag per character, true where that character is inside a comment
     */
    public static boolean[] mask(String text) {
        boolean[] comment = new boolean[text.length()];
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inComment = false;
        boolean afterSpace = true;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                inSingle = false;
                inDouble = false;
                inComment = false;
                afterSpace = true;
                continue;
            }
            if (inComment) {
                comment[i] = true;
                continue;
            }
            if (inDouble) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (inSingle) {
                // '' is how YAML writes a quote inside single quotes, so a pair is not an end.
                if (c == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '#' && afterSpace) {
                inComment = true;
                comment[i] = true;
            }
            afterSpace = c == ' ' || c == '\t';
        }
        return comment;
    }
}
