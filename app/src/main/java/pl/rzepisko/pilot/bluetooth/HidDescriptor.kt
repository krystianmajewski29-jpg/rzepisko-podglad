package pl.rzepisko.pilot.bluetooth

import pl.rzepisko.pilot.core.RemoteKey

/**
 * Deskryptor raportu HID, którym telefon przedstawia się telewizorowi.
 *
 * Zgłaszamy dwa niezależne urządzenia w jednej kolekcji:
 *  - Report ID 1 — klawiatura. Strzałki, Enter i Esc, bo dokładnie tak Android TV
 *    interpretuje nawigację: krzyżak to klawisze kursora, OK to Enter, wstecz to Esc.
 *  - Report ID 2 — Consumer Control. Głośność, sterowanie odtwarzaniem, Home i Power.
 *    Te funkcje nie mają odpowiedników na stronie klawiatury.
 *
 * Rozbicie na dwa raporty nie jest kosmetyczne: telewizor, który nie rozumie strony
 * Consumer, wciąż obsłuży nawigację klawiaturą (i odwrotnie).
 */
object HidDescriptor {

    const val REPORT_ID_KEYBOARD = 1
    const val REPORT_ID_CONSUMER = 2

    /** Klawiatura: [modyfikatory][rezerwa][6 × kod klawisza]. */
    const val KEYBOARD_REPORT_SIZE = 8

    /** Consumer Control: pojedyncza 16-bitowa wartość usage (little-endian). */
    const val CONSUMER_REPORT_SIZE = 2

    val BYTES: ByteArray = byteArrayOf(
        // ---- Klawiatura (Report ID 1) ----
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD.toByte(), // Report ID (1)
        0x05, 0x07, // Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(), // Usage Minimum (Left Control)
        0x29, 0xE7.toByte(), // Usage Maximum (Right GUI)
        0x15, 0x00, // Logical Minimum (0)
        0x25, 0x01, // Logical Maximum (1)
        0x75, 0x01, // Report Size (1)
        0x95.toByte(), 0x08, // Report Count (8)
        0x81.toByte(), 0x02, // Input (Data, Variable, Absolute) — modyfikatory
        0x95.toByte(), 0x01, // Report Count (1)
        0x75, 0x08, // Report Size (8)
        0x81.toByte(), 0x01, // Input (Constant) — bajt rezerwowy
        0x95.toByte(), 0x06, // Report Count (6)
        0x75, 0x08, // Report Size (8)
        0x15, 0x00, // Logical Minimum (0)
        0x25, 0x65, // Logical Maximum (101)
        0x05, 0x07, // Usage Page (Keyboard/Keypad)
        0x19, 0x00, // Usage Minimum (0)
        0x29, 0x65, // Usage Maximum (101)
        0x81.toByte(), 0x00, // Input (Data, Array) — do 6 klawiszy naraz
        0xC0.toByte(), // End Collection

        // ---- Consumer Control (Report ID 2) ----
        0x05, 0x0C, // Usage Page (Consumer)
        0x09, 0x01, // Usage (Consumer Control)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), REPORT_ID_CONSUMER.toByte(), // Report ID (2)
        0x15, 0x00, // Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x03, // Logical Maximum (1023)
        0x19, 0x00, // Usage Minimum (0)
        0x2A, 0xFF.toByte(), 0x03, // Usage Maximum (1023)
        0x75, 0x10, // Report Size (16)
        0x95.toByte(), 0x01, // Report Count (1)
        0x81.toByte(), 0x00, // Input (Data, Array)
        0xC0.toByte(), // End Collection
    )
}

/** Klawisz w mapowaniu HID: albo kod klawiatury, albo usage strony Consumer. */
sealed interface HidCode {
    data class Keyboard(val usage: Int) : HidCode
    data class Consumer(val usage: Int) : HidCode
}

/**
 * Tłumaczenie [RemoteKey] na kody HID.
 *
 * Kody klawiatury pochodzą z HID Usage Tables, rozdz. 10 (Keyboard/Keypad Page),
 * kody Consumer z rozdz. 15 (Consumer Page).
 */
object HidKeyMap {

    private const val KB_ENTER = 0x28
    private const val KB_ESCAPE = 0x29
    private const val KB_RIGHT = 0x4F
    private const val KB_LEFT = 0x50
    private const val KB_DOWN = 0x51
    private const val KB_UP = 0x52

    private const val CC_POWER = 0x30
    private const val CC_MENU = 0x40
    private const val CC_PLAY = 0xB0
    private const val CC_PAUSE = 0xB1
    private const val CC_RECORD = 0xB2
    private const val CC_FAST_FORWARD = 0xB3
    private const val CC_REWIND = 0xB4
    private const val CC_SCAN_NEXT = 0xB5
    private const val CC_SCAN_PREVIOUS = 0xB6
    private const val CC_STOP = 0xB7
    private const val CC_PLAY_PAUSE = 0xCD
    private const val CC_MUTE = 0xE2
    private const val CC_VOLUME_UP = 0xE9
    private const val CC_VOLUME_DOWN = 0xEA
    private const val CC_CHANNEL_UP = 0x9C
    private const val CC_CHANNEL_DOWN = 0x9D
    private const val CC_AC_HOME = 0x223
    private const val CC_AC_BACK = 0x224
    private const val CC_AC_SEARCH = 0x221

    val CODES: Map<RemoteKey, HidCode> = mapOf(
        RemoteKey.UP to HidCode.Keyboard(KB_UP),
        RemoteKey.DOWN to HidCode.Keyboard(KB_DOWN),
        RemoteKey.LEFT to HidCode.Keyboard(KB_LEFT),
        RemoteKey.RIGHT to HidCode.Keyboard(KB_RIGHT),
        RemoteKey.OK to HidCode.Keyboard(KB_ENTER),
        // Esc jest pewniejszy od AC Back: rozumie go Android TV, webOS i Tizen,
        // a Consumer AC Back bywa ignorowany przez starsze firmware'y.
        RemoteKey.BACK to HidCode.Keyboard(KB_ESCAPE),
        RemoteKey.EXIT to HidCode.Consumer(CC_AC_BACK),
        RemoteKey.HOME to HidCode.Consumer(CC_AC_HOME),
        RemoteKey.MENU to HidCode.Consumer(CC_MENU),
        RemoteKey.POWER to HidCode.Consumer(CC_POWER),
        RemoteKey.VOLUME_UP to HidCode.Consumer(CC_VOLUME_UP),
        RemoteKey.VOLUME_DOWN to HidCode.Consumer(CC_VOLUME_DOWN),
        RemoteKey.MUTE to HidCode.Consumer(CC_MUTE),
        RemoteKey.CHANNEL_UP to HidCode.Consumer(CC_CHANNEL_UP),
        RemoteKey.CHANNEL_DOWN to HidCode.Consumer(CC_CHANNEL_DOWN),
        RemoteKey.PLAY to HidCode.Consumer(CC_PLAY),
        RemoteKey.PAUSE to HidCode.Consumer(CC_PAUSE),
        RemoteKey.PLAY_PAUSE to HidCode.Consumer(CC_PLAY_PAUSE),
        RemoteKey.STOP to HidCode.Consumer(CC_STOP),
        RemoteKey.REWIND to HidCode.Consumer(CC_REWIND),
        RemoteKey.FAST_FORWARD to HidCode.Consumer(CC_FAST_FORWARD),
        RemoteKey.PREVIOUS to HidCode.Consumer(CC_SCAN_PREVIOUS),
        RemoteKey.NEXT to HidCode.Consumer(CC_SCAN_NEXT),
        RemoteKey.RECORD to HidCode.Consumer(CC_RECORD),
        RemoteKey.INFO to HidCode.Consumer(CC_AC_SEARCH),
        // Cyfry: 1-9 leżą kolejno od 0x1E, zero jest osobno na 0x27.
        RemoteKey.NUM_1 to HidCode.Keyboard(0x1E),
        RemoteKey.NUM_2 to HidCode.Keyboard(0x1F),
        RemoteKey.NUM_3 to HidCode.Keyboard(0x20),
        RemoteKey.NUM_4 to HidCode.Keyboard(0x21),
        RemoteKey.NUM_5 to HidCode.Keyboard(0x22),
        RemoteKey.NUM_6 to HidCode.Keyboard(0x23),
        RemoteKey.NUM_7 to HidCode.Keyboard(0x24),
        RemoteKey.NUM_8 to HidCode.Keyboard(0x25),
        RemoteKey.NUM_9 to HidCode.Keyboard(0x26),
        RemoteKey.NUM_0 to HidCode.Keyboard(0x27),
    )

    /** Kody klawiatury dla znaków ASCII — używane przy wpisywaniu tekstu. */
    fun keyboardUsageForChar(char: Char): Pair<Int, Boolean>? {
        val lower = char.lowercaseChar()
        val shifted = char.isUpperCase()
        return when {
            lower in 'a'..'z' -> (0x04 + (lower - 'a')) to shifted
            lower in '1'..'9' -> (0x1E + (lower - '1')) to shifted
            lower == '0' -> 0x27 to shifted
            lower == ' ' -> 0x2C to false
            lower == '-' -> 0x2D to false
            lower == '.' -> 0x37 to false
            lower == '\n' -> 0x28 to false
            else -> null
        }
    }

    fun buildKeyboardReport(usage: Int, withShift: Boolean = false): ByteArray =
        ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE).also {
            it[0] = if (withShift) 0x02 else 0x00 // lewy Shift
            it[2] = usage.toByte()
        }

    fun emptyKeyboardReport(): ByteArray = ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE)

    fun buildConsumerReport(usage: Int): ByteArray = byteArrayOf(
        (usage and 0xFF).toByte(),
        ((usage shr 8) and 0xFF).toByte(),
    )

    fun emptyConsumerReport(): ByteArray = ByteArray(HidDescriptor.CONSUMER_REPORT_SIZE)
}
