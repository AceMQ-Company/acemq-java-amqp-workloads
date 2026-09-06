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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { api } from './api'
import { Connect } from './Connect'
import { Canvas, type Selection } from './designer/Canvas'
import { Inspector } from './designer/Inspector'
import { LiveView } from './run/LiveView'
import type {
  BrokerProbe,
  Preset,
  QueueTypeInfo,
  Report,
  RunSummary,
  Scenario,
  ScenarioSample,
  TlsSettings,
} from './types'

type Tab = 'design' | 'run' | 'presets' | 'history'

const EMPTY: Scenario = {
  name: 'new scenario',
  description: '',
  exchanges: [{ name: 'bench', type: 'topic' }],
  queues: [
    {
      name: 'bench.queue',
      type: 'classic',
      bindings: [{ exchange: 'bench', routingKey: '#' }],
      consumers: { concurrency: 2, prefetch: 100 },
    },
  ],
  producers: [{ name: 'load', exchange: 'bench', routingKeys: ['k'], rate: 5000, messageSize: 1024 }],
  warmup: '5s',
  runFor: '30s',
}

// Every queue type, assumed supported until a broker says otherwise. Showing
// nothing until a probe has run would make the designer look broken to anybody
// who opens it before filling in a broker URL.
const ALL_TYPES: QueueTypeInfo[] = [
  { id: 'classic', label: 'Classic', description: 'One node, no replication. The fastest, and the least safe.', supported: true, whyNot: null },
  { id: 'classic-mirrored', label: 'Classic, mirrored', description: 'A classic queue with an HA policy. Removed in RabbitMQ 4.0.', supported: true, whyNot: null },
  { id: 'quorum', label: 'Quorum', description: 'Replicated and durable. The safety costs throughput; measure it.', supported: true, whyNot: null },
  { id: 'stream', label: 'Stream', description: 'An append-only log. Consumers read at their own offset.', supported: true, whyNot: null },
]

export default function App() {
  const [tab, setTab] = useState<Tab>('design')
  const [scenario, setScenario] = useState<Scenario>(EMPTY)
  const [scenarioId, setScenarioId] = useState<string | null>(null)
  const [selection, setSelection] = useState<Selection>(null)

  const [broker, setBroker] = useState('amqp://guest:guest@localhost:5672')
  const [management, setManagement] = useState('http://localhost:15672')
  const [probe, setProbe] = useState<BrokerProbe | null>(null)
  const [tls, setTls] = useState<TlsSettings>({ enabled: false })
  // Nothing here works without a broker, so the connection is established first
  // and the rest of the application is not reachable until it is.
  const [connected, setConnected] = useState(false)

  const [runId, setRunId] = useState<string | null>(null)
  const [samples, setSamples] = useState<ScenarioSample[]>([])
  const [phase, setPhase] = useState('STARTING')
  const [report, setReport] = useState<Report | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [check, setCheck] = useState<{ problems: string[]; warnings: string[] }>({
    problems: [],
    warnings: [],
  })

  const [presets, setPresets] = useState<Preset[]>([])
  const [runs, setRuns] = useState<RunSummary[]>([])
  const [saved, setSaved] = useState<{ id: string; name: string; description: string }[]>([])

  const unwatch = useRef<(() => void) | null>(null)

  useEffect(() => {
    api.presets().then(setPresets).catch(() => {})
    refreshLists()

    // A run started before this tab was opened is still going. Attaching to it
    // rather than showing an empty screen is what makes a reload harmless.
    // A run started before this tab was opened is still going, and somebody
    // reloading mid-run should land back on it rather than at the gate.
    api.current().then((current) => {
      if (current.running && current.id) {
        setConnected(true)
        setRunId(current.id)
        setTab('run')
        attach(current.id)
      }
    }).catch(() => {})

    return () => unwatch.current?.()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const timer = setTimeout(() => {
      api.check(scenario)
        .then((result) => setCheck({ problems: result.problems, warnings: result.warnings }))
        .catch(() => {})
    }, 350)
    return () => clearTimeout(timer)
  }, [scenario])

  function refreshLists() {
    api.recentRuns().then(setRuns).catch(() => {})
    api.listScenarios().then(setSaved).catch(() => {})
  }

  const attach = useCallback((id: string) => {
    unwatch.current?.()
    unwatch.current = api.watch(id, {
      onSample: (sample) => setSamples((previous) => [...previous, sample]),
      onPhase: setPhase,
      onFinished: (finished) => {
        setReport(finished)
        setRunId(null)
        refreshLists()
      },
      onFailed: (message) => {
        setError(message)
        setRunId(null)
      },
    })
  }, [])

  async function start() {
    setError(null)
    setReport(null)
    setSamples([])
    setTab('run')
    try {
      const started = await api.start(scenarioId, scenario, broker, tls.enabled ? tls : undefined)
      setRunId(started.id)
      if (started.rewritten) {
        setError(null)
      }
      attach(started.id)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  async function stop() {
    if (runId) {
      await api.stop(runId).catch(() => {})
    }
  }

  const queueTypes = probe?.capabilities.queueTypes ?? ALL_TYPES
  const runnable = check.problems.length === 0 && !runId

  const brokerState = useMemo(() => {
    if (!probe) return { state: 'bad', text: 'no broker' }
    if (!probe.amqp.reachable) return { state: 'bad', text: 'broker unreachable' }
    if (probe.amqp.rewritten) {
      return { state: 'rewritten', text: hostOf(probe.amqp.url) }
    }
    return { state: 'ok', text: hostOf(probe.amqp.url) }
  }, [probe])

  if (!connected) {
    return (
      <Connect
        broker={broker}
        management={management}
        tls={tls}
        onBrokerChange={setBroker}
        onManagementChange={setManagement}
        onTlsChange={setTls}
        onConnected={(found) => {
          setProbe(found)
          // Whatever answered is what the run will use, so the studio keeps the
          // resolved URL rather than the one that was typed.
          if (found.amqp.url) setBroker(found.amqp.url)
          setConnected(true)
        }}
      />
    )
  }

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <img src="/acemq-logo.png" alt="AceMQ" />
          <small>workloads studio</small>
        </div>

        <nav className="tabs">
          {(['design', 'run', 'presets', 'history'] as Tab[]).map((name) => (
            <button
              key={name}
              className="tab"
              data-active={tab === name}
              onClick={() => setTab(name)}
            >
              {name}
            </button>
          ))}
        </nav>

        <div className="spacer" />

        <div className="broker">
          <input
            value={broker}
            spellCheck={false}
            title="The AMQP URL to run against"
            onChange={(e) => setBroker(e.target.value)}
            onBlur={() => api.probe({ broker, management, tls }).then(setProbe).catch(() => {})}
          />
          {/* The management API is a separate port and often closed. Without it
              the studio cannot tell which queue types the broker honours, and
              cannot import a topology -- so it is worth a box rather than a
              setting somebody has to find. */}
          <input
            className="secondary"
            value={management}
            spellCheck={false}
            placeholder="management URL"
            title="The management API, for queue types and importing a topology"
            onChange={(e) => setManagement(e.target.value)}
            onBlur={() => api.probe({ broker, management, tls }).then(setProbe).catch(() => {})}
          />
          <button
            className="chip as-button"
            data-state={runId ? 'live' : brokerState.state}
            disabled={runId != null}
            title={runId ? 'a run is going' : 'change broker'}
            onClick={() => setConnected(false)}
          >
            <span className="dot" />
            {runId ? 'running' : brokerState.text}
          </button>
        </div>

        <button className="primary" disabled={!runnable} onClick={start}>
          Run
        </button>
        {runId && (
          <button className="danger" onClick={stop}>
            Stop
          </button>
        )}
      </header>

      {probe?.amqp.rewritten && (
        <div className="banner" data-tone="info">
          {probe.amqp.explanation}. The run will use <code>{probe.amqp.url}</code>.
        </div>
      )}
      {probe && !probe.amqp.reachable && (
        <div className="banner" data-tone="bad">
          {probe.amqp.explanation}
        </div>
      )}

      {tab === 'design' && (
        <div className="body">
          <div style={{ display: 'grid', gridTemplateRows: 'auto 1fr', overflow: 'hidden' }}>
            <div className="toolbar">
              <span className="title">{scenario.name}</span>
              <button onClick={() => addExchange(scenario, setScenario, setSelection)}>
                + exchange
              </button>
              <button onClick={() => addQueue(scenario, setScenario, setSelection)}>+ queue</button>
              <button onClick={() => addProducer(scenario, setScenario, setSelection)}>
                + producer
              </button>
              <div className="spacer" />
              <button
                onClick={async () => {
                  const { id } = await api.saveScenario(scenarioId, scenario)
                  setScenarioId(id)
                  refreshLists()
                }}
              >
                Save
              </button>
              <button className="ghost" onClick={() => api.download(scenario, 'json')}>
                Export JSON
              </button>
              <button className="ghost" onClick={() => api.download(scenario, 'yaml')}>
                YAML
              </button>
              <button
                className="ghost"
                title="Read a topology off the broker and edit it here"
                onClick={async () => {
                  try {
                    const imported = await api.importTopology({ broker, management })
                    setScenario(imported)
                    setScenarioId(null)
                    setSelection(null)
                  } catch (e) {
                    setError((e as Error).message)
                  }
                }}
              >
                Import from broker
              </button>
            </div>

            {check.problems.length > 0 && (
              <div className="banner" data-tone="bad">
                {check.problems.join(' · ')}
              </div>
            )}
            {check.problems.length === 0 && check.warnings.length > 0 && (
              <div className="banner" data-tone="warn">
                {check.warnings.join(' · ')}
              </div>
            )}

            <div className="canvas">
              <Canvas
                scenario={scenario}
                sample={samples[samples.length - 1] ?? null}
                selection={selection}
                onSelect={setSelection}
                onBind={(exchange, queue) =>
                  setScenario({
                    ...scenario,
                    queues: scenario.queues?.map((q) =>
                      q.name === queue
                        ? {
                            ...q,
                            bindings: [...(q.bindings ?? []), { exchange, routingKey: '#' }],
                          }
                        : q),
                  })
                }
              />
            </div>
          </div>

          <Inspector
            scenario={scenario}
            selection={selection}
            queueTypes={queueTypes}
            onChange={setScenario}
            onSelect={setSelection}
          />
        </div>
      )}

      {tab === 'run' && (
        <div className="body wide">
          {samples.length === 0 && !report && !error ? (
            <div className="empty">
              <div>
                <h2>Nothing is running</h2>
                <p>Design a scenario, or take one of the presets, and press Run.</p>
              </div>
            </div>
          ) : (
            <LiveView
              samples={samples}
              phase={phase}
              report={report}
              error={error}
              running={runId != null}
              onStop={stop}
            />
          )}
        </div>
      )}

      {tab === 'presets' && (
        <div className="body wide">
          <div className="cards">
            {presets.map((preset) => (
              <div
                key={preset.id}
                className="card"
                onClick={() => {
                  setScenario(preset.scenario)
                  setScenarioId(null)
                  setSelection(null)
                  setTab('design')
                }}
              >
                <h3>{preset.title}</h3>
                <p>{preset.question}</p>
                <div className="shape">
                  <span className="pill">{preset.scenario.queues?.length ?? 0} queues</span>
                  <span className="pill">{preset.scenario.producers?.length ?? 0} producers</span>
                  <span className="pill">{preset.scenario.runFor}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {tab === 'history' && (
        <div className="body wide">
          <div style={{ padding: 18, overflowY: 'auto' }}>
            {saved.length > 0 && (
              <div className="panel" style={{ marginBottom: 16 }}>
                <h4>Saved scenarios</h4>
                <div className="scrolls">
                <table>
                  <tbody>
                    {saved.map((entry) => (
                      <tr key={entry.id}>
                        <td className="mono">{entry.name}</td>
                        <td style={{ color: 'var(--text-faint)' }}>{entry.description}</td>
                        <td style={{ width: 1 }}>
                          <button
                            className="ghost"
                            onClick={async () => {
                              setScenario(await api.loadScenario(entry.id))
                              setScenarioId(entry.id)
                              setTab('design')
                            }}
                          >
                            Open
                          </button>
                        </td>
                        <td style={{ width: 1 }}>
                          <button
                            className="ghost"
                            onClick={async () => {
                              await api.deleteScenario(entry.id)
                              refreshLists()
                            }}
                          >
                            Delete
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </div>
              </div>
            )}

            <div className="panel">
              <h4>Runs</h4>
              <div className="scrolls">
              <table>
                <thead>
                  <tr>
                    <th>Scenario</th>
                    <th>Broker</th>
                    <th>Started</th>
                    <th>Result</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {runs.map((run) => (
                    <tr key={run.id}>
                      <td className="mono">{run.scenarioName}</td>
                      <td className="mono" style={{ color: 'var(--text-faint)' }}>{run.broker}</td>
                      <td>{new Date(run.startedAt).toLocaleString()}</td>
                      <td style={{ color: verdictColour(run.verdict ?? run.status) }}>
                        {run.verdict ?? run.status}
                      </td>
                      <td style={{ width: 1 }}>
                        {run.status === 'finished' && (
                          <button
                            className="ghost"
                            onClick={async () => {
                              const [loaded, taken] = await Promise.all([
                                api.report(run.id),
                                api.samples(run.id),
                              ])
                              setReport(loaded)
                              setSamples(taken)
                              setError(null)
                              setTab('run')
                            }}
                          >
                            Open
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function addExchange(
  scenario: Scenario,
  setScenario: (s: Scenario) => void,
  setSelection: (s: Selection) => void,
) {
  const name = uniqueName('exchange', scenario.exchanges?.map((x) => x.name) ?? [])
  setScenario({ ...scenario, exchanges: [...(scenario.exchanges ?? []), { name, type: 'topic' }] })
  setSelection({ kind: 'exchange', name })
}

function addQueue(
  scenario: Scenario,
  setScenario: (s: Scenario) => void,
  setSelection: (s: Selection) => void,
) {
  const name = uniqueName('queue', scenario.queues?.map((q) => q.name) ?? [])
  setScenario({
    ...scenario,
    queues: [
      ...(scenario.queues ?? []),
      { name, type: 'classic', consumers: { concurrency: 1, prefetch: 100 } },
    ],
  })
  setSelection({ kind: 'queue', name })
}

function addProducer(
  scenario: Scenario,
  setScenario: (s: Scenario) => void,
  setSelection: (s: Selection) => void,
) {
  const name = uniqueName('producer', scenario.producers?.map((p) => p.name) ?? [])
  setScenario({
    ...scenario,
    producers: [
      ...(scenario.producers ?? []),
      {
        name,
        exchange: scenario.exchanges?.[0]?.name ?? '',
        routingKeys: ['k'],
        rate: 1000,
        messageSize: 1024,
      },
    ],
  })
  setSelection({ kind: 'producer', name })
}

function uniqueName(prefix: string, taken: string[]): string {
  let index = taken.length + 1
  while (taken.includes(`${prefix}-${index}`)) index += 1
  return `${prefix}-${index}`
}

function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

function verdictColour(verdict: string): string | undefined {
  switch (verdict) {
    case 'passed':
      return 'var(--flow)'
    case 'failed':
      return 'var(--fail)'
    case 'invalid':
      return 'var(--invalid)'
    case 'running':
      return 'var(--in)'
    default:
      return undefined
  }
}
