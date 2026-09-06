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

import { useCallback, useMemo } from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react'

import type { Scenario, ScenarioSample } from '../types'

/**
 * The topology, drawn.
 *
 * Producers on the left, exchanges in the middle, queues on the right, because
 * that is the direction a message travels and a graph that reads left to right
 * needs no legend. Bindings are the edges, and dragging from an exchange to a
 * queue creates one -- which is the whole reason for a canvas rather than a
 * list of forms.
 *
 * While a run is going the edges carrying traffic animate and each node shows
 * its own rate. A topology under load looks different from a topology at rest,
 * and that difference is the thing somebody is watching for.
 */

export type Selection =
  | { kind: 'producer'; name: string }
  | { kind: 'exchange'; name: string }
  | { kind: 'queue'; name: string }
  | null

interface Props {
  scenario: Scenario
  sample: ScenarioSample | null
  selection: Selection
  onSelect: (selection: Selection) => void
  onBind: (exchange: string, queue: string) => void
}

interface NodeData extends Record<string, unknown> {
  label: string
  kind: 'producer' | 'exchange' | 'queue'
  detail: string
  pills: { text: string; tone?: 'flow' | 'in' | 'warn' }[]
  rate?: number
  rateLabel?: string
  off?: boolean
  selected?: boolean
}

function ScenarioNode({ data }: NodeProps) {
  const node = data as NodeData
  return (
    <div className="node" data-selected={node.selected} data-off={node.off}>
      {node.kind !== 'producer' && <Handle type="target" position={Position.Left} />}
      <div className="kind">{node.kind}</div>
      <div className="name">{node.label}</div>
      <div className="meta">
        {node.pills.map((pill) => (
          <span key={pill.text} className="pill" data-tone={pill.tone}>
            {pill.text}
          </span>
        ))}
      </div>
      {node.rate !== undefined && (
        <div className="live">
          {Math.round(node.rate).toLocaleString()}
          <small>{node.rateLabel}</small>
        </div>
      )}
      {node.kind !== 'queue' && <Handle type="source" position={Position.Right} />}
    </div>
  )
}

const nodeTypes = { scenario: ScenarioNode }

export function Canvas({ scenario, sample, selection, onSelect, onBind }: Props) {
  const nodes = useMemo<Node[]>(() => {
    const made: Node[] = []
    const layout = (scenario.ui?.layout ?? {}) as Record<string, { x: number; y: number }>

    scenario.producers?.forEach((producer, index) => {
      const live = sample?.producers.find((p) => p.name === producer.name)
      made.push({
        id: `producer:${producer.name}`,
        type: 'scenario',
        position: layout[`producer:${producer.name}`] ?? { x: 0, y: index * 130 },
        data: {
          kind: 'producer',
          label: producer.name,
          detail: producer.exchange,
          off: producer.enabled === false,
          selected: selection?.kind === 'producer' && selection.name === producer.name,
          pills: [
            { text: producer.rate ? `${producer.rate.toLocaleString()}/s` : 'unthrottled', tone: 'flow' },
            { text: `${producer.messageSize ?? 1024} B` },
            ...(producer.routingKeys?.length && producer.routingKeys[0]
              ? [{ text: producer.routingKeys.join(', ') }]
              : []),
          ],
          rate: live?.publishRate,
          rateLabel: 'published/s',
        } satisfies NodeData,
      })
    })

    scenario.exchanges?.forEach((exchange, index) => {
      made.push({
        id: `exchange:${exchange.name}`,
        type: 'scenario',
        position: layout[`exchange:${exchange.name}`] ?? { x: 300, y: index * 130 },
        data: {
          kind: 'exchange',
          label: exchange.name,
          detail: exchange.type,
          off: exchange.enabled === false,
          selected: selection?.kind === 'exchange' && selection.name === exchange.name,
          pills: [{ text: exchange.type, tone: 'in' }],
        } satisfies NodeData,
      })
    })

    scenario.queues?.forEach((queue, index) => {
      const live = sample?.queues.find((q) => q.name === queue.name)
      const consumers = queue.consumers
      const consuming = consumers?.enabled !== false && (consumers?.concurrency ?? 1) > 0
      made.push({
        id: `queue:${queue.name}`,
        type: 'scenario',
        position: layout[`queue:${queue.name}`] ?? { x: 640, y: index * 130 },
        data: {
          kind: 'queue',
          label: queue.name,
          detail: queue.type ?? 'classic',
          off: queue.enabled === false,
          selected: selection?.kind === 'queue' && selection.name === queue.name,
          pills: [
            { text: queue.type ?? 'classic' },
            consuming
              ? { text: `${consumers?.concurrency ?? 1} consumers`, tone: 'flow' as const }
              : { text: 'no consumers', tone: 'warn' as const },
            ...(live?.depth ? [{ text: `${live.depth.toLocaleString()} waiting`, tone: 'warn' as const }] : []),
          ],
          rate: live?.consumeRate,
          rateLabel: 'consumed/s',
        } satisfies NodeData,
      })
    })

    return made
  }, [scenario, sample, selection])

  const edges = useMemo<Edge[]>(() => {
    const made: Edge[] = []

    scenario.producers?.forEach((producer) => {
      if (!producer.exchange) return
      const live = sample?.producers.find((p) => p.name === producer.name)
      made.push({
        id: `p:${producer.name}`,
        source: `producer:${producer.name}`,
        target: `exchange:${producer.exchange}`,
        animated: (live?.publishRate ?? 0) > 0,
        label: producer.routingKeys?.[0] || undefined,
      })
    })

    scenario.queues?.forEach((queue) => {
      queue.bindings?.forEach((binding, index) => {
        const live = sample?.queues.find((q) => q.name === queue.name)
        made.push({
          id: `b:${queue.name}:${index}`,
          source: `exchange:${binding.exchange}`,
          target: `queue:${queue.name}`,
          animated: (live?.consumeRate ?? 0) > 0,
          label: binding.routingKey || undefined,
        })
      })
    })

    return made
  }, [scenario, sample])

  const onConnect = useCallback(
    (connection: { source?: string | null; target?: string | null }) => {
      // Only one direction means anything: an exchange feeds a queue. Anything
      // else the canvas allows to be dragged is quietly ignored rather than
      // creating a binding the broker would refuse.
      const source = connection.source ?? ''
      const target = connection.target ?? ''
      if (source.startsWith('exchange:') && target.startsWith('queue:')) {
        onBind(source.slice('exchange:'.length), target.slice('queue:'.length))
      }
    },
    [onBind],
  )

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      onConnect={onConnect}
      onNodeClick={(_, node) => {
        const [kind, ...rest] = node.id.split(':')
        onSelect({ kind: kind as 'producer' | 'exchange' | 'queue', name: rest.join(':') })
      }}
      onPaneClick={() => onSelect(null)}
      fitView
      proOptions={{ hideAttribution: true }}
      defaultEdgeOptions={{ type: 'smoothstep' }}
    >
      <Background variant={BackgroundVariant.Dots} gap={22} size={1} color="#1b2027" />
      <Controls showInteractive={false} />
    </ReactFlow>
  )
}
