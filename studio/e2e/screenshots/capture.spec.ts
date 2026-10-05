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
// studio/e2e/screenshots -> the repository root.
const REPO_ROOT = resolve(HERE, '../../..')
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
  // ----------------------------------------------------------------- broker
  await page.goto('/#broker')
  await page.locator('#bkAmqp').fill(BROKER)
  await page.locator('#bkMgmt').fill(MANAGEMENT)

  // Ask until it answers. A broker container that answers `rabbitmq-diagnostics
  // ping` is not always listening on 5672 yet, and the broker view checks when
  // it is asked rather than on a timer.
  const found = page.locator('#bkStatus .gstat.ok')
  for (let attempt = 0; attempt < 20; attempt += 1) {
    await page.locator('#bkCheck').click()
    const answered = await found.waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => true, () => false)
    if (answered) break
    await page.waitForTimeout(3_000)
  }
  await expect(found).toContainText('Found a broker at')
  // The management API answered, so the broker's own version and its queue
  // types are on the screen. That sentence is what the guide promises.
  await expect(found).toContainText('RabbitMQ')
  await blur(page)
  await shoot(page.locator('.view[data-view="broker"] .grid'), 'studio-connect.png')

  // ----------------------------------------------------------------- design
  await page.locator('#bkStatus').getByRole('button', { name: 'Design a scenario' }).click()
  await page.locator('#scPresets [data-preset="slow-consumer"]').click()
  await expect(page.locator('#scSvg .nd[data-kind="queue"]')).toHaveCount(2)

  // The window it measures, short enough to capture twice and long enough for
  // the charts to have something to draw.
  const inspector = page.locator('#scInspector')
  await page.locator('#scSvg').click({ position: { x: 3, y: 3 } })
  await inspector.getByLabel('Warm-up').fill('3s')
  await inspector.getByLabel('Measure for').fill('25s')
  await page.locator('#scSvg [data-id="producer:orders"]').click()
  await inspector.getByLabel('Rate, messages a second').fill(String(FIRST_RATE))

  // The whole view with nothing selected: the one shot that is a screen
  // rather than a crop of one.
  await page.locator('#scSvg').click({ position: { x: 3, y: 3 } })
  await page.evaluate(() => scrollTo(0, 0))
  // The "opened the preset" toast says nothing the picture does not already show.
  await expect(page.locator('#toast')).not.toHaveClass(/on/, { timeout: 10_000 })
  await blur(page)
  await settle(page)
  await page.screenshot({ path: join(SHOTS, 'studio-canvas.png'), animations: 'disabled' })

  // -------------------------------------------------------------- inspector
  // The thin leg: one consumer against the fast leg's four, a binding and an
  // argument. Everything the guide says the inspector is for.
  await page.locator('#scSvg [data-id="queue:orders.slow"]').click()
  await expect(page.locator('#scInsTitle')).toHaveText('Queue')
  await inspector.getByRole('button', { name: '+ argument' }).click()
  await inspector.locator('[data-av]').fill('200000')
  await blur(page)
  const panel = page.locator('.pn').filter({ has: inspector })
  await shootFrom(page, panel, inspector.getByRole('button', { name: '+ argument' }),
    'studio-inspector.png', panel)

  // ------------------------------------------------------------- objectives
  await inspector.getByLabel('p99 under').fill('150ms')
  await inspector.getByLabel('p99.9 under').fill('500ms')
  await inspector.getByLabel('Handles at least, a second').fill('1400')
  await inspector.getByLabel('Must not be deeper at the end than at the start').check()
  await blur(page)
  await shootFrom(page, inspector.locator('h5', { hasText: 'What it must prove' }),
    inspector.locator('.hint').last(), 'studio-objectives.png', panel)

  // An objective on the producer too, so the verdict has a producer finding.
  await page.locator('#scSvg [data-id="producer:orders"]').click()
  await inspector.getByLabel('At least, a second').fill('1400')
  await inspector.getByLabel('Within % of the rate').fill('10')
  await inspector.getByLabel('Every publish must succeed').check()
  await blur(page)

  // -------------------------------------------------------------------- run
  await page.locator('#scRun').click()
  await expect(page.locator('.view[data-view="runs"]')).toHaveClass(/on/)
  await expect(page.locator('#rnElapsed')).toContainText('s', { timeout: 60_000 })
  // Far enough in that both charts have a line rather than a first point.
  await page.waitForTimeout(12_000)
  await expect(page.locator('#rnStop')).toBeVisible()
  await settle(page)
  await shootFrom(page, page.locator('.view[data-view="runs"] .vh'), page.locator('#rnDepthPn'),
    'studio-run.png')

  // ---------------------------------------------------------------- verdict
  const verdict = page.locator('#rnVerdict .verdict')
  await expect(verdict).toBeVisible({ timeout: 4 * 60_000 })
  await expect(verdict.locator('.vt')).toHaveText(/Passed|Failed|Invalid/)
  const findings = verdict.locator('.finding')
  await expect(findings.first()).toBeVisible()
  await settle(page)
  const shown = Math.min(3, await findings.count())
  await shootFrom(page, verdict, findings.nth(shown - 1), 'studio-verdict.png', verdict)

  // ------------------------------------------------------------ a second run
  // Same scenario, twice the load, so there is something to compare. Saved,
  // so the guide's "Save keeps the scenario" has something behind it.
  await page.locator('#tab-scenarios').click()
  await page.locator('#scSvg [data-id="producer:orders"]').click()
  await inspector.getByLabel('Rate, messages a second').fill(String(SECOND_RATE))
  await blur(page)
  await page.locator('#scSave').click()
  await expect(page.locator('#scSaved tr').filter({ hasText: 'slow-consumer' })).toBeVisible()
  await expect(page.locator('#scRun')).toBeEnabled()
  await page.locator('#scRun').click()
  await expect(page.locator('#rnVerdict .verdict')).toBeVisible({ timeout: 5 * 60_000 })

  // ---------------------------------------------------------------- reports
  await page.locator('#tab-reports').click()
  const ticks = page.locator('#rpT input[type=checkbox]:not([disabled])')
  await expect(ticks.nth(1)).toBeVisible({ timeout: 30_000 })
  await blur(page)
  await settle(page)
  await shoot(page.locator('#rpRuns'), 'studio-history.png')

  // ------------------------------------------------------------- comparison
  // The older one first. Whichever is ticked first is the "before" column, and
  // the newest run is at the top -- so ticking straight down the list would
  // report every improvement as a regression.
  await ticks.nth(1).check()
  await ticks.nth(0).check()
  await page.locator('#rpCompare').click()
  const comparison = page.locator('#rpCmp')
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
  // The top of the region at the top of the window, so as much of it as fits is in the shot.
  await first.evaluate((el) => el.scrollIntoView({ block: 'start' }))
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
