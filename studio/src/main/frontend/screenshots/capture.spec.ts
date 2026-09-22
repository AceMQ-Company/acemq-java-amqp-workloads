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

import { mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * The pictures in the studio guide, taken by driving the studio.
 *
 * Every file this writes is a photograph of the running application against a
 * running broker. Nothing here draws a picture of an interface: if a screen
 * cannot be reached, the shot is missing and the run fails, which is the
 * outcome we want — a manual whose pictures were drawn rather than taken is
 * worse than a manual with no pictures, because the reader believes them.
 *
 * It is also a test. Every screenshot is preceded by an assertion that the
 * screen it is about to photograph actually says what the guide claims it
 * says, so the guide cannot quietly drift away from the build.
 *
 * The scenario is the `slow-consumer` preset: a fan-out with one leg four
 * times thinner than the other, which is the example the guide itself keeps
 * coming back to. Four nodes rather than the fifteen of `ecommerce`, because a
 * topology zoomed out until its names are three pixels tall illustrates
 * nothing. The topology, the node names and the layout are the same on every
 * capture; the measurement is not, and that is the point of it.
 *
 * Start it with scripts/screenshots.sh, which starts the broker and the studio
 * around it.
 */

const HERE = dirname(fileURLToPath(import.meta.url))
// studio/src/main/frontend/screenshots -> the repository root.
const REPO_ROOT = resolve(HERE, '../../../../..')
const SHOTS = process.env.SHOT_DIR ?? join(REPO_ROOT, 'docs/assets')

// The ports scripts/screenshots.sh publishes its broker on, and the ones the
// committed pictures show in the URL box.
const BROKER = process.env.E2E_BROKER ?? 'amqp://guest:guest@localhost:5781'
const MANAGEMENT = process.env.E2E_MANAGEMENT ?? 'http://localhost:15781'

/**
 * What the producer offers. Low enough that a laptop can actually offer it --
 * a run that never applied its load comes back INVALID and says nothing about
 * the broker -- and doubled for the second run so the comparison has a
 * difference to report rather than two readings of the same thing.
 */
const FIRST_RATE = 1_500
const SECOND_RATE = 3_000

test.beforeAll(() => mkdirSync(SHOTS, { recursive: true }))

test('the studio, screen by screen', async ({ page }) => {
  // ---------------------------------------------------------------- connect
  await page.goto('/')
  await page.getByLabel('Broker (AMQP)').fill(BROKER)
  await page.getByPlaceholder('http://localhost:15672').fill(MANAGEMENT)

  // Press it until it answers. A broker container that has finished booting
  // enough to answer `rabbitmq-diagnostics ping` is not always listening on
  // 5672 yet, and the connect screen checks when it is asked rather than on a
  // timer -- so a single press can photograph "Nothing answered" about a
  // broker that came up a second later.
  const gate = page.locator('.gate-card')
  const found = gate.getByText(/^Found a broker at/)
  for (let attempt = 0; attempt < 20; attempt += 1) {
    await page.getByRole('button', { name: /again/ }).click()
    const answered = await found.waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => true, () => false)
    if (answered) break
    await page.waitForTimeout(3_000)
  }
  await expect(found).toBeVisible()
  // The management API answered, so the broker's own version and its queue
  // types are on the screen. That sentence is what the guide promises, and a
  // shot of it taken without a management URL would quietly contradict it.
  await expect(gate.locator('.gate-note').filter({ hasText: 'RabbitMQ' })).toBeVisible()
  await shoot(gate, 'studio-connect.png')

  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.locator('.canvas')).toBeVisible({ timeout: 30_000 })

  // ----------------------------------------------------------------- design
  await page.getByRole('button', { name: 'presets' }).click()
  await page.locator('.card').filter({ hasText: 'One slow consumer in a fan-out' }).click()
  await expect(page.locator('.canvas')).toBeVisible()
  await expect(page.locator('[data-id^="queue:"]')).toHaveCount(2)

  // The window it measures, short enough to capture twice and long enough for
  // the charts to have something to draw.
  const inspector = page.locator('.inspector')
  await page.locator('.canvas').click({ position: { x: 6, y: 6 } })
  await inspector.getByLabel('Warm-up').fill('3s')
  await inspector.getByLabel('Measure for').fill('25s')

  await page.locator('[data-id="producer:orders"]').click()
  await inspector.getByLabel('Rate, messages a second').fill(String(FIRST_RATE))

  // Fit, from the control the guide points at. A preset arrives on whatever
  // viewport the canvas already had, so without this the framing depends on
  // what was on the canvas before -- which is exactly the noise this capture
  // is meant not to have.
  await page.locator('.react-flow__controls-fitview').click()

  // The whole design tab, with nothing selected: the canvas is the subject
  // and the canvas is the size of the screen, so this is the one shot that is
  // a screen rather than a crop of one.
  await page.locator('.canvas').click({ position: { x: 6, y: 6 } })
  await settle(page)
  await page.screenshot({ path: join(SHOTS, 'studio-canvas.png'), animations: 'disabled' })

  // -------------------------------------------------------------- inspector
  // The thin leg: one consumer against the fast leg's four, a handler ten
  // times slower, a binding and an argument. Everything the guide says the
  // inspector is for, on the queue the scenario is about.
  await page.locator('[data-id="queue:orders.slow"]').click()
  await expect(inspector.getByRole('heading', { name: 'Queue' })).toBeVisible()
  await expect(inspector.getByRole('heading', { name: 'Bound to' })).toBeVisible()
  await inspector.getByRole('button', { name: '+ argument' }).click()
  await inspector.locator('input[value="x-max-length"]')
    .locator('xpath=following::input[1]').fill('200000')
  await blur(page)
  // Back to the top: the shot is the panel as it opens, not wherever adding
  // an argument happened to leave the scroll.
  await inspector.evaluate((el) => { el.scrollTop = 0 })
  await shoot(inspector, 'studio-inspector.png')

  // ------------------------------------------------------------- objectives
  // What the same queue must prove. Filled in rather than photographed empty:
  // an empty form does not show what the fields are for.
  await inspector.getByLabel('p99 under').fill('150ms')
  await inspector.getByLabel('p99.9 under').fill('500ms')
  await inspector.getByLabel('Handles at least, a second').fill('1400')
  await inspector.getByLabel('Must not be deeper at the end than at the start').check()
  await blur(page)
  // The panel is at the foot of a long aside, so scroll it there and crop to
  // it rather than photographing the whole scrolling column.
  await inspector.evaluate((el) => { el.scrollTop = el.scrollHeight })
  await settle(page)
  await shootFrom(page, inspector.getByRole('heading', { name: 'What it must prove' }),
    inspector.locator('.hint').last(), 'studio-objectives.png', inspector)

  // An objective on the producer too, so the run has a producer finding under
  // it. The guide sets them on both, and the verdict shows both.
  await page.locator('[data-id="producer:orders"]').click()
  await inspector.getByLabel('At least, a second').fill('1400')
  await inspector.getByLabel('Within % of the rate').fill('10')
  await inspector.getByLabel('Every publish must succeed').check()
  await blur(page)

  // -------------------------------------------------------------------- run
  await page.getByRole('button', { name: 'Run', exact: true }).click()

  // A run in progress: the charts have readings in them and the phase chip
  // still says the run is going.
  await expect(page.locator('.recharts-wrapper').first()).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText(/elapsed/)).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('.run-view .chip[data-state="live"]')).toBeVisible()
  // Far enough in that both charts have a line rather than a first point.
  await page.waitForTimeout(12_000)
  await expect(page.locator('.run-view .chip[data-state="live"]')).toBeVisible()
  await settle(page)
  await shootFrom(page, page.locator('.run-view .toolbar'),
    page.locator('.panel').filter({ hasText: 'What is waiting' }), 'studio-run.png')

  // ---------------------------------------------------------------- verdict
  const verdict = page.locator('.verdict')
  await expect(verdict).toBeVisible({ timeout: 4 * 60_000 })
  await expect(verdict).toContainText(/Passed|Failed|Invalid/i)
  // Findings carry the measurement that produced them; a verdict with no
  // finding under it would not show what the guide is describing.
  await expect(page.locator('.finding').first()).toBeVisible()
  await settle(page)
  // The verdict box holds every finding the run produced, which is more than
  // fits above the fold. Three is enough to show what one looks like.
  const findings = page.locator('.finding')
  const shown = Math.min(3, await findings.count())
  await shootFrom(page, verdict, findings.nth(shown - 1), 'studio-verdict.png')

  // ------------------------------------------------------------ a second run
  // Same scenario, twice the load, so there is something to compare.
  await page.getByRole('button', { name: 'design' }).click()
  await page.locator('[data-id="producer:orders"]').click()
  await inspector.getByLabel('Rate, messages a second').fill(String(SECOND_RATE))
  await blur(page)

  // Saved, so the guide's "Save keeps the scenario" has something behind it
  // and the history screen is not just a list of runs.
  await page.locator('.toolbar').getByRole('button', { name: 'Save' }).click()

  await page.getByRole('button', { name: 'Run', exact: true }).click()
  await expect(page.locator('.verdict')).toBeVisible({ timeout: 5 * 60_000 })

  // ---------------------------------------------------------------- history
  await page.getByRole('button', { name: 'history' }).click()
  const saved = page.locator('.panel')
    .filter({ has: page.getByRole('heading', { name: 'Saved scenarios' }) })
  const runs = page.locator('.panel').filter({ has: page.getByRole('heading', { name: 'Runs' }) })
  const ticks = runs.locator('table input[type=checkbox]:not([disabled])')
  await expect(ticks.nth(1)).toBeVisible({ timeout: 30_000 })
  // Both panels: the scenario that was saved and the runs it produced, which
  // is what the section is about.
  await expect(saved).toBeVisible()
  await settle(page)
  await shootFrom(page, saved, runs, 'studio-history.png')

  // ------------------------------------------------------------- comparison
  // The older one first. Whichever is ticked first is the "before" column, and
  // the table lists the newest run at the top -- so ticking straight down the
  // list compares the new run against the old one in that order and reports
  // every improvement as a regression.
  await ticks.nth(1).check()
  await ticks.nth(0).check()
  await runs.getByRole('button', { name: 'Compare' }).click()

  const comparison = runs.locator('.panel').filter({ hasText: '→' }).first()
  await expect(comparison).toBeVisible()
  await expect(comparison).toContainText(/better|worse|same/)
  await blur(page)
  await settle(page)
  await shoot(comparison, 'studio-comparison.png')
})

/** Takes the shot of one element. */
async function shoot(target: Locator, file: string) {
  await target.screenshot({ path: join(SHOTS, file), animations: 'disabled' })
}

/**
 * Takes the shot of a region: everything from the top of `first` to the bottom
 * of `last`.
 *
 * A scrolling panel cannot be photographed element by element -- the run view
 * is one tall column and its own screenshot would be several thousand pixels
 * of cards nobody can read. This crops to the part the guide is talking about.
 */
async function shootFrom(
  page: Page, first: Locator, last: Locator, file: string, within?: Locator,
) {
  await first.scrollIntoViewIfNeeded()
  const top = await first.boundingBox()
  const bottom = await last.boundingBox()
  if (!top || !bottom) throw new Error(`nothing to photograph for ${file}`)
  const frame = page.viewportSize()!
  // `within` gives the horizontal extent when the headings and paragraphs
  // being measured are narrower than the panel they sit in -- otherwise the
  // crop lands a pixel inside the last word of the widest line.
  const sides = within ? await within.boundingBox() : null
  const left = sides ? sides.x : Math.min(top.x, bottom.x)
  const right = sides
    ? sides.x + sides.width
    : Math.max(top.x + top.width, bottom.x + bottom.width)
  // Clipping past the foot of the window produces a blank strip, so the crop
  // stops at the fold. What falls below it is the tail of a list, and the
  // guide is describing the head.
  const height = Math.min(bottom.y + bottom.height, frame.height) - top.y
  if (height <= 0) throw new Error(`nothing visible to photograph for ${file}`)
  await page.screenshot({
    path: join(SHOTS, file),
    animations: 'disabled',
    clip: {
      x: Math.max(left, 0),
      y: Math.max(top.y, 0),
      width: Math.min(right, frame.width) - Math.max(left, 0),
      height,
    },
  })
}

/** Drops the focus, so no caret or focus ring lands in the picture. */
async function blur(page: Page) {
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
}

/** Lets the charts finish drawing the reading that just arrived. */
async function settle(page: Page) {
  await page.waitForTimeout(1_200)
}
