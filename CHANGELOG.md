# Changelog

All notable changes to libtray will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

This release reworks the public API for Java callers and the threading
of event delivery. It breaks source and binary compatibility in the
places listed under Changed, and the README has a migration section.

### Added
- `TrayBuilder.linuxBusName`: the well-known D-Bus name the Linux
  backend requests for its StatusNotifierItem. Null keeps the generated
  `org.kde.StatusNotifierItem-PID-N`. A Flatpak app can set a name under
  its own app ID, which the sandbox lets it own by default, and then
  needs only `--talk-name=org.kde.StatusNotifierWatcher` instead of
  `--own-name=org.kde.*`. The name must be free on the session bus, so a
  second instance asking for the same one gets no tray.
- `Tray.onEvent(executor, listener)`: each event is handed to the
  executor instead of run on the event thread, so a consumer can receive
  events straight on its UI thread (`Platform::runLater`,
  `SwingUtilities::invokeLater`, `Dispatchers.Main.asExecutor()`).
- `TrayBuilder.of(title, iconBytes)` returns a fluent `TrayBuilder.Builder`
  for Java, with one setter per optional field and the constructor's
  defaults. A field added later becomes another setter, so Java call
  sites stop breaking whenever `TrayBuilder` grows.
- Java conveniences that change nothing for Kotlin: `Tray.create` is
  `@JvmStatic`, `TrayMenuItem.Standard` and `Submenu` have
  `@JvmOverloads`, and `TrayMenu` has a varargs constructor.
- Windows fires `TrayEvent.MenuRequested` when the user asks for the
  menu.
- macOS delivers clicks on the status item. The status button gets its
  own target-action, so a middle click fires `MiddleActivated` and a right
  click or Control-click opens the menu and fires `MenuRequested`.
  `TrayBuilder.macosMenuOnPrimaryClick` decides the primary click: true,
  the default, keeps opening the menu as Mac users expect, and false
  fires `Activated` instead. The menu is attached to the status item only
  while it is shown, and it now opens when the button is released rather
  than when it is pressed.

### Changed
- **Breaking:** events are delivered on one libtray-owned event thread
  per tray, in firing order, instead of on the thread the backend
  received them on (the D-Bus I/O thread, the Win32 message pump, the
  Cocoa main thread). A listener that blocks now delays later events for
  its tray and nothing else: it can no longer hold up replies to the tray
  host or keep `close()` from releasing the D-Bus connection. A macOS
  listener that touched AppKit relying on being on the main thread has
  to register with an executor now.
- **Breaking:** `Tray.onEvent` takes a `TrayEventListener` (a
  `fun interface`) and returns a `TraySubscription` instead of a
  `() -> Unit`. Kotlin lambdas and function-typed values still convert,
  so only code that stores or invokes the returned handle changes:
  `unsubscribe()` becomes `subscription.close()`. Java no longer returns
  `Unit.INSTANCE` from the listener, and `TraySubscription.close()`
  declares no checked exception. A class that implements `Tray` itself,
  such as a test double, has to implement both `onEvent` overloads.
- **Breaking:** `TrayMenuItem.Submenu` takes `items` before `enabled`,
  matching `Standard`, where `enabled` is the trailing default.
  Positional calls that passed `enabled` third have to move it.
- **Binary-incompatible:** `TrayBuilder` gained two properties,
  `linuxBusName` and `macosMenuOnPrimaryClick`, so its constructor and
  generated `copy` changed shape. Kotlin source is unaffected, but a jar compiled against 0.1.3 that relies on the
  default arguments or calls `copy` hits `NoSuchMethodError`, and Java
  callers of the five-argument constructor stop compiling. Java should
  move to `TrayBuilder.of`.
- `TrayEvent.MenuRequested` means that the user asked for the menu, with
  timing explicitly best effort, instead of being a Linux-only echo of
  the SNI `ContextMenu` call. On Linux it stays host-dependent: a host
  that renders the dbusmenu itself may never call `ContextMenu`.
- `TrayBuilder.title` no longer claims to be the Linux bus name suffix.
  It never was: the backend derives only the SNI `Id` from it.

### Fixed
- macOS: `close()` runs its teardown on the Cocoa main queue and waits up
  to two seconds for it, then falls back to the calling thread.
  `NSStatusItem` is main-thread-only and `close()` is usually called from
  a listener, which now runs on the event thread. Queued behind the main
  queue, the teardown also no longer races a `setIcon` or `setMenu` that
  is already executing there.
- Windows opens the context menu on `WM_CONTEXTMENU` when the shell
  accepted `NOTIFYICON_VERSION_4`, and on `WM_RBUTTONUP` only otherwise.
  A version 4 shell sends both for one right click, and both were routed
  to the popup.
- Linux: the `DBusMessageIter` scratch is declared as ten longs instead
  of eighty bytes. A byte sequence carries alignment 1 while libdbus
  stores pointers in the struct, and it only worked because malloc hands
  back 16-aligned blocks.

## [0.1.3]

### Added
- `TrayBuilder.maxIconSize`: icons longer than this on either edge are
  scaled to fit (aspect ratio preserved, re-encoded as PNG, logged at
  WARN with the before/after sizes) before any backend sees them, on the
  initial icon and on every `setIcon`. Defaults to 256, which is the
  largest size the Win32 backend accepts and past which no tray host
  renders more detail. Set it to null to send the original bytes. A
  1024x1024 icon on Linux was 4 MiB re-marshalled into the `IconPixmap`
  property on every host query; scaled, that is 256 KiB and
  `Properties.GetAll` answers in 40-46 ms instead of 744-1816 ms.

### Changed
- **Binary-incompatible:** `TrayBuilder` gained a fifth property, so its
  constructor and generated `copy` changed shape. Kotlin code recompiled
  against 0.1.3 is unaffected, but a jar compiled against 0.1.2 that
  relies on the default arguments hits `NoSuchMethodError`, and Java
  callers of the four-argument constructor stop compiling. Recompile
  downstream consumers. (Pre-1.0: the API can still shift.)
- `TrayEvent` documents per-backend coverage instead of promising that
  every backend fires at least `Activated`. It does not: macOS routes the
  primary button into the `NSStatusItem` menu and libtray installs no
  target-action on the status button, so no click reaches the consumer
  there. `MenuRequested` is Linux-only, and its description no longer
  claims it can be used to rebuild a menu lazily -- the host renders from
  the layout it already fetched, so the event arrives alongside the menu,
  not before it. `MenuItemSelected` is the only event all three deliver.
- Linux: clicking a submenu parent no longer fires `MenuItemSelected`.
  Windows returns the submenu rather than a command from
  `TrackPopupMenu`, and an AppKit submenu parent carries no action, so
  the event only ever existed on Linux.

### Fixed
- Linux: replies to the tray host went out up to a full second late. The
  backend drained its outgoing queue on a thread separate from the one
  polling the socket, but libdbus serialises all socket work behind a
  per-connection io-path lock that `dbus_connection_read_write` holds for
  the whole of its blocking poll. Every `dbus_connection_flush` from the
  sender thread therefore waited that poll out, so a property query took
  ~1 s to answer and a right-click -- which costs the host an
  `AboutToShow` plus a `GetLayout` -- took ~2 s to open the menu. Polling
  and sending now share one thread that drains the queue between poll
  iterations; measured against an isolated session bus, `GetLayout` and
  `Properties.GetAll` drop from 1003-1017 ms to the 2-16 ms bus
  round-trip. State changes pushed by the caller (`setTooltip`,
  `setIcon`, `setMenu`) are sent within the poll interval, now 100 ms.
- Linux: the `IconPixmap` property is marshalled with
  `dbus_message_iter_append_fixed_array` instead of one
  `append_basic` per byte. The old path cost one FFM downcall per
  channel byte -- 16 thousand for a 64x64 icon, 4.2 million for a
  1024x1024 one -- on every property query the host makes. A 1024x1024
  icon's `Properties.GetAll` drops from 744-1816 ms to 549-594 ms; the
  remainder is the 4 MiB itself crossing the bus, which is a reason to
  hand the tray a small icon rather than a full-resolution one.
- Linux: `Event(id, "clicked")` for a separator or a disabled entry no
  longer reaches the consumer. Node ids are public over the bus and a
  host can send any of them, and neither shape is selectable on Windows
  or macOS. A separator previously arrived as `MenuItemSelected("---")`.
- Linux: the `DBusError` used for the `NameOwnerChanged` match rule is
  freed, and the bindings arena is released when `create()` fails after
  loading libdbus (no session bus, or the well-known name is refused) --
  each failed attempt previously leaked the library lookup plus a
  downcall handle per bound symbol. The connection is also closed, not
  just unrefed, on the name-request failure path.
- macOS: `TrayMenuItem.enabled = false` now actually greys the item out.
  `NSMenu.autoenablesItems` defaults to YES, which makes AppKit
  recompute enablement from the responder chain at display time and
  overwrite `setEnabled:NO` for every item whose action the menu target
  implements -- so disabled entries rendered as normal clickable ones,
  unlike on Linux and Windows.
- macOS: an AppKit call marshalled onto the main queue re-checks the open
  flag before running, so a queued `setIcon` / `setMenu` that has not
  started by the time `close()` releases the `NSStatusItem` no longer
  messages a deallocated object. It does not close the window entirely:
  `close()` still runs on the caller's thread, so an action already
  executing when it lands can still race. Calling `close()` from the same
  thread that drives the tray avoids that.
- macOS: the Objective-C class and selector caches are concurrent maps.
  `close()` resolves both from the caller's thread while queued actions
  resolve them on the main queue; two threads growing a plain `HashMap`
  can drop entries or spin inside a resize.
- macOS: the per-call `applyIcon` diagnostics log at debug rather than
  info -- a consumer animating the tray glyph got three info lines per
  frame.
- Windows: middle-click fires `TrayEvent.MiddleActivated`, which the
  event's own documentation already promised. `WM_MBUTTONUP` was never
  routed.
- Windows: `close()` no longer posts `WM_CANCELMODE` / `WM_CLOSE` when
  the message-only window was never created. `PostMessageW` with a NULL
  `hWnd` posts to the *calling* thread's queue, so tearing down a
  half-built instance pushed two stray window messages into whatever
  thread called `close()` -- the EDT, for a UI consumer.
- Windows: the bindings arena is released when `create()` fails, matching
  the Linux fix above. The pre-instance `DefWindowProcW` used by the
  WndProc fallback is bound onto the process-lifetime arena rather than a
  per-Tray one, so a failed or closed instance cannot leave it pointing
  into freed memory -- which would have made a later `Tray.create` return
  a window that was never created.
- Windows: the message pump backs off after a failed iteration instead of
  spinning. A permanently-failing `PeekMessageW` -- against a closed
  arena, say -- pinned a core for the life of the process.
- Linux: `close()` leaves the D-Bus connection allocated instead of
  freeing it when the I/O thread has not stopped in time, and the
  watcher re-registration's reply timeout is bounded below that budget.
  Freeing the connection while the thread is still inside a libdbus call
  -- reachable through a blocking `onEvent` handler, or a tray host
  restarting during shutdown -- crashed in libdbus rather than in
  anything a consumer could see.

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
  rather than throwing, so callers degrade gracefully to a no-tray UX
  instead of guarding every construction with a try/catch.
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
