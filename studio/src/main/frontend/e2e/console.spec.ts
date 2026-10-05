import { expect, test } from '@playwright/test'

/**
 * The workloads console at /console, in a browser.
 *
 * Its layout is fluid by design, from a phone to a wall screen, and the one
 * failure that design exists to prevent is a page that scrolls sideways. Each
 * view is opened at four widths and the page must fit; only tables, code and
 * charts may scroll, inside their own containers.
 */

const WIDTHS = [375, 768, 1280, 1920]
const VIEWS = ['loads', 'soak', 'evidence', 'studio']

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
      }
    })
  }

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
