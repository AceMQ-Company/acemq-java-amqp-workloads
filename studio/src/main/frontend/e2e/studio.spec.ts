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
 * The studio, in a browser, against a broker.
 *
 * Every test here is a bug this project actually had, found by somebody
 * clicking: a run view that would not scroll, charts squeezed into two hundred
 * pixels by a media query meant for the designer, a connect screen that let you
 * past without a broker. None of them are visible without layout, which is why
 * these run in a real browser rather than in jsdom.
 */

const BROKER = process.env.E2E_BROKER ?? 'amqp://guest:guest@localhost:5672'

test.describe('the studio', () => {
  test('will not let you past without a broker', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByRole('heading', { name: 'Connect to a broker' })).toBeVisible()
    // Nothing else is reachable: no tabs, no canvas, no Run.
    await expect(page.getByRole('button', { name: 'Run', exact: true })).toHaveCount(0)
    await expect(page.locator('.canvas')).toHaveCount(0)

    await connect(page, 'amqp://guest:guest@127.0.0.1:1')
    await expect(page.getByText('Nothing answered', { exact: true })).toBeVisible()
    // And it says what it tried, which is the difference between a closed port
    // and a wrong password.
    await expect(page.locator('code').first()).toContainText('127.0.0.1:1')
  })

  test('connects, and then the designer is usable', async ({ page }) => {
    await open(page)

    await expect(page.getByRole('button', { name: 'design' })).toBeVisible()
    await expect(page.locator('.canvas')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Run', exact: true })).toBeEnabled()
  })

  test('carries an edited binding, an argument and an objective into the file', async ({ page }) => {
    await open(page)
    await page.locator('[data-id^="queue:"]').first().click()

    const inspector = page.locator('.inspector')
    await expect(inspector.getByText('Bound to')).toBeVisible()

    await inspector.getByPlaceholder('order.*').fill('order.created')
    await inspector.getByRole('button', { name: '+ argument' }).click()
    await inspector.locator('input[value="x-max-length"]').locator('xpath=following::input[1]')
      .fill('5000')
    await inspector.getByPlaceholder('50ms').fill('75ms')

    // The export is the contract with the command line, so that is what is
    // checked rather than the state of a box on a screen.
    const exported = await exportScenario(page)

    expect(exported.queues[0].bindings).toEqual([
      { exchange: 'bench', routingKey: 'order.created' },
    ])
    expect(exported.queues[0].arguments).toEqual({ 'x-max-length': 5000 })
    expect(exported.queues[0].expect).toEqual({ p99Below: '75ms' })
  })

  test('refuses to run a scenario the broker would refuse, and says which part', async ({ page }) => {
    await open(page)
    await page.locator('[data-id^="queue:"]').first().click()

    // A binding to an exchange nothing declares. The broker's own error for this
    // arrives mid-run as a channel closure that reads like a broker problem.
    await page.locator('.inspector select').first().selectOption({ index: 0 })
    await page.locator('[data-id^="exchange:"]').first().click()
    await page.locator('.inspector input').first().fill('renamed')

    await expect(page.locator('.banner[data-tone="bad"]')).toContainText('bench')
    await expect(page.getByRole('button', { name: 'Run', exact: true })).toBeDisabled()
  })

  test('runs, draws it, and hands the report over', async ({ page }) => {
    await open(page)
    await shortRun(page)

    await page.getByRole('button', { name: 'Run', exact: true }).click()

    // Readings arrive while it goes: without them the charts stay empty, which
    // looks exactly like a dead broker.
    await expect(page.locator('.recharts-wrapper').first()).toBeVisible()
    await expect(page.getByText(/elapsed/)).toBeVisible({ timeout: 30_000 })

    await expect(page.locator('.verdict')).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('.verdict')).toContainText(/Passed|Failed/)

    // And it can be taken away as a file.
    const download = page.waitForEvent('download')
    await page.locator('.run-view .toolbar').getByRole('button', { name: 'HTML' }).click()
    const saved = await download
    expect(saved.suggestedFilename()).toMatch(/^acemq-report-.*\.html$/)
  })

  // The layout bug that started this: the run view divided its height between
  // the verdict and the charts, so a run with findings squeezed the charts to
  // nothing and put a scrollbar inside a page that did not scroll.
  test('the run view scrolls as one page, with the charts at full height', async ({ page }) => {
    await open(page)
    await shortRun(page)
    await page.getByRole('button', { name: 'Run', exact: true }).click()
    await expect(page.locator('.verdict')).toBeVisible({ timeout: 60_000 })

    for (const size of [{ width: 1680, height: 1000 }, { width: 1262, height: 860 },
                        { width: 1024, height: 700 }, { width: 420, height: 760 }]) {
      await page.setViewportSize(size)

      const layout = await page.evaluate(() => {
        const scroll = document.querySelector('.run-scroll')!
        const grid = document.querySelector('.live-grid')!
        return {
          scrolls: scroll.scrollHeight > scroll.clientHeight,
          room: scroll.clientHeight,
          nested: grid.scrollHeight > grid.clientHeight + 4,
          sideways: document.documentElement.scrollWidth > document.documentElement.clientWidth,
          charts: [...document.querySelectorAll('.recharts-wrapper')]
            .map((c) => Math.round(c.getBoundingClientRect().height)),
        }
      })

      expect(layout.nested, `nested scrollbar at ${size.width}x${size.height}`).toBe(false)
      expect(layout.sideways, `horizontal overflow at ${size.width}x${size.height}`).toBe(false)
      // The charts keep their pixel height; the page scrolls instead of them
      // shrinking. 190 is the smaller of the two.
      expect(Math.min(...layout.charts),
        `charts squeezed at ${size.width}x${size.height}`).toBeGreaterThanOrEqual(190)
      // And there is somewhere to scroll: the run view is not a 200px slot.
      expect(layout.room,
        `no room for the run view at ${size.width}x${size.height}`).toBeGreaterThan(300)
    }
  })

  test('keeps the run, compares two, and forgets one', async ({ page }) => {
    await open(page)

    for (const rate of [400, 800]) {
      await shortRun(page, rate)
      await page.getByRole('button', { name: 'Run', exact: true }).click()
      await expect(page.locator('.verdict')).toBeVisible({ timeout: 60_000 })
    }

    await page.getByRole('button', { name: 'history' }).click()
    // Two of however many are there: the tests before this one leave their runs
    // behind, which is the same history somebody accumulates by using the thing.
    const ticks = page.locator('table input[type=checkbox]:not([disabled])')
    await expect(ticks.nth(1)).toBeVisible({ timeout: 15_000 })
    await ticks.nth(0).check()
    await ticks.nth(1).check()

    await page.getByRole('button', { name: 'Compare' }).click()

    const comparison = page.locator('.panel').filter({ hasText: '→' }).first()
    await expect(comparison).toBeVisible()
    // Direction rather than a signed number: the whole point of the table.
    await expect(comparison).toContainText(/better|worse|same/)
    await expect(comparison).toContainText('consumed/s')

    const before = await page.locator('table tbody tr').count()
    await page.locator('table').last().getByRole('button', { name: 'Delete' }).last().click()
    await expect(page.locator('table tbody tr')).toHaveCount(before - 1)
  })

  test('the connect screen fits a small window', async ({ page }) => {
    await page.setViewportSize({ width: 420, height: 760 })
    await page.goto('/')

    await expect(page.getByRole('heading', { name: 'Connect to a broker' })).toBeVisible()
    const sideways = await page.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth)
    expect(sideways).toBe(false)
  })
})

/** @param page the browser */
async function connect(page: Page, broker: string) {
  await page.locator('input').first().fill(broker)
  await page.getByRole('button', { name: /again/ }).click()
}

/** Gets past the gate, with a broker that answers. */
async function open(page: Page) {
  await page.goto('/')
  await connect(page, BROKER)
  await page.getByRole('button', { name: 'Continue' }).click({ timeout: 30_000 })
  await expect(page.locator('.canvas')).toBeVisible()
}

/**
 * Makes the scenario on the canvas short enough to run in a test.
 *
 * @param rate what the producer should offer, so two runs differ
 */
async function shortRun(page: Page, rate = 500) {
  await page.getByRole('button', { name: 'design' }).click()
  await page.locator('.canvas').click({ position: { x: 5, y: 5 } })

  const inspector = page.locator('.inspector')
  await inspector.getByLabel('Warm-up').fill('1s')
  await inspector.getByLabel('Measure for').fill('4s')

  await page.locator('[data-id^="producer:"]').first().click()
  await inspector.getByLabel('Rate, messages a second').fill(String(rate))
}

/** @return the scenario as the file the command line reads */
async function exportScenario(page: Page) {
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Export JSON' }).click()
  const saved = await download
  const stream = await saved.createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream) {
    chunks.push(chunk as Buffer)
  }
  return JSON.parse(Buffer.concat(chunks).toString())
}
