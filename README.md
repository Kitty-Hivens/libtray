<div align="center">
  <h1>libtray</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache_2.0-86dbd7?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-22+-BB86FC?style=for-the-badge&logo=openjdk&logoColor=D9E0EE&labelColor=1E202B)](#)
[![Platform](https://img.shields.io/badge/Linux%20%7C%20Windows%20%7C%20macOS-supported-86dbce?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](#)

</div>

<div align="center">
  <h3>Cross-platform system tray for JVM 22+ via Project Panama.</h3>
</div>

---

A small, focused replacement for [`dorkbox/SystemTray`](https://github.com/dorkbox/SystemTray)
(unmaintained since 2023). Designed for modern JVM desktop apps that already
draw their own UI (Compose Desktop, Skiko, Swing, JavaFX) and need only a
tray icon + right-click menu — not a full UI toolkit.

<details>
  <summary>Why another tray library</summary>

dorkbox/SystemTray hardcodes a JNA version check that conflicts with JBR
25's bundled JNA, requires `-Djna.nosys=true` to work around an AWT loader
clash, and uses runtime bytecode patching (javassist) that fails the
stricter stackmap verifier in modern JVMs. None of that is fixable with a
patch — the architecture predates Project Panama (`java.lang.foreign`,
JEP 454 finalized in JDK 22) and would need to be rewritten anyway.

libtray is the rewrite. Pure Panama bindings, no JNA, no AWT patching,
no transitive GTK/GLib pull-in on Linux. The library is small enough to
read end-to-end in one sitting.
</details>

<details>
  <summary>Platform backends</summary>

| Platform | Backend                                                                         | Notes                                                                                                                                                                                   |
|----------|---------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Linux    | `org.kde.StatusNotifierItem` over D-Bus + `com.canonical.dbusmenu` for the menu | Talks to the desktop's tray host directly via libdbus. No GTK / GLib runtime dependency. Works on KDE, GNOME with the SNI extension, Hyprland (via waybar / similar), Cinnamon, Budgie. |
| Windows  | `Shell_NotifyIcon` via `shell32`                                                | Win32 message-pump driven. The classic "balloon notification" surface.                                                                                                                  |
| macOS    | `NSStatusBar` / `NSStatusItem` via AppKit + `objc_msgSend`                      | Menu-bar item top-right. Requires the JVM to be running with a Cocoa main thread (most JVM desktop apps already do).                                                                    |

Each backend lives in its own package so consumers can audit / patch the
one that affects them without grokking the others.

Event coverage is not uniform, because how much of the interaction the
platform keeps to itself differs. Put anything essential in the menu:

| Event                       | Linux                    | Windows | macOS                                                                                         |
|-----------------------------|--------------------------|---------|-----------------------------------------------------------------------------------------------|
| `MenuItemSelected`          | yes                      | yes     | yes                                                                                           |
| `Activated` (primary click) | yes                      | yes     | when `macosMenuOnPrimaryClick` is false or no menu is set, otherwise the click opens the menu |
| `MiddleActivated`           | yes                      | yes     | yes                                                                                           |
| `MenuRequested`             | depends on the tray host | yes     | yes                                                                                           |
</details>

<details>
  <summary>Install</summary>

Available on Maven Central:

```kotlin
dependencies {
    implementation("dev.hivens:libtray:0.2.0")
}
```

Requires JDK 22+ (Project Panama). Caller must pass
`--enable-native-access=ALL-UNNAMED` (or grant the library's module
specifically) to permit the native calls.

**GraalVM native image.** The jar ships its reachability metadata in
`META-INF/native-image/dev.hivens/libtray/`, which `native-image` picks up
from the classpath: every foreign call shape and upcall the three backends
use, plus the AWT and ImageIO entries libtray's icon handling needs. Pass
`--enable-native-access=ALL-UNNAMED` to `native-image` as well. Icon
decoding goes through AWT, so the build writes `libawt` and its companion
libraries next to the executable, and they have to ship with it. CI builds
and runs a native check program on Linux, Windows and macOS with only that
metadata.
</details>

<details>
  <summary>Use</summary>

```kotlin
import dev.hivens.libtray.*

val tray = Tray.create(
    TrayBuilder(
        title = "MyApp",
        iconBytes = Files.readAllBytes(Path.of("icon.png")),
        tooltip = "MyApp — running",
        menu = TrayMenu(listOf(
            TrayMenuItem.Standard(id = "show", label = "Show window"),
            TrayMenuItem.Separator,
            TrayMenuItem.Standard(id = "exit", label = "Exit"),
        )),
    ),
) ?: error("Tray not supported on this platform")

tray.onEvent { event ->
    when (event) {
        is TrayEvent.Activated -> showMainWindow()
        is TrayEvent.MenuItemSelected -> when (event.id) {
            "show" -> showMainWindow()
            "exit" -> exitProcess(0)
        }
        else -> Unit
    }
}

// On app shutdown
tray.close()
```

`onEvent` returns a `TraySubscription`, close it to stop listening.
Listeners run on an event thread libtray keeps per tray, in the order
things happened, so a slow listener never stalls the tray itself. To
receive events on your UI thread instead, pass its executor:

```kotlin
tray.onEvent(Dispatchers.Main.asExecutor()) { event -> /* on the UI thread */ }
```

From Java, `TrayBuilder.of` builds the same thing without the
all-arguments constructor:

```java
Tray tray = Tray.create(TrayBuilder.of("MyApp", iconBytes)
    .tooltip("MyApp")
    .menu(new TrayMenu(
        new TrayMenuItem.Standard("show", "Show window"),
        TrayMenuItem.Separator.INSTANCE,
        new TrayMenuItem.Standard("exit", "Exit")))
    .build());

if (tray != null) {
    tray.onEvent(Platform::runLater, event -> {
        switch (event) {
            case TrayEvent.Activated a -> showMainWindow();
            case TrayEvent.MenuItemSelected s when s.getId().equals("exit") -> Platform.exit();
            default -> { }
        }
    });
}
```

For Flatpak on Linux, set `linuxBusName` to a unique name within your
application ID's D-Bus namespace:

```kotlin
TrayBuilder(
    title = "MyApp",
    iconBytes = iconBytes,
    linuxBusName = "com.example.MyApp.StatusNotifierItem",
)
```

The Flatpak manifest still needs permission to talk to the tray watcher:

```yaml
finish-args:
  - --talk-name=org.kde.StatusNotifierWatcher
```

No `--own-name=org.kde.*` permission is needed for this setup. Outside
Flatpak, omitting `linuxBusName` preserves the generated
`org.kde.StatusNotifierItem-PID-N` name.

The name is held for the whole session bus, so a second running instance
of the app that asks for the same one gets no icon (`Tray.create` returns
null). Single-instance apps can use a fixed name as shown.
</details>

<details>
  <summary>Migrating</summary>

### From 0.1.3

The event API, the threading of events and `TrayBuilder` all changed.
Recompile against the new version in any case: a jar built against
0.1.3 throws `NoSuchMethodError`.

| Change                                                            | Kotlin                                                                                                                            | Java                                                                                |
|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `onEvent` returns a `TraySubscription`                            | Lambdas compile as before. Where the handle is stored as `() -> Unit`, change the type and call `close()` instead of invoking it. | Drop `return Unit.INSTANCE` and call `close()` on the subscription.                 |
| Events arrive on a libtray event thread                           | Code that touches UI from a listener registers with `onEvent(executor) { }`. On macOS this replaces relying on the main thread.   | Same, with `tray.onEvent(Platform::runLater, listener)` or your toolkit's executor. |
| `TrayBuilder` gained `linuxBusName` and `macosMenuOnPrimaryClick` | Nothing in the source.                                                                                                            | Replace the constructor with `TrayBuilder.of(title, icon)...build()`.               |
| `Submenu` takes `items` before `enabled`                          | Only positional calls that passed `enabled` third change.                                                                         | Pass `items` third and `enabled` last, or leave `enabled` out.                      |
| `Tray` has two `onEvent` overloads                                | A class implementing `Tray`, such as a test double, implements both.                                                              | Same.                                                                               |
| `Tray.create` is static                                           | Nothing.                                                                                                                          | `Tray.Companion.create` becomes `Tray.create`.                                      |
| Flatpak can avoid `org.kde.*`                                     | Set `linuxBusName` to a name under your app ID and drop `--own-name=org.kde.*` from `finish-args`.                                | Same, via `.linuxBusName(...)`.                                                     |

```kotlin
// 0.1.3
val unsubscribe: () -> Unit = tray.onEvent { event -> handle(event) }
unsubscribe()
// now
val subscription: TraySubscription = tray.onEvent { event -> handle(event) }
subscription.close()
```

```java
// 0.1.3
Tray tray = Tray.Companion.create(new TrayBuilder("MyApp", iconBytes, null, menu, 256));
Function0<Unit> unsubscribe = tray.onEvent(e -> { handle(e); return Unit.INSTANCE; });
unsubscribe.invoke();
// now
Tray tray = Tray.create(TrayBuilder.of("MyApp", iconBytes).menu(menu).build());
TraySubscription subscription = tray.onEvent(e -> handle(e));
subscription.close();
```

### From 0.1.2

`TrayBuilder` gained `maxIconSize`. Recompile Kotlin callers. Java
callers of the four-argument constructor pass the size (`256` keeps the
default, `null` turns scaling off) as the fifth argument.
</details>

<details>
  <summary>Status</summary>

**Pre-1.0.** API may still shift before 1.0. All three backends shipped
(Linux SNI, Windows Shell_NotifyIcon, macOS NSStatusItem).

Built and validated against:

- Nexira (`Kitty-Hivens/Nexira`), the primary downstream
- Linux: Hyprland, KDE Plasma — verified
- Windows: Shell_NotifyIcon + popup menu — verified on Win10 / Win11
- macOS: NSStatusItem + menu — verified on a macOS VM and a community
  JavaFX consumer (both JVM and GraalVM native-image)
</details>

---

> ※ Apache License 2.0 — fork it, ship it, sell it. Patches welcome but not required.
