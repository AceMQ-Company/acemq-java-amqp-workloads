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
 * One route from an exchange into a queue.
 *
 * @param exchange where the messages come from
 * @param routingKey the pattern they arrive under, empty for a fanout
 */
public record Binding(String exchange, String routingKey) {

    @Override
    public String toString() {
        return exchange + " -> [" + routingKey + "]";
    }
}
