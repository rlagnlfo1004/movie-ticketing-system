import {chromium} from 'playwright';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';
import {mkdir} from 'node:fs/promises';

const here = dirname(fileURLToPath(import.meta.url));
const pageUrl = process.env.DEMO_URL || new URL('../../reservation-api/src/main/resources/static/demo/index.html', import.meta.url).href;
const output = resolve(here, '../../docs/screenshots/demo');
await mkdir(output, {recursive: true});

const browser = await chromium.launch({headless: true});
try {
  for (const [label, width, height] of [['desktop', 1440, 900], ['mobile', 390, 844]]) {
    const context = await browser.newContext({viewport: {width, height}, deviceScaleFactor: 1});
    const page = await context.newPage();
    await page.goto(pageUrl);
    await page.locator('#seat-map .seat').last().waitFor();
    const shot = async (name) => page.screenshot({path: resolve(output, `${label}-${name}.png`), fullPage: true, animations: 'disabled'});

    await page.locator('#queue-button').click();
    await page.waitForTimeout(500);
    await shot('01-queue');
    await page.evaluate(() => window.demo.enterSeats());
    await shot('02-seats');
    await page.locator('[data-seat="A-1"]').click();
    await page.locator('[data-seat="A-2"]').click();
    await page.locator('#hold-button').click();
    await page.locator('#pay-button:not([hidden])').waitFor();
    await shot('03-hold');
    await page.locator('#pay-button').click();
    await page.locator('#result-title').getByText('예매가 완료됐어요').waitFor();
    await shot('04-confirmed');

    await page.locator('#restart-button').click();
    await page.evaluate(() => window.demo.enterSeats());
    await page.locator('[data-seat="A-1"]').click();
    await page.locator('#hold-button').click();
    await page.evaluate(() => window.demo.expireHold());
    await page.locator('#result-title').getByText('선점 시간이 만료됐어요').waitFor();
    await shot('05-expired');

    // Two separate browser contexts contend for the same seat through one shared mock claim.
    const claimed = new Set();
    const rivalContext = await browser.newContext({viewport: {width, height}, deviceScaleFactor: 1});
    await context.exposeFunction('demoClaimSeat', async (seats) => {
      if (seats.some((seat) => claimed.has(seat))) return {ok: false};
      seats.forEach((seat) => claimed.add(seat));
      return {ok: true};
    });
    await rivalContext.exposeFunction('demoClaimSeat', async (seats) => {
      if (seats.some((seat) => claimed.has(seat))) return {ok: false};
      seats.forEach((seat) => claimed.add(seat));
      return {ok: true};
    });
    const rival = await rivalContext.newPage();
    await rival.goto(pageUrl);
    await rival.evaluate(() => window.demo.enterSeats());
    await rival.locator('[data-seat="A-1"]').click();
    await rival.locator('#hold-button').click();
    await rival.locator('#pay-button:not([hidden])').waitFor();
    await page.locator('#restart-button').click();
    await page.evaluate(() => window.demo.enterSeats());
    await page.locator('[data-seat="A-1"]').click();
    await page.locator('#hold-button').click();
    await page.locator('#booking-message').getByText(/SEAT_TAKEN/).waitFor();
    await shot('06-seat-taken');
    await rivalContext.close();

    await page.locator('#admin-tab').click();
    await page.locator('#admin-view:not([hidden])').waitFor();
    await shot('07-admin');
    await context.close();
  }
} finally {
  await browser.close();
}
console.log(`Screenshots saved to ${output}`);
