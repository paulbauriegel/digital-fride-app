# Project instructions for Claude Code

## Device safety — never run `adb shell pm clear`

Never run `adb shell pm clear com.example.groceriesapp`, or any other command that resets/uninstalls
app data, on a connected device. This app stores all data locally under `filesDir/groceries/` with
no cloud sync — the connected phone holds the only copy of the user's real receipts and inventory.
On 2026-08-07 this was run to force a "clean" reinstall during manual verification and permanently
destroyed a real, locally-scanned receipt (unrecoverable) plus other inventory data.

- `adb install -r` is sufficient for reinstalling a debug build and preserves app data. Use that.
- If a genuinely fresh-install state is needed for testing, back up first:
  `adb exec-out run-as com.example.groceriesapp tar c files/groceries > backup.tar`
  and restore afterward — or ask the user before doing anything destructive.
- This rule applies to any equivalent destructive action: `pm clear`, `pm uninstall`, deleting
  `files/groceries/` via `adb shell rm`, etc.
