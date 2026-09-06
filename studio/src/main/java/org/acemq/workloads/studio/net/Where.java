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
package org.acemq.workloads.studio.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the studio is running, which decides what {@code localhost} means.
 *
 * <p>This is the difference between a tool that works and one that produces a connection error
 * nobody can explain. On a laptop, {@code localhost:5672} is the broker somebody started five
 * minutes ago. Inside a container it is the container — nothing is listening, the studio says
 * "connection refused", and the user is looking at a broker that is plainly running.
 *
 * <p>So the studio works out where it is and translates rather than complaining.
 */
public enum Where {

    /** A jar on somebody's machine. {@code localhost} means what they think it means. */
    HOST,

    /** A container on Docker or Podman. The host is reachable, by another name. */
    CONTAINER,

    /**
     * A pod in Kubernetes.
     *
     * <p>There is no host to reach here in any useful sense: the broker is a service, and its
     * name is what should be typed. Suggesting {@code host.docker.internal} in a cluster would be
     * advice that cannot work.
     */
    KUBERNETES;

    /** @return where this process appears to be running */
    public static Where detect() {
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            return KUBERNETES;
        }
        if (Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv"))) {
            return CONTAINER;
        }
        // cgroup v1 names the container runtime in the path. On cgroup v2 hosts this file is
        // usually a single line with no runtime in it, which is why it is the last resort rather
        // than the first check.
        try {
            Path cgroup = Path.of("/proc/self/cgroup");
            if (Files.exists(cgroup)) {
                String contents = Files.readString(cgroup);
                if (contents.contains("docker") || contents.contains("containerd")
                        || contents.contains("kubepods") || contents.contains("libpod")) {
                    return contents.contains("kubepods") ? KUBERNETES : CONTAINER;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not being able to read it is itself a fair sign of a host, and a wrong guess here
            // costs a suggestion rather than a run.
        }
        return HOST;
    }

    /** @return whether {@code localhost} in a broker URL means this process rather than a machine */
    public boolean localhostIsMisleading() {
        return this != HOST;
    }

    /** @return how to describe this in one line to somebody who is confused */
    public String describe() {
        return switch (this) {
            case HOST -> "running directly on this machine";
            case CONTAINER -> "running inside a container, so localhost is the container itself";
            case KUBERNETES -> "running inside a Kubernetes pod, so localhost is the pod itself";
        };
    }
}
