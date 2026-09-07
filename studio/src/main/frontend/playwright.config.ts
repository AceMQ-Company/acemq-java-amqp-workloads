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
 * The end-to-end tests: a real browser, the real jar, a real broker.
 *
 * These exist because every interface bug this project has had was found by a
 * person clicking. A chart squeezed to two hundred pixels, a page that would not
 * scroll, a media query that caught a screen it was not meant for — none of them
 * are visible to a component test in jsdom, which has no layout at all.
 *
 * The studio and the broker are started by whoever runs this (the `ui` job in
 * CI, or `scripts/e2e.sh` by hand), because starting a broker is not something a
 * test runner should be doing.
 */
export default defineConfig({
  testDir: './e2e',
  // A run takes seconds and the studio allows one at a time, so these are
  // deliberately serial. Parallel tests would fight over the one run slot and
  // fail for a reason that has nothing to do with the interface.
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  retries: process.env.CI ? 1 : 0,

  use: {
    baseURL: process.env.STUDIO_URL ?? 'http://127.0.0.1:8749',
    // Kept only for a failure: a screenshot of a passing test is a megabyte
    // nobody looks at, and a trace of a failing one is the whole story.
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
