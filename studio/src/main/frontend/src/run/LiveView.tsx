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

import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import type { Report, ScenarioSample } from '../types'

/**
 * What a run looks like while it is happening.
 *
 * Published against consumed on one chart, because the gap between those two
 * lines is the entire story: while they sit on top of each other the system is
 * keeping up, and the moment they separate is the moment worth seeing. The
 * queue depths go underneath, since a growing depth is the same fact expressed
 * as a consequence.
 */

interface Props {
  samples: ScenarioSample[]
  phase: string
  report: Report | null
  error: string | null
  onStop: () => void
  running: boolean
}

const AXIS = { stroke: '#6b757f', fontSize: 11 }

export function LiveView({ samples, phase, report, error, onStop, running }: Props) {
  const series = samples.map((sample) => {
    const seconds = Math.round(durationSeconds(sample.elapsed))
    const row: Record<string, number | string> = {
      t: seconds,
      published: Math.round(sample.producers.reduce((sum, p) => sum + p.publishRate, 0)),
      consumed: Math.round(sample.queues.reduce((sum, q) => sum + q.consumeRate, 0)),
    }
    sample.queues.forEach((queue) => {
      row[`depth:${queue.name}`] = queue.depth ?? 0
    })
    return row
  })

  const latest = samples[samples.length - 1]
  const queueNames = latest?.queues.map((q) => q.name) ?? []
  const depthColours = ['#3ddc97', '#63b3ff', '#ffb457', '#c77dff', '#ff6b6b']

  return (
    <div style={{ display: 'grid', gridTemplateRows: 'auto auto 1fr', overflow: 'hidden' }}>
      <div className="toolbar">
        <span className="chip" data-state={running ? 'live' : 'ok'}>
          <span className="dot" />
          {running ? phaseLabel(phase) : report ? 'finished' : 'not running'}
        </span>
        {latest && (
          <span className="chip">
            <span className="dot" />
            {Math.round(durationSeconds(latest.elapsed))}s elapsed
          </span>
        )}
        {latest?.blocked && (
          <span className="chip" data-state="bad">
            <span className="dot" />
            the broker is blocking publishers
          </span>
        )}
        <div className="spacer" />
        {running && (
          <button className="danger" onClick={onStop}>
            Stop, and report on what it measured
          </button>
        )}
      </div>

      {error && (
        <div className="banner" data-tone="bad">
          {error}
        </div>
      )}

      {report && <Verdict report={report} />}

      <div className="live-grid">
        <div className="panel" style={{ gridColumn: '1 / -1' }}>
          <h4>Published against consumed</h4>
          <p className="hint" style={{ margin: '0 0 10px', color: 'var(--text-faint)', fontSize: 12 }}>
            While these two lines sit together the system is keeping up. The gap between them is
            the backlog forming.
          </p>
          <ResponsiveContainer width="100%" height={230}>
            <AreaChart data={series}>
              <defs>
                <linearGradient id="published" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#3ddc97" stopOpacity={0.35} />
                  <stop offset="100%" stopColor="#3ddc97" stopOpacity={0} />
                </linearGradient>
                <linearGradient id="consumed" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#63b3ff" stopOpacity={0.3} />
                  <stop offset="100%" stopColor="#63b3ff" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid stroke="#1b2027" vertical={false} />
              <XAxis dataKey="t" tick={AXIS} tickLine={false} axisLine={false}
                     tickFormatter={(v) => `${v}s`} />
              <YAxis tick={AXIS} tickLine={false} axisLine={false}
                     tickFormatter={(v) => Intl.NumberFormat('en', { notation: 'compact' }).format(v)} />
              <Tooltip contentStyle={TOOLTIP} labelFormatter={(v) => `${v}s in`} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Area type="monotone" dataKey="published" name="published/s" stroke="#3ddc97"
                    strokeWidth={2} fill="url(#published)" isAnimationActive={false} />
              <Area type="monotone" dataKey="consumed" name="consumed/s" stroke="#63b3ff"
                    strokeWidth={2} fill="url(#consumed)" isAnimationActive={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        {queueNames.length > 0 && (
          <div className="panel" style={{ gridColumn: '1 / -1' }}>
            <h4>What is waiting, queue by queue</h4>
            <ResponsiveContainer width="100%" height={190}>
              <LineChart data={series}>
                <CartesianGrid stroke="#1b2027" vertical={false} />
                <XAxis dataKey="t" tick={AXIS} tickLine={false} axisLine={false}
                       tickFormatter={(v) => `${v}s`} />
                <YAxis tick={AXIS} tickLine={false} axisLine={false}
                       tickFormatter={(v) => Intl.NumberFormat('en', { notation: 'compact' }).format(v)} />
                <Tooltip contentStyle={TOOLTIP} labelFormatter={(v) => `${v}s in`} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                {queueNames.map((name, index) => (
                  <Line
                    key={name}
                    type="monotone"
                    dataKey={`depth:${name}`}
                    name={name}
                    stroke={depthColours[index % depthColours.length]}
                    strokeWidth={2}
                    dot={false}
                    isAnimationActive={false}
                  />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}

        {latest?.producers.map((producer) => (
          <div className="panel" key={producer.name}>
            <h4>{producer.name}</h4>
            <div className="big">
              {Math.round(producer.publishRate).toLocaleString()}
              <small>published/s</small>
            </div>
            <div style={{ marginTop: 8, color: 'var(--text-faint)', fontSize: 12 }}>
              {producer.published.toLocaleString()} sent
              {producer.failed > 0 && (
                <span style={{ color: 'var(--fail)' }}> · {producer.failed} failed</span>
              )}
            </div>
          </div>
        ))}

        {latest?.queues.map((queue) => (
          <div className="panel" key={queue.name}>
            <h4>{queue.name}</h4>
            <div className="big" style={{ color: queue.consuming ? undefined : 'var(--warn)' }}>
              {Math.round(queue.consumeRate).toLocaleString()}
              <small>consumed/s</small>
            </div>
            <div style={{ marginTop: 8, color: 'var(--text-faint)', fontSize: 12 }}>
              {queue.consuming ? `${queue.consumed.toLocaleString()} handled` : 'nothing consuming'}
              {queue.depth != null && ` · ${queue.depth.toLocaleString()} waiting`}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function Verdict({ report }: { report: Report }) {
  return (
    <div style={{ padding: '14px 16px 0' }}>
      <div className="verdict" data-verdict={report.verdict}>
        <h2>
          {report.verdict === 'invalid'
            ? 'Invalid — this run did not measure what it was asked to'
            : report.verdict === 'passed'
              ? 'Passed'
              : 'Failed'}
        </h2>
        <div style={{ marginTop: 6, color: 'var(--text-dim)', fontSize: 13 }}>
          {Math.round(report.durationMs / 1000)}s measured ·{' '}
          {report.totalPublished.toLocaleString()} published ·{' '}
          {report.totalConsumed.toLocaleString()} consumed
          {report.stoppedEarly && ' · stopped early'}
        </div>

        {report.findings.map((finding) => (
          <div className="finding" key={finding.rule} data-severity={finding.severity}>
            <code>
              [{finding.severity}] {finding.rule}
            </code>
            <p className="observation">{finding.observation}</p>
            <p>{finding.implication}</p>
          </div>
        ))}
      </div>

      <div className="panel" style={{ marginTop: 14 }}>
        <h4>Queues</h4>
        <div className="scrolls">
        <table>
          <thead>
            <tr>
              <th>Queue</th>
              <th>Type</th>
              <th>Consumers</th>
              <th>Consumed/s</th>
              <th>p50</th>
              <th>p99</th>
              <th>p99.9</th>
              <th>Waiting at the end</th>
            </tr>
          </thead>
          <tbody>
            {report.queues.map((queue) => (
              <tr key={queue.name}>
                <td className="mono">{queue.name}</td>
                <td>{queue.type}</td>
                <td>{queue.consumers}</td>
                <td>{Math.round(queue.consumeRate).toLocaleString()}</td>
                <td>{queue.endToEnd.count ? `${queue.endToEnd.p50.toFixed(1)}ms` : '—'}</td>
                <td>{queue.endToEnd.count ? `${queue.endToEnd.p99.toFixed(1)}ms` : '—'}</td>
                <td>{queue.endToEnd.count ? `${queue.endToEnd.p999.toFixed(1)}ms` : '—'}</td>
                <td style={{ color: queue.grew ? 'var(--warn)' : undefined }}>
                  {queue.depthAtEnd?.toLocaleString() ?? '—'}
                  {queue.grew && ' ↑'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </div>

      <div className="panel" style={{ marginTop: 14 }}>
        <h4>Producers</h4>
        <div className="scrolls">
        <table>
          <thead>
            <tr>
              <th>Producer</th>
              <th>Offered</th>
              <th>Achieved</th>
              <th>Failed</th>
              <th>Send lag p99</th>
              <th>Confirm p99</th>
            </tr>
          </thead>
          <tbody>
            {report.producers.map((producer) => (
              <tr key={producer.name}>
                <td className="mono">{producer.name}</td>
                <td>{producer.offeredRate ? producer.offeredRate.toLocaleString() : 'unthrottled'}</td>
                <td>{Math.round(producer.achievedRate).toLocaleString()}</td>
                <td style={{ color: producer.failed ? 'var(--fail)' : undefined }}>
                  {producer.failed.toLocaleString()}
                </td>
                <td>{producer.sendLag.count ? `${producer.sendLag.p99.toFixed(1)}ms` : '—'}</td>
                <td>
                  {producer.publishLatency.count
                    ? `${producer.publishLatency.p99.toFixed(1)}ms`
                    : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </div>
    </div>
  )
}

const TOOLTIP = {
  background: '#121519',
  border: '1px solid #2f363d',
  borderRadius: 8,
  fontSize: 12,
}

function phaseLabel(phase: string) {
  switch (phase) {
    case 'WARMUP':
      return 'warming up — these numbers are thrown away'
    case 'MEASURING':
      return 'measuring'
    case 'DRAINING':
      return 'draining what is in flight'
    default:
      return 'starting'
  }
}

/** The engine sends ISO-8601 durations. Only seconds are wanted here. */
function durationSeconds(iso: string): number {
  const match = /PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?/.exec(iso)
  if (!match) return 0
  return Number(match[1] ?? 0) * 3600 + Number(match[2] ?? 0) * 60 + Number(match[3] ?? 0)
}
