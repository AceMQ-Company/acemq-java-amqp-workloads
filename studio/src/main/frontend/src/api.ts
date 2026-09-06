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

import type {
  BrokerProbe,
  Preset,
  Report,
  RunSummary,
  Scenario,
  ScenarioSample,
} from './types'

// The token, when the studio is running somewhere that needs one. It arrives in
// the URL once and is kept in a cookie by the server, so this is only for the
// first navigation and for fetch calls made before that cookie exists.
const token = new URLSearchParams(window.location.search).get('token')

if (token) {
  document.cookie = `acemq-studio-token=${token}; path=/; SameSite=Strict`
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  })
  if (!response.ok) {
    // The back end says what is wrong in a sentence; showing that is better than
    // showing a status code and letting somebody guess.
    const body = await response.json().catch(() => ({}))
    throw new Error(body.error ?? body.explanation ?? `${response.status} ${response.statusText}`)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

export const api = {
  presets: () => call<Preset[]>('/api/presets'),

  listScenarios: () =>
    call<{ id: string; name: string; description: string; updatedAt: string }[]>('/api/scenarios'),

  loadScenario: (id: string) => call<Scenario>(`/api/scenarios/${id}`),

  saveScenario: (id: string | null, scenario: Scenario) =>
    call<{ id: string }>(id ? `/api/scenarios/${id}` : '/api/scenarios', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(scenario),
    }),

  deleteScenario: (id: string) => call<void>(`/api/scenarios/${id}`, { method: 'DELETE' }),

  check: (scenario: Scenario) =>
    call<{ problems: string[]; warnings: string[]; runnable: boolean }>('/api/scenarios/check', {
      method: 'POST',
      body: JSON.stringify(scenario),
    }),

  probe: (connection: {
    broker: string
    management?: string
    username?: string
    password?: string
  }) => call<BrokerProbe>('/api/broker/probe', {
    method: 'POST',
    body: JSON.stringify(connection),
  }),

  importTopology: (connection: {
    broker: string
    management: string
    username?: string
    password?: string
  }) => call<Scenario>('/api/broker/import', {
    method: 'POST',
    body: JSON.stringify(connection),
  }),

  start: (scenarioId: string | null, scenario: Scenario, broker: string) =>
    call<{ id: string; broker: string; rewritten: boolean; explanation: string }>('/api/runs', {
      method: 'POST',
      body: JSON.stringify({ scenarioId, scenario, broker }),
    }),

  stop: (id: string) => call<{ stopping: boolean }>(`/api/runs/${id}/stop`, { method: 'POST' }),

  current: () => call<{ id?: string; running: boolean }>('/api/runs/current'),

  recentRuns: () => call<RunSummary[]>('/api/runs'),

  report: (id: string) => call<Report>(`/api/runs/${id}/report`),

  samples: (id: string) => call<ScenarioSample[]>(`/api/runs/${id}/samples`),

  /**
   * Subscribes to a run.
   *
   * Server-sent events rather than a socket: it is one-way, the browser
   * reconnects on its own, and a reload picks the run back up because whatever
   * has already happened is replayed on connect.
   */
  watch(
    id: string,
    handlers: {
      onSample: (sample: ScenarioSample) => void
      onPhase: (phase: string) => void
      onFinished: (report: Report) => void
      onFailed: (error: string) => void
    },
  ): () => void {
    const url = token ? `/api/runs/${id}/stream?token=${token}` : `/api/runs/${id}/stream`
    const source = new EventSource(url)

    source.addEventListener('sample', (event) =>
      handlers.onSample(JSON.parse((event as MessageEvent).data)))
    source.addEventListener('phase', (event) =>
      handlers.onPhase(JSON.parse((event as MessageEvent).data).phase))
    source.addEventListener('finished', (event) => {
      handlers.onFinished(JSON.parse((event as MessageEvent).data))
      source.close()
    })
    source.addEventListener('failed', (event) => {
      handlers.onFailed(JSON.parse((event as MessageEvent).data).error)
      source.close()
    })

    return () => source.close()
  },

  /** Downloads the scenario as the file the command line reads. */
  async download(scenario: Scenario, format: 'json' | 'yaml') {
    const response = await fetch(
      format === 'json' ? '/api/scenarios/export' : '/api/scenarios/export.yaml',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(scenario),
      },
    )
    const blob = await response.blob()
    const disposition = response.headers.get('Content-Disposition') ?? ''
    const match = /filename="([^"]+)"/.exec(disposition)

    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = match?.[1] ?? `acemq-workload.${format}`
    link.click()
    URL.revokeObjectURL(link.href)
  },
}
