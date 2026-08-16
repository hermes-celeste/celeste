# CF-10 — Mobile privacy controls

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-10-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-10-mobile-privacy-controls.md`

## Recorded investigation

Celeste currently requests only `INTERNET`; the manifest has no biometric, notification, or privacy-lock implementation (`app/src/main/AndroidManifest.xml:1-24`). The app targets SDK 37, supports min SDK 28, and has no AndroidX Biometric dependency in the recorded snapshot (`app/build.gradle.kts:10-20,56-85`).

Current lifecycle forwarding is a Compose `LifecycleEventObserver`: `ON_START` calls `CelesteViewModel.onForeground()` and `ON_STOP` calls `onBackground()` (`app/src/main/java/dev/hazydreams/hermesceleste/MainActivity.kt:52-72`). `onBackground()` currently only snapshots rotated provider cookies, while `onForeground()` health-checks/reconnects the active gateway and reconciles the stored session (`app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:771-816`). Cold start immediately restores the saved connection in the ViewModel initializer (`app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:96-124`).

Existing connection protection is deliberately different: `AndroidConnectionStore` encrypts reusable authentication with an Android Keystore AES-GCM key configured with `setUnlockedDeviceRequired(true)` (`app/src/main/java/dev/hazydreams/hermesceleste/connection/AndroidConnectionStore.kt:118-172`).

Hermes Conduit is comparison evidence, not protocol authority. Its Face ID path is an optional gate for a saved credential: `BiometricAuth.authenticate` requests `deviceOwnerAuthentication`, which permits the device passcode recovery path (`Hermes Conduit snapshot: Conduit/Services/BiometricAuth.swift:8-29`); the login UI makes “Save credentials” and “Use Face ID” separate choices and tells users that passcode recovery remains available (`Hermes Conduit snapshot: Conduit/Views/LoginView.swift:107-128`); `DashboardCredentials` carries `requiresFaceID` as credential policy metadata (`Hermes Conduit snapshot: Conduit/Services/NativeAuthClient.swift:10-17`). Conduit also treats scene transitions as reconciliation boundaries and invalidates/cancels background work before a later active refresh (`Hermes Conduit snapshot: Conduit/Views/RootView.swift:10-35`; `Hermes Conduit snapshot: Conduit/Services/AppState.swift:3651-3723`). The inspected Conduit evidence does not establish an Android-equivalent content lock or screenshot guarantee.

The recorded Android source audit found that `BiometricPrompt` is a system prompt, is dismissed when its client leaves the foreground, and reports cancellation, lockout, and device-credential outcomes. The AndroidX builder records unsupported authenticator combinations on API 28–29, while `BiometricManager.canAuthenticate` reports runtime availability.

## Recorded Android references

[1] https://developer.android.com/reference/androidx/biometric/BiometricPrompt
[2] https://developer.android.com/reference/androidx/biometric/BiometricPrompt.PromptInfo.Builder
[3] https://developer.android.com/reference/androidx/biometric/BiometricManager
[4] https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder
[5] https://developer.android.com/reference/android/view/WindowManager.LayoutParams
[6] https://developer.android.com/reference/android/app/Activity
[7] https://developer.android.com/guide/components/activities/activity-lifecycle
[8] https://developer.android.com/reference/android/app/Notification
[9] https://developer.android.com/reference/android/app/NotificationChannel

## Unresolved source and behavior questions

- On which OEM/API combinations does `setRecentsScreenshotEnabled(false)` produce a reliable recents background?
