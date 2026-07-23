# TODO

## Issues

1. Setup EPG dialog can lose focus to the "Continue" button behind it.
   - Scenario: setup import succeeds and sets `ImportState.Success`, then immediately sets `EpgSyncUi.Ask`.
   - Current risk: `ImportProgressScreen` focuses the success "Continue" button immediately, while `EpgSyncDialog` requests focus after a 50 ms delay. During that window, pressing Enter can finish setup and exit before the user syncs EPG.
   - User-visible result: the EPG prompt appears but can be accidentally bypassed; D-pad navigation may also reach controls behind the overlay because the dialog is not the only rendered surface.
   - Preferred fix: in `SetupWizard`, render only `EpgSyncDialog` while `epgSync` is not `Hidden`, matching the `ManageSourcesScreen` pattern. Remove the standalone overlay render after the `IMPORTING` case.

2. Setup can prompt for EPG sync before Live channels finish importing.
   - Scenario: user selects only Movies during Xtream setup. Movies sync completes, Live and Series are queued as background remainder work, then the EPG prompt appears immediately.
   - Current risk: if the user taps "Sync now" before Live channels exist, EPG sync may store the whole guide unfiltered; if Live is only partially imported, the EPG may be filtered to a partial channel set.
   - User-visible result: Guide can temporarily show no channels or a misleading "guide ids don't match" message until Live completes or EPG is re-synced.
   - Preferred fix: only show or run the semi-auto EPG prompt after Live sync has completed for the source, especially when Live was not part of the foreground setup import.
