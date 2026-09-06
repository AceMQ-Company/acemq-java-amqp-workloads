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
 * Something in a scenario that can be switched off without being removed.
 *
 * <p>The switch is the point. "What happens if this consumer stops" is the question people run
 * these things to answer, and answering it by deleting the consumer loses what you were about to
 * put back — along with its prefetch, its handler time, and the reason somebody wrote them down.
 */
public interface Node {

    /** @return what this node is called */
    String name();

    /** @return whether it takes part in the run */
    boolean isEnabled();
}
