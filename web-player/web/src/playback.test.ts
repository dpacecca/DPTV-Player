import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bufferPreset, RateMeter, reconnectDelayMs, resolutionLabel, streamChips } from './playback.ts';

test('chips read as on the TV: aspect, resolution, FPS, Mbps, audio', () => {
  const r = streamChips({ width: 1920, height: 1080, fps: 50, bitsPerSecond: 8_240_000, audioChannels: 2 });
  assert.equal(r.ultraHd, false);
  assert.deepEqual(r.chips, ['16:9', '1080p', '50 FPS', '8.2 Mbps', 'STEREO']);
});

test('4K is the badge, not a chip', () => {
  const r = streamChips({ width: 3840, height: 2160, fps: 59.94, audioChannels: 6 });
  assert.equal(r.ultraHd, true);
  assert.deepEqual(r.chips, ['16:9', '60 FPS', '5.1']);
});

test('anamorphic SD shows the shape it is drawn in', () => {
  // 720×576 with 64:45 pixels is a 16:9 picture.
  assert.deepEqual(streamChips({ width: 720, height: 576, sarNum: 64, sarDen: 45 }).chips, ['16:9', '480p']);
  assert.deepEqual(streamChips({ width: 720, height: 576 }).chips, ['1.25:1', '480p']);
});

test('a cropped cinemascope frame counts by its width', () => {
  assert.equal(resolutionLabel(1920, 800), '1080p');
  assert.equal(resolutionLabel(3840, 1600), '4K');
});

test('unknown facts make no chips', () => {
  assert.deepEqual(streamChips({}), { ultraHd: false, chips: [] });
});

test('an unknown buffer choice falls back to the default', () => {
  assert.equal(bufferPreset('nonsense').mode, 'low');
  assert.equal(bufferPreset('mobile').startSec, 10);
});

test('reconnects back off to at most 10 s', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6].map(reconnectDelayMs), [1000, 2000, 4000, 8000, 10000, 10000]);
});

test('the rate meter skips the first seconds and averages the rest', () => {
  const m = new RateMeter(0, 10_000, 5_000);
  m.add(1_000, 5_000); // still filling the buffer: ignored
  assert.equal(m.bitsPerSecond(), undefined);
  for (let t = 6_000; t <= 9_000; t += 1_000) m.add(t, 1_000);
  assert.equal(m.bitsPerSecond(), 1_000 * 1024 * 8);
});
