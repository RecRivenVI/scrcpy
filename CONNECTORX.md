# ConnectorX integration

This fork is used as the `scrcpy` submodule of ConnectorX.
Base: `19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac` (server 4.1).

ConnectorX integration changes:

- Server: optional audio termination is isolated from video/control when the ConnectorX environment flag is set.
- DesktopConnection: verify local bridge UID/PID.
- DisplayManager and NewDisplayCapture: delegate virtual-display creation and readiness to VirtualAppHooks.
- Device and ActivityManager: return application start/force-stop results instead of reporting false success.
- `server/src/main/java/io/github/recrivenvi/connectorx/runtime/VirtualAppHooks.java`: requested refresh rate, application launch and display metadata. Full class name is retained; absent ConnectorX environment leaves the ordinary path active.

ConnectorX compiles these Java/AIDL sources through its `runtime/Android` module. The hook resides in this fork so the patched server does not depend on a Java source file located outside this repository. ConnectorX-specific Binder services, device identity, TLS and UI remain in the parent project.

The upstream license and copyright notices are unchanged. This file records integration provenance; it does not authorize commits, pushes, or further feature work.
