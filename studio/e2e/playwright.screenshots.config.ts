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

import { defineConfig, devices } from '@playwright/test'

/**
 * The capture run that produces the pictures in the studio guide.
 *
 * Separate from playwright.config.ts on purpose. The end-to-end suite runs on
 * every push and must stay fast; this one drives two full measured runs and
 * writes files into docs/assets, which is not something CI should be doing
 * behind somebody's back. Started by scripts/screenshots.sh, which also starts
 * the broker and the studio.
 *
 * Everything that would otherwise differ between two capture runs is pinned
 * here: the viewport, the device scale, the locale and the time zone. What is
 * left varying is the measurement itself, because these are photographs of a
 * real run rather than drawings of one.
 */
export default defineConfig({
  testDir: './screenshots',
  // One browser, one test, in order. The studio allows one run at a time and
  // the shots build on each other: the comparison needs the two runs above it.
  workers: 1,
  fullyParallel: false,
  // Two measured runs plus the designing in between.
  timeout: 15 * 60_000,
  expect: { timeout: 30_000 },
  reporter: [['list']],
  retries: 0,

  use: {
    baseURL: process.env.STUDIO_URL ?? 'http://127.0.0.1:8751',
    // The pictures are the output; a failure screenshot would land in
    // test-results and confuse the two.
    screenshot: 'off',
    trace: 'off',
    video: 'off',
    // The history table prints a locale timestamp. Pinning both keeps the
    // format stable, even though the instant itself cannot be.
    locale: 'en-GB',
    timezoneId: 'UTC',
    // Stops the status dot pulsing, so it is captured at a fixed opacity
    // rather than wherever the animation happened to be.
    reducedMotion: 'reduce',
  },

  projects: [
    {
      name: 'chromium',
      // The frame goes here rather than in `use` above: a project's block
      // replaces the shared one field by field, and Desktop Chrome carries a
      // 1280x720 viewport at 1x that would otherwise win.
      use: {
        ...devices['Desktop Chrome'],
        // fitView zooms the canvas to the space it is given, so the viewport
        // is what decides whether the topology is readable.
        viewport: { width: 1440, height: 900 },
        // Retina, because the site is read on retina screens and a 1x capture
        // of 12px labels is a smear. SHOT_SCALE=1 for smaller files.
        deviceScaleFactor: Number(process.env.SHOT_SCALE ?? 2),
      },
    },
  ],
})
