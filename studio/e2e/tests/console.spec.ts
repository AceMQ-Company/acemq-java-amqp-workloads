import { expect, test } from '@playwright/test'

/**
 * The studio's one interface, at / (and /console), in a browser.
 *
 * Its layout is fluid by design, from a phone to a wall screen, and the one
 * failure that design exists to prevent is a page that scrolls sideways. Each
 * view is opened at four widths and the page must fit; only tables, code and
 * charts may scroll, inside their own containers.
 */

const WIDTHS = [375, 768, 1280, 1920]
const VIEWS = ['loads', 'soak', 'evidence', 'studio', 'scenarios', 'runs', 'reports', 'broker']

test.describe('the workloads console', () => {
  for (const width of WIDTHS) {
    test(`fits ${width} px without scrolling sideways`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      for (const view of VIEWS) {
        await page.goto(`/console/#${view}`)
        await expect(page.locator(`.view[data-view="${view}"]`)).toHaveClass(/on/)
        await page.waitForTimeout(600)
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow, `${view} at ${width} px`).toBeLessThanOrEqual(0)
        // On a phone everything a thumb presses is at least 40 px: the bottom bar, buttons,
        // fields and the boxes around checkboxes. Links inside a sentence are exempt.
        if (width === 375) {
          const small = await page.evaluate(() => [...document.querySelectorAll(
            '.rail button, .view.on button, .view.on a:not(p a, span a), .view.on select, '
            + '.view.on input:not(label input):not([type=file])')]
            .filter((e) => {
              const r = e.getBoundingClientRect()
              return r.width > 0 && r.height > 0 && (r.width < 40 || r.height < 40)
            })
            .map((e) => `${e.tagName}#${e.id}.${e.className}`))
          expect(small, `small touch targets on ${view}`).toEqual([])
        }
      }
    })
  }

  test('the rail is a bottom bar on a phone and a side rail elsewhere', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 })
    await page.goto('/#scenarios')
    const phone = await page.locator('#rail').boundingBox()
    expect(phone!.y + phone!.height).toBeGreaterThan(780)
    expect(phone!.width).toBeGreaterThan(360)
    await expect(page.locator('#rail [role=tab]')).toHaveCount(VIEWS.length)

    await page.setViewportSize({ width: 1280, height: 800 })
    const desk = await page.locator('#rail').boundingBox()
    expect(desk!.x).toBe(0)
    expect(desk!.width).toBeLessThan(80)
  })

  test('is the home page, and /console is an alias', async ({ page }) => {
    for (const path of ['/', '/console', '/console/']) {
      await page.goto(path)
      await expect(page).toHaveTitle('AceMQ Workloads Studio')
      await expect(page.locator('.view.on')).toHaveCount(1)
    }
  })

  test.describe('on a 2x screen', () => {
    test.use({ deviceScaleFactor: 2 })
    // The canvas height was written back in device pixels and read again on the next redraw,
    // so every resize doubled it until the browser refused to draw it at all.
    test('charts keep their height through redraws', async ({ page }) => {
      await page.goto('/console/#studio')
      await expect(page.locator('#stYaml')).toContainText('name:')
      for (const width of [1280, 900, 1400, 1100]) {
        await page.setViewportSize({ width, height: 900 })
        await page.waitForTimeout(250)
      }
      const box = (await page.locator('#stChart').boundingBox())!
      expect(box.height).toBe(140)
      expect(await page.locator('#stChart').evaluate((c: HTMLCanvasElement) => c.height)).toBe(280)
    })
  })

  test('designs a file the library accepts', async ({ page }) => {
    await page.goto('/console/#studio')
    await expect(page.locator('#stYaml')).toContainText('name: standing-load')
    await expect(page.locator('.lint.ok')).toContainText('The library parses this file')

    await page.locator('#stQueue').fill('e2e.console')
    await expect(page.locator('#stYaml')).toContainText('queue: e2e.console')
  })

  test('opens the palette from the keyboard and jumps to a view', async ({ page }) => {
    await page.goto('/console/#loads')
    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.locator('#palIn')).toBeFocused()
    await page.keyboard.type('evidence')
    await page.keyboard.press('Enter')
    await expect(page.locator('.view[data-view="evidence"]')).toHaveClass(/on/)
  })
})

// The chaos framework's crash-drill and claim-lease results, as it writes them
// (fixtures/drills, the studio's ACEMQ_STUDIO_DRILL_REPORTS in scripts/e2e.sh):
// a table each in Delivery evidence, whatever run is selected, and only the
// table scrolls on a phone.
test.describe('delivery evidence shows the process drills', () => {
  for (const width of WIDTHS) {
    test(`crash drill and claim lease at ${width} px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/console/#evidence')
      await expect(page.locator('#crashBody table tbody tr')).toHaveCount(5)
      await expect(page.locator('#claimBody table tbody tr')).toHaveCount(6)
      await expect(page.locator('#crashBody')).toContainText('accounted for')
      await expect(page.locator('#claimBody')).toContainText('REFUSED')
      await expect(page.locator('#evDrills')).not.toContainText('coming')
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth)
      expect(overflow).toBeLessThanOrEqual(0)
    })
  }
})
