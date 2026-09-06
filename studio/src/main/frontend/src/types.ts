// Copyright 2026 AceMQ.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// The scenario file, exactly as the back end reads and writes it. One shape for
// the designer, the API, the saved file and the command line -- which is the
// only reason designing here and running in a pipeline is the same scenario
// rather than two that look alike.

export type QueueTypeId = 'classic' | 'classic-mirrored' | 'quorum' | 'stream'

export interface Binding {
  exchange: string
  routingKey: string
}

export interface Consumers {
  concurrency?: number
  prefetch?: number
  handlerTime?: string
  failureRate?: number
  enabled?: boolean
}

export interface Exchange {
  name: string
  type: string
  durable?: boolean
  enabled?: boolean
  arguments?: Record<string, unknown>
}

// What a node is asked to prove. Nothing stated is nothing checked, and what is
// checked here is what the command line's exit code follows -- so a scenario
// designed on this screen can fail a build on a number.
export interface Expect {
  p50Below?: string
  p99Below?: string
  p999Below?: string
  consumeRateAtLeast?: number
  achievedRateAtLeast?: number
  withinPercentOfOffered?: number
  noFailures?: boolean
  noBacklog?: boolean
}

export interface Queue {
  name: string
  type?: QueueTypeId
  durable?: boolean
  enabled?: boolean
  deadLetterExchange?: string
  bindings?: Binding[]
  consumers?: Consumers
  arguments?: Record<string, unknown>
  expect?: Expect
}

export interface Producer {
  name: string
  exchange: string
  routingKeys?: string[]
  rate?: number
  threads?: number
  messageSize?: number
  confirms?: boolean
  maxInFlight?: number
  maxMessages?: number
  enabled?: boolean
  expect?: Expect
}

export interface Scenario {
  name: string
  description?: string
  broker?: string
  management?: string
  exchanges?: Exchange[]
  queues?: Queue[]
  producers?: Producer[]
  warmup?: string
  runFor?: string
  declare?: boolean
  ui?: Record<string, unknown>
}

// What a run looks like while it is happening.

export type Phase = 'STARTING' | 'WARMUP' | 'MEASURING' | 'DRAINING'

export interface LatencySummary {
  name: string
  count: number
  // The engine sends durations as ISO-8601; only the percentiles are read here.
  p50?: string
  p99?: string
}

export interface ProducerSample {
  name: string
  published: number
  confirmed: number
  failed: number
  publishRate: number
  sendLag: LatencySummary
}

export interface QueueSample {
  name: string
  consumed: number
  consumeRate: number
  depth: number | null
  endToEnd: LatencySummary
  consuming: boolean
}

export interface ScenarioSample {
  at: string
  elapsed: string
  phase: Phase
  producers: ProducerSample[]
  queues: QueueSample[]
  blocked: boolean
}

// And what it did.

export interface Latency {
  count: number
  p50: number
  p90: number
  p99: number
  p999: number
  max: number
}

export interface Finding {
  rule: string
  severity: 'INVALID' | 'FAILED' | 'WARNING' | 'INFO'
  observation: string
  implication: string
}

export interface Report {
  scenario: string
  startedAt: string
  durationMs: number
  stoppedEarly: boolean
  verdict: 'passed' | 'failed' | 'invalid'
  valid: boolean
  totalPublished: number
  totalConsumed: number
  blockedMs: number
  blockedReason: string | null
  producers: {
    name: string
    offeredRate: number
    achievedRate: number
    published: number
    confirmed: number
    failed: number
    publishLatency: Latency
    sendLag: Latency
  }[]
  queues: {
    name: string
    type: string
    consumers: number
    consumed: number
    consumeRate: number
    endToEnd: Latency
    depthAtStart: number | null
    depthAtEnd: number | null
    grew: boolean
  }[]
  findings: Finding[]
}

export interface RunSummary {
  id: string
  scenarioId: string | null
  scenarioName: string
  broker: string
  startedAt: string
  finishedAt: string | null
  status: 'running' | 'finished' | 'failed'
  verdict: string | null
  error: string | null
}

export interface QueueTypeInfo {
  id: QueueTypeId
  label: string
  description: string
  supported: boolean
  whyNot: string | null
}

export interface TlsSettings {
  enabled: boolean
  caPath?: string
  caPem?: string
  clientCertificatePath?: string
  clientCertificatePem?: string
  clientKeyPath?: string
  clientKeyPem?: string
  trustAnyCertificate?: boolean
  allowDevelopmentCertificates?: boolean
}

export interface CertificateDescription {
  subject: string
  issuer: string
  notBefore: string
  notAfter: string
  expired: boolean
  development: boolean
}

export interface TlsResult {
  completed: boolean
  protocol: string | null
  cipherSuite: string | null
  trusted: boolean
  clientCertificateRequested: boolean
  clientCertificateProvided: boolean
  chain: CertificateDescription[]
  problem: string | null
}

export interface BrokerProbe {
  where: 'host' | 'container' | 'kubernetes'
  whereDescription: string
  hostCandidates: string[]
  amqp: {
    requested: string
    reachable: boolean
    url: string
    rewritten: boolean
    explanation: string
    attempts: { url: string; reachable: boolean; detail: string }[]
  }
  management?: {
    requested: string
    reachable: boolean
    url: string
    rewritten: boolean
    explanation: string
  }
  capabilities: {
    version: string | null
    known: boolean
    queueTypes: QueueTypeInfo[]
  }
  tls?: TlsResult
}

export interface Preset {
  id: string
  title: string
  question: string
  scenario: Scenario
}
