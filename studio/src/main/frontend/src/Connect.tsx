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
import type { BrokerProbe, TlsSettings } from './types'

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
  tls: TlsSettings
  onBrokerChange: (url: string) => void
  onManagementChange: (url: string) => void
  onTlsChange: (tls: TlsSettings) => void
  onConnected: (probe: BrokerProbe) => void
}

export function Connect({
  broker,
  management,
  tls,
  onBrokerChange,
  onManagementChange,
  onTlsChange,
  onConnected,
}: Props) {
  const [probe, setProbe] = useState<BrokerProbe | null>(null)
  const [looking, setLooking] = useState(true)
  const [failure, setFailure] = useState<string | null>(null)

  async function look() {
    setLooking(true)
    setFailure(null)
    try {
      const found = await api.probe({ broker, management, tls })
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

  const secure = broker.trim().startsWith('amqps://')
  const handshake = probe?.tls
  // A TLS URL that has not completed a handshake is not a connection, whatever the
  // TCP probe says. Continue would only defer the failure to the first run.
  const reachable = (probe?.amqp.reachable ?? false) && (!secure || (handshake?.completed ?? false))
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

        {secure && (
          <div className="tls-panel">
            <div className="tls-title">
              TLS <span className="optional">— this URL says amqps, so a handshake is made</span>
            </div>

            <div className="field">
              <label>
                Certificate authority <span className="optional">— a path, or paste it below</span>
              </label>
              <input
                value={tls.caPath ?? ''}
                spellCheck={false}
                placeholder="/etc/rabbitmq/certs/ca.pem"
                onChange={(e) => onTlsChange({ ...tls, enabled: true, caPath: e.target.value })}
              />
              <p className="hint">
                The authority that signed the broker's certificate. Without it the studio trusts
                only what this machine already trusts, which is not a privately issued
                certificate.
              </p>
            </div>

            <div className="row">
              <div className="field">
                <label>Client certificate <span className="optional">— for mutual TLS</span></label>
                <input
                  value={tls.clientCertificatePath ?? ''}
                  spellCheck={false}
                  placeholder="client.crt"
                  onChange={(e) =>
                    onTlsChange({ ...tls, enabled: true, clientCertificatePath: e.target.value })}
                />
              </div>
              <div className="field">
                <label>Its private key</label>
                <input
                  value={tls.clientKeyPath ?? ''}
                  spellCheck={false}
                  placeholder="client.key"
                  onChange={(e) =>
                    onTlsChange({ ...tls, enabled: true, clientKeyPath: e.target.value })}
                />
              </div>
            </div>
            <p className="hint">
              Only needed when the broker asks who you are. PEM as it comes — the studio builds
              the keystores itself, and the key never leaves this machine.
            </p>

            <label className="switch" style={{ marginTop: 12 }}>
              <input
                type="checkbox"
                checked={tls.allowDevelopmentCertificates ?? false}
                onChange={(e) =>
                  onTlsChange({
                    ...tls,
                    enabled: true,
                    allowDevelopmentCertificates: e.target.checked,
                  })}
              />
              Accept development certificates
            </label>
            <p className="hint">
              The AceMQ generator stamps its certificates as development-only and the library
              refuses them unless this is on. That refusal is the feature: it is what stops one
              reaching production.
            </p>

            <label className="switch" style={{ marginTop: 12 }}>
              <input
                type="checkbox"
                checked={tls.trustAnyCertificate ?? false}
                onChange={(e) =>
                  onTlsChange({ ...tls, enabled: true, trustAnyCertificate: e.target.checked })}
              />
              Trust any certificate
            </label>
            <p className="hint">
              Encrypts the conversation and proves nothing about who is on the other end. For a
              first run against a broker whose certificate nobody can find, and not for anything
              else.
            </p>
          </div>
        )}

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
                {handshake?.completed && (
                  <div className="gate-note">
                    {handshake.protocol} · {handshake.trusted
                      ? 'certificate verified'
                      : 'certificate NOT verified — encrypted, and proving nothing'}
                    {handshake.clientCertificateProvided && ' · client certificate accepted'}
                    {tls.enabled && tls.clientCertificatePath && !handshake.clientCertificateProvided
                      && ' · the broker did not ask for a client certificate'}
                  </div>
                )}
                {handshake?.chain?.[0] && (
                  <div className="gate-note">
                    presented {shortName(handshake.chain[0].subject)}, signed by{' '}
                    {shortName(handshake.chain[0].issuer)}, expires{' '}
                    {handshake.chain[0].notAfter.slice(0, 10)}
                    {handshake.chain[0].development && ' · marked development-only'}
                  </div>
                )}
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
                <strong>
                  {handshake && !handshake.completed
                    ? 'The TLS handshake failed'
                    : 'Nothing answered'}
                </strong>
                <div className="gate-note">
                  {handshake && !handshake.completed
                    ? handshake.problem
                    : (failure ?? probe?.amqp.explanation)}
                </div>
                {handshake?.chain?.[0] && (
                  <div className="gate-note">
                    it presented {shortName(handshake.chain[0].subject)}, signed by{' '}
                    {shortName(handshake.chain[0].issuer)}
                  </div>
                )}
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

/** A distinguished name is unreadable in full; the common name is the part that matters. */
function shortName(dn: string): string {
  const match = /CN=([^,]+)/.exec(dn)
  return match ? match[1] : dn
}

function hostOf(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}
