# Home Hero Overlay Stats Plan

## Summary

Add a small stat line to the expanded Home hero overlay:

- VOD movie and episode heroes show `Finish by <time>`.
- Play Next episodes are included; their `positionMs = 0` means "if started now".
- Live heroes show relative copy like `3 hours ago`.
- The stat is computed only inside the expanded overlay branch, with no global ticking timer.

## Implementation Changes

- Update `app/src/main/java/tv/own/owntv/features/home/HomeScreen.kt`.
- Add small pure helpers near the Home hero composables:
  - `finishByLabel(context, positionMs, durationMs, nowMs)`
  - `relativeLastWatchedLabel(lastEngagementAt, nowMs)`
  - `roundUpToNextQuarterHour(ms)`
- Insert the stat `Text` between the existing subtitle block and CTA button in the expanded overlay `Column`.
- Use `DateFormat.is24HourFormat(context)` so finish times follow the device 12/24-hour preference.
- Compute `statText` only for the currently expanded hero item.

## Locked Behavior

- Round finish time up to the next local 15-minute clock boundary.
- Do not advance the finish time if it is already exactly on a 15-minute boundary.
- Use `Finish by <time>` for movies, resumed episodes, and Play Next episodes.
- Omit the VOD stat when `durationMs <= 0`.
- Clamp `positionMs < 0` to `0`.
- Clamp `positionMs > durationMs` to `durationMs`.
- Omit the VOD stat when remaining time is `0`.
- Live relative thresholds:
  - `<60s` or future timestamp: `Just now`
  - `<60m`: `N minute(s) ago`
  - `<24h`: `N hour(s) ago`
  - `>=24h`: `N day(s) ago`

## Test Plan

- Run `./gradlew compileDebugKotlin`.
- Manually verify:
  - resumed movie hero shows `Finish by <time>`,
  - resumed episode hero shows `Finish by <time>`,
  - Play Next episode hero shows `Finish by <time>` from full duration,
  - live hero shows relative last-watched text,
  - missing or invalid VOD duration omits the stat,
  - completed or over-clamped VOD omits the stat,
  - hero focus, preview, CTA, and progress bar behavior remain unchanged.

## Assumptions

- The stat belongs only on the expanded hero overlay, not collapsed cards or continue-watching rows.
- Existing modified files in the worktree are unrelated user changes and must not be reverted.
