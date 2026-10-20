# ConnectorX integration

This fork is used as the `third_party/scrcpy` submodule of ConnectorX.
Base: `19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`; the server version is defined in `meson.build`.

ConnectorX integration changes:

- Server: optional audio termination is isolated from video/control when the ConnectorX environment flag is set.
- DesktopConnection: verify local bridge UID/PID.
- DisplayManager and NewDisplayCapture: delegate virtual-display creation and readiness to VirtualAppHooks.
- Device and ActivityManager: return application start/force-stop results instead of reporting false success.
- `server/src/main/java/io/github/recrivenvi/connectorx/runtime/VirtualAppHooks.java`: requested refresh rate, application launch and display metadata. Full class name is retained; absent ConnectorX environment leaves the ordinary path active.
- `server/src/main/java/io/github/recrivenvi/connectorx/runtime/LocalDisplayController.java`: direct Surface backend for same-device virtual applications, reusing scrcpy display wrappers and targeted input injection without media encoding or transport.
- Local performance extension: complete multi-pointer samples with recycled MotionEvents, reusable pointer storage, independent wheel scheduling and input/lifecycle locks, platform-following display refresh and diagnostic counters. `WheelTimeline.java` supplies monotonic absolute deadlines, skipped-tick accounting and bounded catch-up, without a parent protocol dependency. Target app launch/polling never owns the input lock.
- Wheel scheduling uses `WheelTimeline.DEFAULT_UNITS_PER_SECOND`; the parent local control shares this default. Absolute deadlines and bounded catch-up do not change ordinary scrcpy wheel messages.
- Local IME integration: LOCAL places the native keyboard inside the virtual display. Android supplies real IME Insets to the target. The outer preview and virtual resolution remain fixed. Ordinary display-targeted touch is retained without explicit task/display focus overrides.
- Local decorated displays omit ALWAYS_UNLOCKED only when system decorations are enabled. AOSP android-16.0.0_r3 KeyguardDisplayManager hides secondary navigation bars before testing whether a KeyguardPresentation is allowed, but restores only displays recorded in its presentation list. Allow the ordinary lock/unlock lifecycle without recreating the display/task, injecting Insets, or changing undecorated/remote displays. Device acceptance is recorded in the parent `documents/design/mechanism-local_display.md`. Source: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r3/packages/SystemUI/src/com/android/keyguard/KeyguardDisplayManager.java
- `LocalImeController.java` / `LocalImeBinding.java`: on-demand bounded IMMS snapshots check current UID/display before calling the system StatusBar IME-hide path; the helper then confirms hidden state within a bounded interval. No dump is logged/persisted and no synthetic Insets are installed. The parent sequences Back from this result, not the physical host's Insets. Basis: AOSP android-16.0.0_r3 StatusBarManagerService/InputMethodManagerService; shell execution verified on shennong for fallback and local keyboards. The current-user API does not provide atomic task ownership, and OEM formats/APIs can fail closed. Ordinary scrcpy/remote sessions are unchanged.

ConnectorX compiles these Java/AIDL sources through its `core/privilege` module. The hook resides in this fork so the patched server does not depend on a Java source file located outside this repository. ConnectorX-specific Binder services, device identity, TLS and UI remain in the parent project. Its complete integration inventory is `documents/reference/upstream-scrcpy.md` in the parent repository.

The upstream license and copyright notices are unchanged. This file records integration provenance; it does not authorize commits, pushes, or further feature work.
