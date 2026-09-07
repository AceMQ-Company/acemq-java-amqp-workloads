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

import type { ComparisonRow, RunComparison } from '../types'

/**
 * Two runs, side by side.
 *
 * The direction is carried by the colour and the word, never by the sign: a
 * latency that went up is worse and a rate that went up is better, and a table
 * showing both as "+12%" makes the reader work out which is which at the moment
 * they are least likely to.
 */
export function Comparison({ comparison }: { comparison: RunComparison }) {
  const rows = comparison.rows
  const changed = rows.filter((row) => row.verdict === 'better' || row.verdict === 'worse')

  return (
    <div className="panel" style={{ marginBottom: 14 }}>
      <h4>
        {comparison.a?.scenarioName ?? 'run A'} → {comparison.b?.scenarioName ?? 'run B'}
      </h4>
      <p className="hint" style={{ margin: '0 0 10px', color: 'var(--text-faint)', fontSize: 12 }}>
        {comparison.a && comparison.b && (
          <>
            {new Date(comparison.a.startedAt).toLocaleString()} against{' '}
            {new Date(comparison.b.startedAt).toLocaleString()}.{' '}
          </>
        )}
        {changed.length === 0
          ? 'Nothing moved by more than 5%, which on ordinary hardware means these are the same run.'
          : `${changed.length} of ${rows.length} measurements moved by more than 5%.`}
      </p>

      <div className="scrolls">
        <table>
          <thead>
            <tr>
              <th>Node</th>
              <th>Measurement</th>
              <th>Before</th>
              <th>After</th>
              <th>Change</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={index}>
                <td className="mono">{row.node}</td>
                <td>{row.metric}</td>
                <td className="mono">{format(row.a, row.unit)}</td>
                <td className="mono">{format(row.b, row.unit)}</td>
                <td className="mono" style={{ color: colour(row.verdict) }}>
                  {label(row)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function format(value: number | null, unit: string): string {
  if (value == null) {
    return '—'
  }
  if (unit === 'ms') {
    return `${value.toFixed(1)}ms`
  }
  return Math.round(value).toLocaleString()
}

function label(row: ComparisonRow): string {
  if (row.verdict === 'missing') {
    return row.a == null ? 'only after' : 'only before'
  }
  if (row.change == null) {
    return row.verdict === 'same' ? 'same' : row.verdict
  }
  return `${size(row.change)} · ${row.verdict}`
}

/**
 * How big the change was, in whichever way reads.
 *
 * A percentage stops carrying meaning somewhere past a few hundred: "+294854.2%"
 * is a p50 that went from 0.6ms to 1.8 seconds, and nobody reads that number —
 * they read the sign and move on. Past ten times, a multiplier says the same
 * thing in a form somebody can hold in their head.
 */
function size(change: number): string {
  if (Math.abs(change) >= 10) {
    return `×${(1 + change).toFixed(change > 100 ? 0 : 1)}`
  }
  return `${change > 0 ? '+' : ''}${(change * 100).toFixed(1)}%`
}

function colour(verdict: string): string | undefined {
  switch (verdict) {
    case 'better':
      return 'var(--flow)'
    case 'worse':
      return 'var(--fail)'
    case 'missing':
      return 'var(--warn)'
    default:
      return 'var(--text-faint)'
  }
}
