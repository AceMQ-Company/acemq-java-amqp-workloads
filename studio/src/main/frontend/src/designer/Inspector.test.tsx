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

import { useState } from 'react'

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { Inspector } from './Inspector'
import type { Queue, QueueTypeInfo, Scenario } from '../types'

/**
 * The inspector, which is where a scenario is actually written.
 *
 * Every one of these is a shape that has to survive being typed and then
 * exported: the file is the contract with the command line, and a field that
 * looks right on screen and leaves the wrong thing in the JSON is the worst
 * kind of bug this application can have — it fails later, in a pipeline,
 * against a broker.
 */
describe('the inspector', () => {
  it('keeps a queue argument as a number when it reads as one', async () => {
    const { scenario } = await editQueue(async () => {
      await userEvent.click(screen.getByRole('button', { name: '+ argument' }))
      const value = screen.getAllByRole('textbox')
        .find((box) => (box as HTMLInputElement).value === '')!
      await userEvent.type(value, '5000')
    })

    // A broker refuses x-max-length: "5000" and accepts 5000. Everything typed
    // into a text box is a string, so this conversion is the whole point.
    expect(scenario.queues![0].arguments).toEqual({ 'x-max-length': 5000 })
    expect(typeof scenario.queues![0].arguments!['x-max-length']).toBe('number')
  })

  it('keeps an argument that is not a number as text', async () => {
    const { scenario } = await editQueue(async () => {
      await userEvent.click(screen.getByRole('button', { name: '+ argument' }))
      const value = screen.getAllByRole('textbox')
        .find((box) => (box as HTMLInputElement).value === '')!
      await userEvent.type(value, 'reject-publish')
    })

    expect(scenario.queues![0].arguments).toEqual({ 'x-max-length': 'reject-publish' })
  })

  it('removes a binding when asked, and leaves the others', async () => {
    const { scenario } = await editQueue(
      async () => userEvent.click(screen.getAllByRole('button', { name: 'Unbind' })[0]),
      {
        bindings: [
          { exchange: 'bench', routingKey: 'first' },
          { exchange: 'bench', routingKey: 'second' },
        ],
      })

    expect(scenario.queues![0].bindings).toEqual([{ exchange: 'bench', routingKey: 'second' }])
  })

  it('changes a routing key without touching the exchange', async () => {
    const { scenario } = await editQueue(async () => {
      const key = screen.getByPlaceholderText('order.*')
      await userEvent.clear(key)
      await userEvent.type(key, 'order.created')
    })

    expect(scenario.queues![0].bindings).toEqual([
      { exchange: 'bench', routingKey: 'order.created' },
    ])
  })

  // A binding pointing at an exchange somebody has renamed is the mistake this
  // designer makes most, and hiding it is how it survives to the run.
  it('keeps a binding to an exchange that no longer exists visible', async () => {
    render(
      <Inspector
        scenario={{ ...aScenario(), queues: [queue({
          bindings: [{ exchange: 'renamed-away', routingKey: '#' }],
        })] }}
        selection={{ kind: 'queue', name: 'bench.queue' }}
        queueTypes={types}
        onChange={vi.fn()}
        onSelect={vi.fn()}
      />)

    expect(screen.getByText('renamed-away (no such exchange)')).toBeInTheDocument()
  })

  it('writes an objective in the shape the file uses', async () => {
    const { scenario } = await editQueue(async () => {
      await userEvent.type(screen.getByPlaceholderText('50ms'), '75ms')
      await userEvent.click(
        screen.getByLabelText(/Must not be deeper at the end/))
    })

    expect(scenario.queues![0].expect).toEqual({ p99Below: '75ms', noBacklog: true })
  })

  // What is not asked for is not written: an empty `expect` block in an
  // exported file is a gate that looks set and checks nothing.
  it('drops the objective entirely when the last one is cleared', async () => {
    const { scenario } = await editQueue(
      async () => userEvent.clear(screen.getByPlaceholderText('50ms')),
      { expect: { p99Below: '75ms' } })

    expect(scenario.queues![0].expect).toBeUndefined()
  })

  it('offers only the queue types the broker honours, and says why', () => {
    render(
      <Inspector
        scenario={aScenario()}
        selection={{ kind: 'queue', name: 'bench.queue' }}
        queueTypes={[
          { id: 'classic', label: 'Classic', description: 'One node', supported: true, whyNot: null },
          { id: 'stream', label: 'Stream', description: 'A log', supported: false,
            whyNot: 'this broker is older than streams' },
        ]}
        onChange={vi.fn()}
        onSelect={vi.fn()}
      />)

    expect(screen.getByRole('radio', { name: /Classic/ })).toBeEnabled()
    const stream = screen.getByRole('radio', { name: /Stream/ })
    expect(stream).toBeDisabled()
    // Disabled and still saying why, rather than hidden: somebody who knows the
    // option exists would otherwise think the studio was broken.
    expect(screen.getByText('this broker is older than streams')).toBeInTheDocument()
  })
})

const types: QueueTypeInfo[] = [
  { id: 'classic', label: 'Classic', description: 'One node', supported: true, whyNot: null },
  { id: 'quorum', label: 'Quorum', description: 'Replicated', supported: true, whyNot: null },
]

function queue(over: Partial<Queue> = {}): Queue {
  return {
    name: 'bench.queue',
    type: 'classic',
    bindings: [{ exchange: 'bench', routingKey: '#' }],
    consumers: { concurrency: 2, prefetch: 100 },
    ...over,
  }
}

function aScenario(): Scenario {
  return {
    name: 'test',
    exchanges: [{ name: 'bench', type: 'topic' }],
    queues: [queue()],
    producers: [],
  }
}

/**
 * Renders the inspector on a queue, does something to it, and hands back
 * whatever the scenario became.
 *
 * <p>The inspector is controlled: it draws what it is given and reports edits
 * upwards, so a harness that does not feed the change back renders the second
 * keystroke against the state before the first. Which is what the first version
 * of this did, and it made every typed value one character long.
 *
 * @param edit what to do to it
 * @param over the queue to start from
 */
async function editQueue(edit: () => Promise<void>, over: Partial<Queue> = {}) {
  const seen = { scenario: { ...aScenario(), queues: [queue(over)] } as Scenario }

  function Harness() {
    const [scenario, setScenario] = useState<Scenario>(seen.scenario)
    seen.scenario = scenario
    return (
      <Inspector
        scenario={scenario}
        selection={{ kind: 'queue', name: 'bench.queue' }}
        queueTypes={types}
        onChange={setScenario}
        onSelect={vi.fn()}
      />
    )
  }

  render(<Harness />)
  await edit()
  return { get scenario() { return seen.scenario } }
}
