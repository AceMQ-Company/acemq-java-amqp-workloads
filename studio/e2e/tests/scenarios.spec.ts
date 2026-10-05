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

import { expect, test, type Page } from '@playwright/test'

/**
 * Scenarios, scenario runs, reports and the broker: the screens the studio's first interface
 * had, in the console, against a real broker.
 *
 * Every test here is a bug this project has had or a promise the old interface made and the
 * console must keep: no run without a broker, a designer whose edits reach the file the command
 * line reads, a run drawn while it goes and a report that can be taken away, two runs compared.
 */

const BROKER = process.env.E2E_BROKER ?? 'amqp://guest:guest@localhost:5672'
const WIDTHS = [375, 768, 1280, 1920]

test.describe('the broker', () => {
  test('says what it tried when nothing answers', async ({ page }) => {
    await page.goto('/#broker')
    await page.locator('#bkAmqp').fill('amqp://guest:guest@127.0.0.1:1')
    await page.locator('#bkAmqp').press('Enter')

    await expect(page.locator('#bkStatus .gstat.bad')).toContainText('Nothing answered')
    // The difference between a closed port and a wrong password.
    await expect(page.locator('#bkStatus code').first()).toContainText('127.0.0.1:1')
  })

  test('will not start a run without one', async ({ page }) => {
    await page.goto('/#broker')
    await page.locator('#bkAmqp').fill('amqp://guest:guest@127.0.0.1:1')
    await page.locator('#bkAmqp').press('Enter')
    await expect(page.locator('#bkStatus .gstat.bad')).toBeVisible()

    await go(page, 'scenarios')
    await page.locator('#scRun').click()
    // Back to the broker, rather than a run that fails against nothing.
    await expect(page.locator('.view[data-view="broker"]')).toHaveClass(/on/)
  })

  test('connects, and offers the designer', async ({ page }) => {
    await connect(page)
    await page.locator('#bkStatus').getByRole('button', { name: 'Design a scenario' }).click()
    await expect(page.locator('.view[data-view="scenarios"]')).toHaveClass(/on/)
    await expect(page.locator('#scBroker')).toHaveAttribute('data-state', /ok|rw/)
    await expect(page.locator('#scRun')).toBeEnabled()
  })
})

test.describe('the designer', () => {
  test('opens a preset onto the canvas', async ({ page }) => {
    await page.goto('/#scenarios')
    await openPreset(page, 'dead-letter')

    await expect(page.locator('#scName')).toHaveText('dead-letter')
    await expect(page.locator('#scSvg .nd[data-kind="queue"]')).toHaveCount(2)
    await expect(page.locator('#scSvg [data-id="queue:payments.parked"]')).toBeVisible()
    // The warning about the queue nothing reads sits above the canvas, not instead of it.
    await expect(page.locator('#scLint')).toContainText('nothing consumes payments.parked')
    expect((await page.locator('#scCanvas').boundingBox())!.height).toBeGreaterThan(200)
  })

  test('carries an edited binding, an argument and an objective into the file', async ({ page }) => {
    await page.goto('/#scenarios')
    await page.locator('#scNew').click()
    await page.locator('#scSvg .nd[data-kind="queue"]').first().click()

    const inspector = page.locator('#scInspector')
    await inspector.getByPlaceholder('order.*').fill('order.created')
    await inspector.getByRole('button', { name: '+ argument' }).click()
    await inspector.locator('[data-av]').fill('5000')
    await inspector.getByPlaceholder('50ms').fill('75ms')
    await inspector.getByLabel('Must not be deeper at the end than at the start').check()

    // The export is the contract with the command line, so that is what is checked.
    let file = await exportScenario(page)
    expect(file.queues[0].bindings).toEqual([{ exchange: 'bench', routingKey: 'order.created' }])
    // A number stays a number: the broker refuses "5000".
    expect(file.queues[0].arguments).toEqual({ 'x-max-length': 5000 })
    expect(file.queues[0].expect).toEqual({ p99Below: '75ms', noBacklog: true })

    // Text stays text; the last objective cleared drops the block; a binding removed is gone.
    await inspector.locator('[data-av]').fill('reject-publish')
    await inspector.getByPlaceholder('50ms').fill('')
    await inspector.getByLabel('Must not be deeper at the end than at the start').uncheck()
    await inspector.getByRole('button', { name: 'Unbind' }).click()
    file = await exportScenario(page)
    expect(file.queues[0].arguments).toEqual({ 'x-max-length': 'reject-publish' })
    expect(file.queues[0].expect).toBeUndefined()
    expect(file.queues[0].bindings ?? []).toEqual([])
  })

  test('binds an exchange to a queue by dragging between them', async ({ page }) => {
    await page.goto('/#scenarios')
    await page.locator('#scNew').click()
    await page.locator('#scAddQ').click()
    await expect(page.locator('#scSvg [data-id="queue:queue-2"]')).toBeVisible()

    // The mouse cannot drag to what is off screen, so the whole topology is brought into view.
    await page.setViewportSize({ width: 1280, height: 1000 })
    await page.locator('#scCanvas').scrollIntoViewIfNeeded()
    const from = (await page.locator('#scSvg [data-id="exchange:bench"]').boundingBox())!
    const to = (await page.locator('#scSvg [data-id="queue:queue-2"]').boundingBox())!
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
    await page.mouse.down()
    await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 8 })
    await page.mouse.up()

    await page.locator('#scSvg [data-id="queue:queue-2"]').click()
    await expect(page.locator('#scInspector [data-bx="0"]')).toHaveValue('bench')
  })

  test('refuses to run a scenario the broker would refuse, and says which part', async ({ page }) => {
    await page.goto('/#scenarios')
    await page.locator('#scNew').click()
    // Renaming the exchange leaves the binding and the producer pointing at nothing.
    await page.locator('#scSvg [data-id="exchange:bench"]').click()
    await page.locator('#scInspector #fName').fill('renamed')

    await expect(page.locator('#scLint .lint.cr').filter({ hasText: 'bench' }).first()).toBeVisible()
    await expect(page.locator('#scRun')).toBeDisabled()
    // The binding stays visible, saying what is wrong with it.
    await page.locator('#scSvg .nd[data-kind="queue"]').first().click()
    await expect(page.locator('#scInspector [data-bx="0"] option:checked')).toHaveText('bench (no such exchange)')
  })

  test('offers only the queue types the broker honours, and says why', async ({ page }) => {
    await page.route('**/api/broker/probe', (route) => route.fulfill({
      json: {
        where: 'host', whereDescription: 'on this machine', hostCandidates: [],
        amqp: { requested: BROKER, reachable: true, url: BROKER, rewritten: false, explanation: '', attempts: [] },
        capabilities: {
          version: '3.11.0', known: true, queueTypes: [
            { id: 'classic', label: 'Classic', description: 'one node', supported: true, whyNot: null },
            { id: 'quorum', label: 'Quorum', description: 'replicated', supported: true, whyNot: null },
            { id: 'stream', label: 'Stream', description: 'a log', supported: false, whyNot: 'this broker is older than streams' },
          ],
        },
      },
    }))
    await page.goto('/#scenarios')
    await page.locator('#scSvg .nd[data-kind="queue"]').first().click()

    await expect(page.locator('#scInspector input[value="classic"]')).toBeEnabled()
    await expect(page.locator('#scInspector input[value="stream"]')).toBeDisabled()
    await expect(page.locator('#scInspector')).toContainText('this broker is older than streams')
  })

  test('opens a scenario file, saves it, reopens it and forgets it', async ({ page }) => {
    await page.goto('/#scenarios')
    const name = `e2e-file-${Date.now()}`
    await page.locator('#scFile').setInputFiles({
      name: 'from-a-pipeline.yaml', mimeType: 'application/yaml',
      buffer: Buffer.from(`name: ${name}\nexchanges:\n  - name: x\n    type: fanout\nqueues:\n  - name: q\n    bindings:\n      - exchange: x\n        routingKey: ""\nproducers:\n  - name: p\n    exchange: x\n    rate: 100\nrunFor: 5s\n`),
    })
    await expect(page.locator('#scName')).toHaveText(name)
    await expect(page.locator('#scSvg [data-id="exchange:x"]')).toBeVisible()

    await page.locator('#scSave').click()
    const row = page.locator('#scSaved tr').filter({ hasText: name })
    await expect(row).toBeVisible()

    await page.locator('#scNew').click()
    await row.getByRole('button', { name: 'Open' }).click()
    await expect(page.locator('#scName')).toHaveText(name)

    await row.getByRole('button', { name: /Delete/ }).click()
    await expect(row).toHaveCount(0)
  })

  test('opens a preset from the palette', async ({ page }) => {
    await page.goto('/#loads')
    await expect(page.locator('#scPresets [data-preset]').first()).toBeAttached()
    await page.keyboard.press('ControlOrMeta+k')
    await page.keyboard.type('Preset: Find the ceiling')
    await page.keyboard.press('Enter')
    await expect(page.locator('.view[data-view="scenarios"]')).toHaveClass(/on/)
    await expect(page.locator('#scName')).toHaveText('find-the-ceiling')
  })
})

test.describe('a run', () => {
  test('starts from a preset, is drawn while it goes, and hands its report over', async ({ page }) => {
    await connect(page)
    await go(page, 'scenarios')
    await openPreset(page, 'quorum-vs-classic')
    await shorten(page)
    await page.locator('#scRun').click()

    await expect(page.locator('.view[data-view="runs"]')).toHaveClass(/on/)
    // Readings arrive while it goes: without them the charts stay empty, which looks exactly
    // like a dead broker.
    await expect(page.locator('#rnElapsed')).toContainText('s', { timeout: 30_000 })

    await expect(page.locator('#rnVerdict .verdict')).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('#rnVerdict .vt')).toHaveText(/Passed|Failed|Invalid/)
    await expect(page.locator('#rnQT tr')).toHaveCount(2)

    // The same document the command line writes, as a file.
    for (const [button, extension] of [['HTML', 'html'], ['Markdown', 'md'], ['JSON', 'json']]) {
      const download = page.waitForEvent('download')
      await page.locator('#rnSave').getByRole('link', { name: button }).click()
      expect((await download).suggestedFilename()).toMatch(new RegExp(`^acemq-report-.*\\.${extension}$`))
    }

    // The run view fits every width, and its charts keep their height rather than squeeze.
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 900 })
      await page.waitForTimeout(300)
      const layout = await page.evaluate(() => ({
        sideways: document.documentElement.scrollWidth > document.documentElement.clientWidth,
        charts: [...document.querySelectorAll('#rnRate, #rnDepth')]
          .map((c) => Math.round(c.getBoundingClientRect().height)),
        drawn: (document.querySelector('#rnRate') as HTMLCanvasElement).width,
      }))
      expect(layout.sideways, `sideways at ${width}`).toBe(false)
      expect(Math.min(...layout.charts), `charts at ${width}`).toBeGreaterThanOrEqual(190)
      // Redrawn for the width it now has, not the width it was drawn at.
      expect(layout.drawn, `chart redrawn at ${width}`).toBeGreaterThan(width / 3)
    }
  })

  test('is kept, opened again from Reports, compared with another and forgotten', async ({ page }) => {
    await connect(page)
    for (const rate of [400, 800]) {
      await go(page, 'scenarios')
      // A preset rather than a new scenario: the runs above leave its exchange declared, and
      // the same name redeclared as another type is refused by the broker.
      await openPreset(page, 'quorum-vs-classic')
      await shorten(page, rate)
      await page.locator('#scRun').click()
      await expect(page.locator('#rnVerdict .verdict')).toBeVisible({ timeout: 60_000 })
    }

    await page.goto('/#reports')
    const ticks = page.locator('#rpT input[type=checkbox]:not([disabled])')
    await expect(ticks.nth(1)).toBeVisible({ timeout: 15_000 })
    await ticks.nth(0).check()
    await ticks.nth(1).check()
    await expect(page.locator('#rpHint')).toHaveText('Ready.')
    await page.locator('#rpCompare').click()

    await expect(page.locator('#rpCmpTitle')).toContainText('→')
    // Direction rather than a signed number: the whole point of the table.
    await expect(page.locator('#rpCmpT')).toContainText(/better|worse|same/)
    await expect(page.locator('#rpCmpT')).toContainText('consumed/s')

    // Every finished run links its report in three forms.
    const first = page.locator('#rpT tr').first()
    await expect(first.getByRole('link', { name: 'HTML' })).toHaveAttribute('href', /\/report\.html$/)
    const download = page.waitForEvent('download')
    await first.getByRole('link', { name: 'Markdown' }).click()
    expect((await download).suggestedFilename()).toMatch(/\.md$/)

    await first.getByRole('button', { name: 'Open' }).click()
    await expect(page.locator('.view[data-view="runs"]')).toHaveClass(/on/)
    await expect(page.locator('#rnVerdict .verdict')).toBeVisible()
    // Drawn again from the readings that were kept.
    await expect(page.locator('#rnNodes .lcard')).not.toHaveCount(0)

    await page.goto('/#reports')
    const before = await page.locator('#rpT tr').count()
    await page.locator('#rpT tr').first().getByRole('button', { name: /Delete/ }).click()
    await expect(page.locator('#rpT tr')).toHaveCount(before - 1)
  })
})

test.describe('a comparison', () => {
  // The words, not only the numbers: checked against a fixed answer so the arithmetic of the
  // label is pinned rather than whatever two real runs happened to measure.
  test('says better and worse, and switches to a multiplier past ten times', async ({ page }) => {
    const run = (id: string) => ({ id, scenarioId: null, scenarioName: id, broker: 'amqp://b', startedAt: '2026-10-01T10:00:00Z', finishedAt: '2026-10-01T10:01:00Z', status: 'finished', verdict: 'passed', error: null })
    await page.route('**/api/runs', (route) => route.fulfill({ json: [run('a'), run('b')] }))
    await page.route('**/api/runs/compare**', (route) => route.fulfill({
      json: {
        a: run('a'), b: run('b'), rows: [
          { node: 'q', metric: 'p99', a: 1.0, b: 1.4, unit: 'ms', higherIsBetter: false, change: 0.4, verdict: 'worse' },
          { node: 'q', metric: 'consumed/s', a: 1000, b: 1400, unit: '/s', higherIsBetter: true, change: 0.4, verdict: 'better' },
          { node: 'q', metric: 'p50', a: 0.6, b: 1780, unit: 'ms', higherIsBetter: false, change: 2966, verdict: 'worse' },
          { node: 'gone', metric: 'consumed/s', a: 1234.6, b: null, unit: '/s', higherIsBetter: true, change: null, verdict: 'missing' },
        ],
      },
    }))
    await page.goto('/#reports')
    await page.locator('#rpT input[type=checkbox]').nth(0).check()
    await page.locator('#rpT input[type=checkbox]').nth(1).check()
    await page.locator('#rpCompare').click()

    const cells = page.locator('#rpCmpT tr td:last-child')
    await expect(cells.nth(0)).toHaveText('+40.0% · worse')
    await expect(cells.nth(1)).toHaveText('+40.0% · better')
    await expect(cells.nth(2)).toHaveText('×2967 · worse')
    await expect(cells.nth(3)).toHaveText('only before')
    await expect(page.locator('#rpCmpT tr').nth(0)).toContainText('1.4ms')
    await expect(page.locator('#rpCmpT tr').nth(3)).toContainText('1,235')
    await expect(page.locator('#rpCmpHint')).toContainText('3 of 4 measurements moved')
  })
})

/** Changes view inside the page, so what the page knows -- the broker -- is kept. */
async function go(page: Page, view: string) {
  await page.locator(`#tab-${view}`).click()
  await expect(page.locator(`.view[data-view="${view}"]`)).toHaveClass(/on/)
}

/** Points the studio at the test broker and waits for it to answer. */
async function connect(page: Page) {
  await page.goto('/#broker')
  await page.locator('#bkAmqp').fill(BROKER)
  await page.locator('#bkAmqp').press('Enter')
  await expect(page.locator('#bkStatus .gstat.ok')).toBeVisible({ timeout: 30_000 })
}

/** @param id the preset's id */
async function openPreset(page: Page, id: string) {
  await page.locator(`#scPresets [data-preset="${id}"]`).click()
  await expect(page.locator('#scSvg .nd').first()).toBeVisible()
}

/**
 * Makes the scenario on the canvas short enough to run in a test.
 *
 * @param rate what the first producer should offer, so two runs differ
 */
async function shorten(page: Page, rate = 500) {
  await page.locator('#scSvg').click({ position: { x: 2, y: 2 } })
  await page.locator('#scInspector').getByLabel('Warm-up').fill('1s')
  await page.locator('#scInspector').getByLabel('Measure for').fill('3s')
  await page.locator('#scSvg .nd[data-kind="producer"]').first().click()
  await page.locator('#scInspector').getByLabel('Rate, messages a second').fill(String(rate))
  // The check runs a moment after the last change; Run waits for nothing it reports.
  await expect(page.locator('#scRun')).toBeEnabled()
}

/** @return the scenario as the file the command line reads */
async function exportScenario(page: Page) {
  await page.waitForTimeout(100)
  const download = page.waitForEvent('download')
  await page.locator('#scJson').click()
  const stream = await (await download).createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(chunk as Buffer)
  return JSON.parse(Buffer.concat(chunks).toString())
}
