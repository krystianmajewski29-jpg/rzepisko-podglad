package pl.rzepisko.pilot.core

/**
 * Kanoniczny zestaw klawiszy pilota.
 *
 * To jest jedyny "język", którym posługuje się UI. Każdy sterownik (Samsung, LG, Sony,
 * Roku, HID przez Bluetooth...) tłumaczy te wartości na swój protokół. Dzięki temu
 * dodanie nowej marki nie wymaga dotykania warstwy prezentacji.
 *
 * Nie każdy sterownik obsłuży każdy klawisz — patrz [RemoteTransport.supportedKeys].
 */
enum class RemoteKey {
    POWER,
    POWER_ON,
    POWER_OFF,

    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,

    BACK,
    HOME,
    MENU,
    EXIT,
    INFO,
    GUIDE,
    SOURCE,

    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    CHANNEL_UP,
    CHANNEL_DOWN,

    PLAY,
    PAUSE,
    PLAY_PAUSE,
    STOP,
    REWIND,
    FAST_FORWARD,
    PREVIOUS,
    NEXT,
    RECORD,

    NUM_0,
    NUM_1,
    NUM_2,
    NUM_3,
    NUM_4,
    NUM_5,
    NUM_6,
    NUM_7,
    NUM_8,
    NUM_9,

    RED,
    GREEN,
    YELLOW,
    BLUE,

    APP_NETFLIX,
    APP_YOUTUBE,
    APP_PRIME_VIDEO,
    APP_DISNEY_PLUS;

    val isDigit: Boolean get() = this in NUM_0..NUM_9

    /** Cyfra 0-9 dla klawiszy numerycznych, `null` dla pozostałych. */
    val digit: Int? get() = if (isDigit) ordinal - NUM_0.ordinal else null

    companion object {
        val digits: List<RemoteKey> =
            listOf(NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9)

        fun forDigit(digit: Int): RemoteKey {
            require(digit in 0..9) { "Cyfra poza zakresem: $digit" }
            return digits[digit]
        }
    }
}

/** Rodzaj naciśnięcia — część urządzeń rozróżnia klik od przytrzymania. */
enum class KeyAction { CLICK, PRESS, RELEASE }
