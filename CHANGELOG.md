# Changelog

All notable changes to libtray will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- Repository scaffold: Apache 2.0 license, Gradle/Kotlin build,
  Java 22 toolchain (Project Panama floor — `java.lang.foreign`
  finalized as JEP 454), gradle wrapper.
- Public API surface in `dev.hivens.libtray`:
  `Tray` interface (open/close lifecycle, icon + tooltip + menu
  setters, event subscription), `TrayBuilder` (immutable construction
  parameters), `TrayEvent` sealed hierarchy
  (`Activated` / `MiddleActivated` / `MenuRequested` /
  `MenuItemSelected`), `TrayMenu` + `TrayMenuItem` sealed model
  (`Standard` / `Submenu` / `Separator`).
- `Tray.create(builder)` factory that detects the host OS and dispatches
  to the matching backend; returns null when no backend is available
  rather than throwing, mirroring the `IKeyringStorage` pattern from
  Aura — degrade gracefully so callers can fall back to a no-tray UX.
- Linux backend: StatusNotifierItem over D-Bus
  (`org.kde.StatusNotifierItem`) + DBusMenu (`com.canonical.dbusmenu`)
  for the right-click menu. Pure Panama bindings to libdbus; no GTK,
  no GLib runtime dependency. Works under KDE Plasma, GNOME with the
  SNI extension, Hyprland (via waybar / similar), Cinnamon, Budgie.
- Stub backends for Windows and macOS that report `isAvailable=false`
  until the real implementations land — the factory's dispatch
  mechanism is in place from day one so the eventual swap is
  implementation-only.
- Basic CI workflow: build + test on Linux runners. Windows / macOS
  CI matrices add when the platform backends land.
