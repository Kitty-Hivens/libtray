package dev.hivens.libtray.macos

import dev.hivens.libtray.Tray
import dev.hivens.libtray.TrayBuilder
import dev.hivens.libtray.TrayMenu
import dev.hivens.libtray.TrayMenuItem
import dev.hivens.libtray.solidPng
import dev.hivens.libtray.startWatchdog
import dev.hivens.libtray.step
import java.awt.EventQueue
import java.awt.Frame
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Looks for the stall macAwtCheck hit: a wait for the Cocoa main queue from
 * the event dispatch thread that runs out. Creates, reads and closes a tray
 * from a background thread and then from the EDT, over and over, and while a
 * step is stuck prints every Java stack and the native ones `sample` sees.
 */
fun main() {
    startWatchdog(150)
    thread(isDaemon = true, name = "stall-monitor") { watchForStalls() }

    var window: Frame? = null
    EventQueue.invokeAndWait {
        window = Frame("libtray EDT probe").apply {
            setSize(200, 100)
            isVisible = true
        }
    }
    step("AWT is up and its window is showing")
    // Loading the bindings and their classes takes long enough to look like a stall.
    running = null
    warmUp()
    repeat(CYCLES) { cycle ->
        thread { runCycle("background", cycle) }.join()
        EventQueue.invokeAndWait { runCycle("EDT", cycle) }
    }
    EventQueue.invokeAndWait { window?.dispose() }
    step("$CYCLES cycles, ${stalls.get()} stalls")
    exitProcess(if (stalls.get() == 0) 0 else 2)
}

private const val CYCLES = 150
private const val STALL_NANOS = 700_000_000L

private class Running(val what: String, val startNanos: Long, @Volatile var dumped: Boolean = false)

@Volatile private var running: Running? = null
private val stalls = AtomicInteger(0)

private fun <T> timed(what: String, block: () -> T): T {
    val mine = Running(what, System.nanoTime())
    running = mine
    try {
        return block()
    } finally {
        running = null
        val ms = (System.nanoTime() - mine.startNanos) / 1_000_000
        if (ms >= 100) step("$what took $ms ms")
    }
}

private fun warmUp() {
    val tray = Tray.create(TrayBuilder(title = "libtray-edt-probe", iconBytes = solidPng(32)))
    (tray as? AppKitTrayImpl)?.shownTooltip()
    tray?.close()
}

private fun runCycle(where: String, cycle: Int) {
    val name = "$where $cycle"
    val tray = timed("$name create") {
        Tray.create(
            TrayBuilder(
                title = "libtray-edt-probe",
                iconBytes = solidPng(32),
                tooltip = "initial",
                menu = TrayMenu(TrayMenuItem.Standard("noop", "Noop")),
            ),
        )
    }
    if (tray == null) {
        step("$name create returned null")
        return
    }
    tray.setTooltip(name)
    val shown = timed("$name shownTooltip") { (tray as AppKitTrayImpl).shownTooltip() }
    if (shown != name) step("$name shows \"$shown\"")
    timed("$name close") { tray.close() }
}

private fun watchForStalls() {
    while (true) {
        Thread.sleep(50)
        val current = running ?: continue
        if (current.dumped || System.nanoTime() - current.startNanos < STALL_NANOS) continue
        current.dumped = true
        val number = stalls.incrementAndGet()
        step("STALL $number in ${current.what}")
        for ((thread, frames) in Thread.getAllStackTraces()) {
            println("  \"${thread.name}\" ${thread.state}")
            frames.forEach { println("      at $it") }
        }
        if (number <= 3) sampleNative(number)
    }
}

private fun sampleNative(number: Int) {
    val out = File("build/edt-probe").apply { mkdirs() }.resolve("sample-${ProcessHandle.current().pid()}-$number.txt")
    val sample = listOf("/usr/bin/sample", ProcessHandle.current().pid().toString(), "1", "10", "-file", out.path)
    for (command in listOf(sample, listOf("sudo", "-n") + sample)) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val ended = process.waitFor(10, TimeUnit.SECONDS)
        step("${command.first()} ended=$ended exit=${if (ended) process.exitValue() else -1} ${text.take(300)}")
        if (ended && process.exitValue() == 0 && out.exists()) {
            out.readLines().take(160).forEach { println("  | $it") }
            return
        }
    }
}
