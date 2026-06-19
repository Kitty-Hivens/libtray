# Changelog

All notable changes to libtray will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [0.1.2]

### Fixed
- Linux: the SNI backend opens a private D-Bus connection
  (`dbus_bus_get_private`) instead of the process-shared `dbus_bus_get`
  one. A shared connection has a single incoming-message queue; when
  another libdbus user in the same process runs its own
  `dbus_connection_pop_message` loop (for instance a sibling
  notification library), it could pop -- and discard -- the tray host's
  property queries before this backend's pump saw them, so the icon
  never rendered on startup and only appeared after the tray host was
  restarted. A private connection is drained solely by our own pump.
- Linux: `exit_on_disconnect` is turned off on the connection. libdbus
  defaults it on, which `_exit()`s the whole process if the session bus
  drops; the pump loop now idles on a dead connection instead of taking
  the host application down with it.
- Linux: `close()` closes the private connection before the final unref,
  as the private-connection contract requires.

## [0.1.1]

### Fixed
- Linux: the `DBusMessageIter` scratch buffer was 64 bytes, but the
  struct is 72 on x86_64 / aarch64 -- libdbus wrote its trailing pointer
  8 bytes past the allocation on every `dbus_message_iter_*` call,
  silently corrupting adjacent arena memory. Reserved 80.
- Linux: unhandled D-Bus method calls now receive an
  `org.freedesktop.DBus.Error.UnknownMethod` reply instead of silence.
  A caller that probes an object before subscribing otherwise blocked to
  its own ~25 s timeout. `Properties.Get` / `GetAll` for a foreign
  interface returns `UnknownInterface` rather than a mistyped empty
  reply, and method dispatch is filtered by object path (SNI on
  `/StatusNotifierItem`, dbusmenu on `/MenuBar`).
- Linux: `DBusError` is freed on the `dbus_bus_get` /
  `dbus_bus_request_name` failure paths and after `registerWithWatcher`
  -- libdbus heap-allocates the error's `name` / `message`, which the
  confined arena does not own. `readBasicString` now also accepts
  OBJECT_PATH and SIGNATURE, not only STRING.
- Linux: `close()` drains the outgoing queue after both worker threads
  join. The pump thread (joined second) could enqueue a reply after the
  sender thread's final drain, leaking that libdbus message.
- Linux / Windows: the per-instance Panama arena (library lookup +
  downcall handles, and the Win32 reusable `NOTIFYICONDATA`) is closed
  on `close()`, so repeated create/close cycles no longer leak native
  memory. macOS keeps its arena for the process lifetime -- the new
  main-queue path (below) can hold deferred references to it.
- macOS: NSMenu, NSMenuItem, child NSMenu and NSImage objects no longer
  leak one reference each on every `setMenu` / `setIcon`. Alloc-owned
  objects are released after the call that retains them (the menu had an
  extra `objc_retain` on top of the alloc +1 with only one release; the
  items and the image were never released at all).
- macOS: the text bullet fallback is cleared once a real icon installs.
  Ventura+ `NSStatusBarButton` renders both an image and a title, which
  left a stray dot beside the icon.
- Windows: closing while a context menu is open no longer hangs for the
  menu's lifetime. `close()` posts `WM_CANCELMODE` to the owner window
  so the tracking `TrackPopupMenu` returns and the pump thread can then
  process `WM_CLOSE`.

### Added
- macOS: mutating calls (`setIcon` / `setTooltip` / `setMenu`) marshal
  onto the Cocoa main queue via libdispatch (`dispatch_async_f`); a call
  already on the main thread runs inline. AppKit now always runs on the
  main thread regardless of which thread the consumer calls from (#3).
- Linux: the item re-registers with the StatusNotifierWatcher when the
  tray host restarts (subscribes to `NameOwnerChanged`). Previously the
  icon vanished permanently on a shell / tray-widget restart, and an
  item created before any watcher existed never recovered (#10).

### Changed
- `SmokeMain` moved into the test source set so it no longer ships in
  the published library jar.

## [0.1.0]

### Fixed
- macOS backend: no longer crashes a host UI toolkit on tray creation
  (#5). When a host (JavaFX/Glass, Compose/Skiko, AWT) already owned
  NSApplication and was running its event loop, libtray re-ran
  `[NSApp finishLaunching]` and flipped the activation policy to
  Accessory; on JavaFX/Intel this destabilised Glass's CVDisplayLink
  pulse timer and crashed the JVM with a SIGSEGV in `objc_msgSend`.
  The NSApp bootstrap is now gated on `[NSApp isRunning]`: host-owned
  apps get just the status item, while libtray still bootstraps NSApp
  when it owns it (headless / smoke).
- Windows backend: tray icon no longer renders upside down (#4). The
  PNG-to-HICON path was flipping the color rows into bottom-up order
  before `CreateIcon`. `CreateIcon` builds DDBs (via `CreateBitmap`),
  whose scanlines run top-to-bottom; bottom-up is the DIB convention,
  not the DDB one. Rows are now fed top-down, matching what the shell
  expects. The bug stayed invisible on vertically near-symmetric
  glyphs and only showed on asymmetric icons.
- Linux backend: `dbus_connection_flush` no longer runs on the caller
  thread. Public mutators (`setTooltip`, `setMenu`, `setIcon`) enqueue
  the outgoing message on a `LinkedBlockingQueue<MemorySegment>`; a
  dedicated `libtray-sni-sender-<pid>` daemon thread drains the queue
  and performs the blocking `send` + `flush` + `unref` triplet there.
  A multi-signal burst (e.g. `setMenu` emits `LayoutUpdated`, a
  following `setTooltip` emits `NewTitle` + `NewToolTip`) previously
  stalled the caller for over a second on a busy session bus -- in
  UI-toolkit consumers (AWT/EDT, Compose Desktop's Swing dispatcher,
  JavaFX) this manifested as full-window freezes.

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
