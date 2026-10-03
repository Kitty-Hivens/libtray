package dev.hivens.libtray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The public API as a Java caller writes it. Most of the value is that this
 * file compiles: no Companion, no Unit.INSTANCE, no Function0, no checked
 * exception on unsubscribe, and an exhaustive switch over TrayEvent.
 */
class JavaApiTest {

    private static final byte[] ICON = {(byte) 0x89, 0x50, 0x4E, 0x47};

    @Test
    void builderReadsLikeJava() {
        TrayMenu menu = new TrayMenu(
            new TrayMenuItem.Standard("show", "Show window"),
            TrayMenuItem.Separator.INSTANCE,
            new TrayMenuItem.Submenu("more", "More", List.of(new TrayMenuItem.Standard("about", "About"))),
            new TrayMenuItem.Standard("exit", "Exit", false));

        TrayBuilder spec = TrayBuilder.of("MyApp", ICON)
            .tooltip("MyApp")
            .menu(menu)
            .maxIconSize(128)
            .build();

        assertEquals("MyApp", spec.getTitle());
        assertEquals(4, spec.getMenu().getItems().size());
        assertEquals(128, spec.getMaxIconSize());
    }

    @Test
    void createIsStatic() {
        Function<TrayBuilder, Tray> create = Tray::create;
        assertTrue(create != null);
    }

    @Test
    void eventsSwitchExhaustively() {
        assertEquals("activated", describe(TrayEvent.Activated.INSTANCE));
        assertEquals("selected exit", describe(new TrayEvent.MenuItemSelected("exit")));
    }

    /** Compiles only while Java sees TrayEvent as sealed with exactly these four kinds. */
    private static String describe(TrayEvent event) {
        return switch (event) {
            case TrayEvent.Activated a -> "activated";
            case TrayEvent.MiddleActivated m -> "middle";
            case TrayEvent.MenuRequested r -> "menu";
            case TrayEvent.MenuItemSelected s -> "selected " + s.getId();
        };
    }

    /** Never runs against a live tray. It pins the shape of onEvent for Java callers. */
    @SuppressWarnings("unused")
    private static void subscribeShapes(Tray tray, Executor uiThread) {
        TraySubscription plain = tray.onEvent(event -> System.out.println(describe(event)));
        try (TraySubscription onUi = tray.onEvent(uiThread, event -> System.out.println(describe(event)))) {
            plain.close();
        }
    }
}
