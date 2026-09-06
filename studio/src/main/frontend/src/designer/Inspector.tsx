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

import type { Expect, Producer, Queue, QueueTypeId, QueueTypeInfo, Scenario } from '../types'
import type { Selection } from './Canvas'

/**
 * Everything about the selected thing, and nothing about anything else.
 *
 * The queue type is radio cards rather than a dropdown on purpose: the choice
 * is a trade-off, not a name, and a dropdown hides what each option costs at
 * the moment somebody is deciding. An option the broker will not honour stays
 * visible and says why -- hiding it makes the studio look broken to anybody who
 * knows the option exists.
 */

/**
 * An expectation with one field changed, or none at all.
 *
 * An object of nothing but undefined is dropped rather than written, so a
 * scenario that asks for nothing exports as a file without an empty `expect`
 * block in it.
 */
function withExpect(current: Expect | undefined, changes: Partial<Expect>): Expect | undefined {
  const merged = { ...current, ...changes }
  return Object.values(merged).some((value) => value !== undefined && value !== '')
    ? merged
    : undefined
}

/** A number field that is empty rather than zero when it is not being asked for. */
function number(value: string): number | undefined {
  return value === '' ? undefined : Number(value)
}

interface Props {
  scenario: Scenario
  selection: Selection
  queueTypes: QueueTypeInfo[]
  onChange: (scenario: Scenario) => void
  onSelect: (selection: Selection) => void
}

export function Inspector({ scenario, selection, queueTypes, onChange, onSelect }: Props) {
  if (!selection) {
    return (
      <aside className="inspector">
        <h3>The scenario</h3>
        <div className="field">
          <label>Name</label>
          <input
            value={scenario.name}
            onChange={(e) => onChange({ ...scenario, name: e.target.value })}
          />
        </div>
        <div className="field">
          <label>What it is for</label>
          <textarea
            rows={3}
            style={{ width: '100%', resize: 'vertical' }}
            value={scenario.description ?? ''}
            placeholder="The question this run answers"
            onChange={(e) => onChange({ ...scenario, description: e.target.value })}
          />
        </div>
        <div className="row">
          <div className="field">
            <label>Warm-up</label>
            <input
              value={scenario.warmup ?? '5s'}
              onChange={(e) => onChange({ ...scenario, warmup: e.target.value })}
            />
          </div>
          <div className="field">
            <label>Measure for</label>
            <input
              value={scenario.runFor ?? '30s'}
              onChange={(e) => onChange({ ...scenario, runFor: e.target.value })}
            />
          </div>
        </div>
        <p className="hint">
          The warm-up runs the whole scenario and throws the numbers away, so class loading, JIT
          and the first collection land there rather than in the p99.
        </p>

        <div className="field">
          <label className="switch">
            <input
              type="checkbox"
              checked={scenario.declare !== false}
              onChange={(e) => onChange({ ...scenario, declare: e.target.checked ? undefined : false })}
            />
            Declare the topology before running
          </label>
        </div>
        <p className="hint">
          Turn this off against a real environment. Declaring there is either refused for
          mismatched arguments or, worse, quietly creates something subtly different from what
          production runs and measures that instead.
        </p>

        <p className="hint">Select something on the canvas to configure it.</p>
      </aside>
    )
  }

  if (selection.kind === 'producer') {
    const producer = scenario.producers?.find((p) => p.name === selection.name)
    if (!producer) return <aside className="inspector" />
    const update = (changes: Partial<Producer>) =>
      onChange({
        ...scenario,
        producers: scenario.producers?.map((p) =>
          p.name === producer.name ? { ...p, ...changes } : p),
      })

    return (
      <aside className="inspector">
        <h3>Producer</h3>
        <div className="field">
          <label>Name</label>
          <input value={producer.name} onChange={(e) => {
            const name = e.target.value
            update({ name })
            onSelect({ kind: 'producer', name })
          }} />
        </div>

        <div className="field">
          <label>Publishes to</label>
          <select
            value={producer.exchange}
            onChange={(e) => update({ exchange: e.target.value })}
          >
            <option value="">(the default exchange, by queue name)</option>
            {scenario.exchanges?.map((exchange) => (
              <option key={exchange.name} value={exchange.name}>{exchange.name}</option>
            ))}
          </select>
        </div>

        <div className="field">
          <label>Routing keys, comma separated</label>
          <input
            value={producer.routingKeys?.join(', ') ?? ''}
            placeholder="order.placed, order.cancelled"
            onChange={(e) => update({
              routingKeys: e.target.value.split(',').map((k) => k.trim()).filter(Boolean),
            })}
          />
        </div>
        <p className="hint">
          Several keys are used in turn, which is what makes a topic exchange behave like one. A
          producer on a single key measures one binding however many the exchange has.
        </p>

        <div className="row">
          <div className="field">
            <label>Rate, messages a second</label>
            <input
              type="number"
              min={0}
              value={producer.rate ?? 1000}
              onChange={(e) => update({ rate: Number(e.target.value) })}
            />
          </div>
          <div className="field">
            <label>Message size, bytes</label>
            <input
              type="number"
              min={1}
              value={producer.messageSize ?? 1024}
              onChange={(e) => update({ messageSize: Number(e.target.value) })}
            />
          </div>
        </div>
        <p className="hint">
          Say the rate and let the studio work out the threads. A rate of 0 is unthrottled: it
          finds the ceiling, and makes the latency meaningless while doing it.
        </p>

        <div className="field">
          <label className="switch">
            <input
              type="checkbox"
              checked={producer.confirms !== false}
              onChange={(e) => update({ confirms: e.target.checked ? undefined : false })}
            />
            Wait for publisher confirms
          </label>
        </div>

        <div className="field">
          <label className="switch">
            <input
              type="checkbox"
              checked={producer.enabled !== false}
              onChange={(e) => update({ enabled: e.target.checked ? undefined : false })}
            />
            Include in the run
          </label>
        </div>

        <h3 style={{ marginTop: 22 }}>What it must prove</h3>
        <div className="row">
          <div className="field">
            <label>At least, a second</label>
            <input
              type="number"
              min={0}
              placeholder="—"
              value={producer.expect?.achievedRateAtLeast ?? ''}
              onChange={(e) => update({
                expect: withExpect(producer.expect, {
                  achievedRateAtLeast: number(e.target.value),
                }),
              })}
            />
          </div>
          <div className="field">
            <label>Within % of the rate</label>
            <input
              type="number"
              min={0}
              max={100}
              placeholder="—"
              value={producer.expect?.withinPercentOfOffered ?? ''}
              onChange={(e) => update({
                expect: withExpect(producer.expect, {
                  withinPercentOfOffered: number(e.target.value),
                }),
              })}
            />
          </div>
        </div>
        <div className="field">
          <label className="switch">
            <input
              type="checkbox"
              checked={producer.expect?.noFailures === true}
              onChange={(e) => update({
                expect: withExpect(producer.expect, {
                  noFailures: e.target.checked ? true : undefined,
                }),
              })}
            />
            Every publish must succeed
          </label>
        </div>
        <p className="hint">
          What is left blank is not checked. Anything set here decides the exit code when this
          scenario is run from a pipeline, so a build can fail on a number rather than on somebody
          reading a chart.
        </p>

        <button
          className="danger"
          onClick={() => {
            onChange({
              ...scenario,
              producers: scenario.producers?.filter((p) => p.name !== producer.name),
            })
            onSelect(null)
          }}
        >
          Remove this producer
        </button>
      </aside>
    )
  }

  if (selection.kind === 'exchange') {
    const exchange = scenario.exchanges?.find((x) => x.name === selection.name)
    if (!exchange) return <aside className="inspector" />
    const update = (changes: Partial<typeof exchange>) =>
      onChange({
        ...scenario,
        exchanges: scenario.exchanges?.map((x) =>
          x.name === exchange.name ? { ...x, ...changes } : x),
      })

    return (
      <aside className="inspector">
        <h3>Exchange</h3>
        <div className="field">
          <label>Name</label>
          <input value={exchange.name} onChange={(e) => {
            const name = e.target.value
            update({ name })
            onSelect({ kind: 'exchange', name })
          }} />
        </div>

        <div className="field">
          <label>Type</label>
          <div className="choices">
            {[
              ['topic', 'Topic', 'Routes on a pattern. The usual choice for events.'],
              ['fanout', 'Fanout', 'Every bound queue gets a copy; the key is ignored.'],
              ['direct', 'Direct', 'Routes on an exact key.'],
              ['headers', 'Headers', 'Routes on headers rather than on the key.'],
            ].map(([id, label, description]) => (
              <label key={id} className="choice" data-selected={exchange.type === id}>
                <input
                  type="radio"
                  name="exchange-type"
                  checked={exchange.type === id}
                  onChange={() => update({ type: id })}
                />
                <span>
                  <strong>{label}</strong>
                  <span>{description}</span>
                </span>
              </label>
            ))}
          </div>
        </div>

        <div className="field">
          <label className="switch">
            <input
              type="checkbox"
              checked={exchange.enabled !== false}
              onChange={(e) => update({ enabled: e.target.checked ? undefined : false })}
            />
            Include in the run
          </label>
        </div>

        <button
          className="danger"
          onClick={() => {
            onChange({
              ...scenario,
              exchanges: scenario.exchanges?.filter((x) => x.name !== exchange.name),
            })
            onSelect(null)
          }}
        >
          Remove this exchange
        </button>
      </aside>
    )
  }

  const queue = scenario.queues?.find((q) => q.name === selection.name)
  if (!queue) return <aside className="inspector" />

  const update = (changes: Partial<Queue>) =>
    onChange({
      ...scenario,
      queues: scenario.queues?.map((q) => (q.name === queue.name ? { ...q, ...changes } : q)),
    })

  const consumers = queue.consumers ?? {}
  const updateConsumers = (changes: Partial<typeof consumers>) =>
    update({ consumers: { ...consumers, ...changes } })

  return (
    <aside className="inspector">
      <h3>Queue</h3>
      <div className="field">
        <label>Name</label>
        <input value={queue.name} onChange={(e) => {
          const name = e.target.value
          update({ name })
          onSelect({ kind: 'queue', name })
        }} />
      </div>

      <div className="field">
        <label>Type</label>
        <div className="choices">
          {queueTypes.map((type) => (
            <label
              key={type.id}
              className="choice"
              data-selected={(queue.type ?? 'classic') === type.id}
              data-disabled={!type.supported}
              title={type.whyNot ?? undefined}
            >
              <input
                type="radio"
                name="queue-type"
                disabled={!type.supported}
                checked={(queue.type ?? 'classic') === type.id}
                onChange={() => update({ type: type.id as QueueTypeId })}
              />
              <span>
                <strong>{type.label}</strong>
                <span>{type.supported ? type.description : type.whyNot}</span>
              </span>
            </label>
          ))}
        </div>
      </div>

      <div className="field">
        <label>Dead-letter exchange</label>
        <select
          value={queue.deadLetterExchange ?? ''}
          onChange={(e) => update({ deadLetterExchange: e.target.value || undefined })}
        >
          <option value="">(none: rejected messages are dropped)</option>
          {scenario.exchanges?.map((exchange) => (
            <option key={exchange.name} value={exchange.name}>{exchange.name}</option>
          ))}
        </select>
      </div>

      <h3 style={{ marginTop: 22 }}>Consumers</h3>
      <div className="row">
        <div className="field">
          <label>How many</label>
          <input
            type="number"
            min={0}
            value={consumers.concurrency ?? 1}
            onChange={(e) => updateConsumers({ concurrency: Number(e.target.value) })}
          />
        </div>
        <div className="field">
          <label>Prefetch</label>
          <input
            type="number"
            min={0}
            value={consumers.prefetch ?? 100}
            onChange={(e) => updateConsumers({ prefetch: Number(e.target.value) })}
          />
        </div>
      </div>

      <div className="row">
        <div className="field">
          <label>Handler takes</label>
          <input
            value={consumers.handlerTime ?? ''}
            placeholder="1ms"
            onChange={(e) => updateConsumers({ handlerTime: e.target.value || undefined })}
          />
        </div>
        <div className="field">
          <label>Fails this often</label>
          <input
            type="number"
            min={0}
            max={1}
            step={0.01}
            value={consumers.failureRate ?? 0}
            onChange={(e) => updateConsumers({ failureRate: Number(e.target.value) || undefined })}
          />
        </div>
      </div>

      <div className="field">
        <label className="switch">
          <input
            type="checkbox"
            checked={consumers.enabled !== false}
            onChange={(e) => updateConsumers({ enabled: e.target.checked ? undefined : false })}
          />
          Consumers running
        </label>
      </div>
      <p className="hint">
        Turning these off is not the same as removing them: the queue keeps filling and the run
        measures what the backlog costs, which is usually the question. The settings stay where
        they are, ready to be switched back on.
      </p>

      <div className="field">
        <label className="switch">
          <input
            type="checkbox"
            checked={queue.enabled !== false}
            onChange={(e) => update({ enabled: e.target.checked ? undefined : false })}
          />
          Include this queue in the run
        </label>
      </div>

      {/* Per queue rather than for the whole run, because the interesting
          property is usually asymmetric: the audit leg may lag as much as it
          likes while the fulfilment leg must not, and one overall p99 averages
          away exactly that distinction. */}
      <h3 style={{ marginTop: 22 }}>What it must prove</h3>
      <div className="row">
        <div className="field">
          <label>p99 under</label>
          <input
            placeholder="50ms"
            value={queue.expect?.p99Below ?? ''}
            onChange={(e) => update({
              expect: withExpect(queue.expect, { p99Below: e.target.value || undefined }),
            })}
          />
        </div>
        <div className="field">
          <label>p99.9 under</label>
          <input
            placeholder="—"
            value={queue.expect?.p999Below ?? ''}
            onChange={(e) => update({
              expect: withExpect(queue.expect, { p999Below: e.target.value || undefined }),
            })}
          />
        </div>
      </div>
      <div className="field">
        <label>Handles at least, a second</label>
        <input
          type="number"
          min={0}
          placeholder="—"
          value={queue.expect?.consumeRateAtLeast ?? ''}
          onChange={(e) => update({
            expect: withExpect(queue.expect, { consumeRateAtLeast: number(e.target.value) }),
          })}
        />
      </div>
      <div className="field">
        <label className="switch">
          <input
            type="checkbox"
            checked={queue.expect?.noBacklog === true}
            onChange={(e) => update({
              expect: withExpect(queue.expect, { noBacklog: e.target.checked ? true : undefined }),
            })}
          />
          Must not be deeper at the end than at the start
        </label>
      </div>
      <p className="hint">
        {(queue.type ?? 'classic') === 'stream'
          ? 'A stream keeps what it has served, so its depth is the length of the log rather'
            + ' than a backlog: this one is not checked for a stream.'
          : 'What is left blank is not checked. Anything set here decides the exit code when this'
            + ' scenario is run from a pipeline.'}
      </p>

      <button
        className="danger"
        onClick={() => {
          onChange({
            ...scenario,
            queues: scenario.queues?.filter((q) => q.name !== queue.name),
          })
          onSelect(null)
        }}
      >
        Remove this queue
      </button>
    </aside>
  )
}
