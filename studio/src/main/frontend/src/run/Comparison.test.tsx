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

import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Comparison } from './Comparison'
import type { ComparisonRow, RunComparison } from '../types'

/**
 * The comparison table.
 *
 * The back end decides which way is better and this decides how to say it, so
 * what is checked here is the saying: that a reader sees the direction rather
 * than a signed number they have to interpret, and that a change too large to
 * be a percentage is not printed as one.
 */
describe('the comparison table', () => {
  it('says better and worse rather than leaving the sign to be read', () => {
    render(<Comparison comparison={of(
      row({ node: 'orders.q', metric: 'p99', a: 10, b: 14, change: 0.4, verdict: 'worse' }),
      row({ node: 'orders.q', metric: 'consumed/s', a: 1000, b: 1400, change: 0.4,
            verdict: 'better', unit: 'per second', higherIsBetter: true }),
    )} />)

    // The same +40% twice, and the two rows say opposite things.
    expect(cell('p99')).toHaveTextContent('+40.0% · worse')
    expect(cell('consumed/s')).toHaveTextContent('+40.0% · better')
  })

  it('prints a change too large to be a percentage as a multiplier', () => {
    render(<Comparison comparison={of(
      row({ node: 'q', metric: 'p50', a: 0.6, b: 1780.5, change: 2966, verdict: 'worse' }),
    )} />)

    // "+296550.0%" is a p50 that went from under a millisecond to nearly two
    // seconds, and nobody reads that number.
    expect(cell('p50')).toHaveTextContent('×2967 · worse')
    expect(cell('p50')).not.toHaveTextContent('%')
  })

  it('shows a node only one run had as such rather than as a number', () => {
    render(<Comparison comparison={of(
      row({ node: 'removed', metric: 'consumed/s', a: 50, b: null, change: null,
            verdict: 'missing', unit: 'per second', higherIsBetter: true }),
    )} />)

    expect(cell('consumed/s')).toHaveTextContent('only before')
    expect(cell('consumed/s')).toHaveTextContent('—')
  })

  it('says plainly when two runs are the same run', () => {
    render(<Comparison comparison={of(
      row({ node: 'q', metric: 'p99', a: 10, b: 10.2, change: 0.02, verdict: 'same' }),
    )} />)

    expect(screen.getByText(/Nothing moved by more than 5%/)).toBeInTheDocument()
  })

  it('counts what actually moved', () => {
    render(<Comparison comparison={of(
      row({ node: 'q', metric: 'p99', a: 10, b: 14, change: 0.4, verdict: 'worse' }),
      row({ node: 'q', metric: 'p50', a: 1, b: 1, change: 0, verdict: 'same' }),
      row({ node: 'q', metric: 'p99.9', a: 20, b: 40, change: 1, verdict: 'worse' }),
    )} />)

    expect(screen.getByText(/2 of 3 measurements moved/)).toBeInTheDocument()
  })

  it('shows latency in milliseconds and counts as counts', () => {
    render(<Comparison comparison={of(
      row({ node: 'q', metric: 'p99', a: 1.25, b: 2.5, change: 1, verdict: 'worse' }),
      row({ node: 'q', metric: 'consumed/s', a: 1234.7, b: 2000, change: 0.62,
            verdict: 'better', unit: 'per second', higherIsBetter: true }),
    )} />)

    expect(cell('p99')).toHaveTextContent('1.3ms')
    expect(cell('consumed/s')).toHaveTextContent('1,235')
  })
})

/** @param metric which row to read */
function cell(metric: string): HTMLElement {
  const row = screen.getAllByRole('row').find((candidate) =>
    within(candidate).queryByText(metric) != null)
  if (!row) {
    throw new Error(`no row for ${metric}`)
  }
  return row
}

function row(over: Partial<ComparisonRow>): ComparisonRow {
  return {
    node: 'q',
    metric: 'p99',
    a: 1,
    b: 1,
    unit: 'ms',
    higherIsBetter: false,
    change: 0,
    verdict: 'same',
    ...over,
  }
}

function of(...rows: ComparisonRow[]): RunComparison {
  return {
    a: summary('before', '2026-09-01T10:00:00Z'),
    b: summary('after', '2026-09-07T10:00:00Z'),
    rows,
  }
}

function summary(name: string, at: string) {
  return {
    id: name,
    scenarioId: null,
    scenarioName: name,
    broker: 'amqp://guest:***@localhost:5672',
    startedAt: at,
    finishedAt: at,
    status: 'finished' as const,
    verdict: 'passed',
    error: null,
  }
}
