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

| Platform | Backend | Notes |
|---|---|---|
| Linux   | `org.kde.StatusNotifierItem` over D-Bus + `com.canonical.dbusmenu` for the menu | Talks to the desktop's tray host directly via libdbus. No GTK / GLib runtime dependency. Works on KDE, GNOME with the SNI extension, Hyprland (via waybar / similar), Cinnamon, Budgie. |
| Windows | `Shell_NotifyIcon` via `shell32` | Win32 message-pump driven. The classic "balloon notification" surface. |
| macOS   | `NSStatusBar` / `NSStatusItem` via AppKit + `objc_msgSend` | Menu-bar item top-right. Requires the JVM to be running with a Cocoa main thread (most JVM desktop apps already do). |

Each backend lives in its own package so consumers can audit / patch the
one that affects them without grokking the others.
</details>

<details>
  <summary>Install</summary>

Maven Central coordinates land with the first tagged release. For now, use
JitPack or build from source.

```kotlin
dependencies {
    implementation("io.github.kitty-hivens:libtray:0.1.0")
}
```

Requires JDK 22+ (Project Panama). Caller must pass
`--enable-native-access=ALL-UNNAMED` (or grant the library's module
specifically) to permit the native calls.
</details>

<details>
  <summary>Use</summary>

```kotlin
import io.github.kittyhivens.libtray.*

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
</details>

<details>
  <summary>Status</summary>

**Pre-release.** API may shift before 1.0. The Linux SNI backend is the
first to land; Windows + macOS impls follow.

Built and validated against:

- Aura Launcher (`Kitty-Hivens/Aura-Launcher`) — primary downstream
- Linux: Hyprland, KDE Plasma — verified
- Windows: pending
- macOS: pending (no Mac in maintainer's hands; community contributors with
  Apple Silicon are the current path to validation)
</details>

---

> ※ Apache License 2.0 — fork it, ship it, sell it. Patches welcome but not required.
