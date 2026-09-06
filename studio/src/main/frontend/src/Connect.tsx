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

import { useEffect, useState } from 'react'

import { api } from './api'
import type { BrokerProbe } from './types'

/**
 * The first screen: find a broker, or go no further.
 *
 * Everything in the studio needs one. A designer that lets somebody draw a
 * topology, press Run and only then learn that nothing was listening has wasted
 * their time and taught them nothing — so the connection is established first,
 * and the rest of the application is not reachable until it is.
 *
 * It probes on its own as soon as it opens, because the common case is a broker
 * already running on the machine and the right amount of work for that case is
 * none. What it finds is stated plainly, including when the URL had to be
 * changed to reach it.
 */

interface Props {
  broker: string
  management: string
  onBrokerChange: (url: string) => void
  onManagementChange: (url: string) => void
  onConnected: (probe: BrokerProbe) => void
}

export function Connect({
  broker,
  management,
  onBrokerChange,
  onManagementChange,
  onConnected,
}: Props) {
  const [probe, setProbe] = useState<BrokerProbe | null>(null)
  const [looking, setLooking] = useState(true)
  const [failure, setFailure] = useState<string | null>(null)

  async function look() {
    setLooking(true)
    setFailure(null)
    try {
      const found = await api.probe({ broker, management })
      setProbe(found)
    } catch (e) {
      setProbe(null)
      setFailure((e as Error).message)
    } finally {
      setLooking(false)
    }
  }

  // Once, on open. A broker is usually already running, and the right amount of
  // work for that case is none.
  useEffect(() => {
    look()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const reachable = probe?.amqp.reachable ?? false
  const capabilities = probe?.capabilities
  const supported = capabilities?.queueTypes.filter((t) => t.supported) ?? []

  return (
    <div className="gate">
      <div className="gate-card">
        <div className="gate-brand">
          <img src="/acemq-logo.png" alt="AceMQ" />
          <span>workloads studio</span>
        </div>

        <h1>Connect to a broker</h1>
        <p className="gate-lead">
          Everything here runs against a real broker — the designer checks its queue types, and a
          scenario is only worth drawing if it can be run. Point the studio at one to begin.
        </p>

        <div className="field">
          <label>Broker (AMQP)</label>
          <input
            value={broker}
            spellCheck={false}
            autoFocus
            onChange={(e) => onBrokerChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') look()
            }}
          />
        </div>

        <div className="field">
          <label>
            Management API <span className="optional">— optional, and worth having</span>
          </label>
          <input
            value={management}
            spellCheck={false}
            placeholder="http://localhost:15672"
            onChange={(e) => onManagementChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') look()
            }}
          />
          <p className="hint">
            A separate port, often closed. Without it the studio cannot tell which queue types this
            broker honours, and cannot import an existing topology.
          </p>
        </div>

        {looking && (
          <div className="gate-status" data-state="looking">
            <span className="dot" />
            Looking for a broker…
          </div>
        )}

        {!looking && reachable && (
          <>
            <div className="gate-status" data-state="ok">
              <span className="dot" />
              <div>
                <strong>Found a broker at {hostOf(probe!.amqp.url)}</strong>
                {probe!.amqp.rewritten && <div className="gate-note">{probe!.amqp.explanation}</div>}
                {capabilities?.version && (
                  <div className="gate-note">
                    RabbitMQ {capabilities.version} — {supported.map((t) => t.label).join(', ')}
                  </div>
                )}
                {capabilities && !capabilities.known && (
                  <div className="gate-note">
                    The management API did not answer, so only classic and quorum queues are
                    offered. The run itself is unaffected.
                  </div>
                )}
              </div>
            </div>

            <div className="gate-actions">
              <button className="primary" onClick={() => onConnected(probe!)}>
                Continue
              </button>
              <button onClick={look}>Check again</button>
            </div>
          </>
        )}

        {!looking && !reachable && (
          <>
            <div className="gate-status" data-state="bad">
              <span className="dot" />
              <div>
                <strong>Nothing answered</strong>
                <div className="gate-note">{failure ?? probe?.amqp.explanation}</div>
              </div>
            </div>

            {probe && probe.where !== 'host' && (
              <div className="banner" data-tone="info" style={{ margin: '14px 0 0' }}>
                The studio is {probe.whereDescription}.
                {probe.hostCandidates.length > 0 ? (
                  <>
                    {' '}
                    It also tried {probe.hostCandidates.join(', ')} — if your broker is a container
                    on the same network, use its container name instead of localhost.
                  </>
                ) : (
                  <> Use the broker's service name, such as rabbitmq.default.svc.cluster.local.</>
                )}
              </div>
            )}

            {probe && probe.amqp.attempts.length > 0 && (
              <div className="attempts">
                <div className="attempts-title">What was tried</div>
                {probe.amqp.attempts.map((attempt) => (
                  <div key={attempt.url} className="attempt">
                    <code>{attempt.url}</code>
                    <span>{attempt.reachable ? 'answered' : attempt.detail}</span>
                  </div>
                ))}
              </div>
            )}

            <div className="gate-actions">
              <button className="primary" onClick={look}>
                Try again
              </button>
            </div>

            <p className="hint" style={{ marginTop: 14 }}>
              No broker to hand?{' '}
              <code>docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:4-management</code>
            </p>
          </>
        )}
      </div>
    </div>
  )
}

function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}
