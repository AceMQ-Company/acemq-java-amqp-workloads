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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** An exchange in a scenario. */
public final class ExchangeNode implements Node {

    private final String name;
    private String type;
    private boolean durable = true;
    private boolean enabled = true;
    private final Map<String, Object> arguments = new LinkedHashMap<>();

    ExchangeNode(String name, String type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = type == null ? "topic" : type;
    }

    /**
     * @param type direct, topic, fanout or headers
     * @return this node
     */
    public ExchangeNode type(String type) {
        this.type = type;
        return this;
    }

    /** @return this node, not surviving a broker restart */
    public ExchangeNode transient_() {
        this.durable = false;
        return this;
    }

    /**
     * @param name an exchange argument, such as {@code alternate-exchange}
     * @param value its value
     * @return this node
     */
    public ExchangeNode argument(String name, Object value) {
        arguments.put(name, value);
        return this;
    }

    /**
     * @param enabled whether it takes part in the run
     * @return this node
     */
    public ExchangeNode enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public boolean isDurable() {
        return durable;
    }

    public Map<String, Object> arguments() {
        return Map.copyOf(arguments);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "Exchange{" + name + " (" + type + ")" + (enabled ? "" : ", off") + "}";
    }
}
