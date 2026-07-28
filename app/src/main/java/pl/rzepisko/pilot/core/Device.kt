package pl.rzepisko.pilot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sposób komunikacji z urządzeniem. */
enum class TransportKind { WIFI, BLUETOOTH }

/**
 * Obsługiwane protokoły. Jedna marka może mieć kilka (np. Samsung sprzed 2016
 * gada innym protokołem niż Tizen), dlatego kluczem jest protokół, nie marka.
 */
enum class Protocol(
    val displayName: String,
    val kind: TransportKind,
    /** Marki, których telewizory typowo mówią tym protokołem — do podpowiedzi w UI. */
    val brands: List<String>,
    /** Czy przed użyciem wymagane jest sparowanie (token / PIN / potwierdzenie na TV). */
    val requiresPairing: Boolean,
) {
    SAMSUNG_TIZEN(
        displayName = "Samsung (Tizen, 2016+)",
        kind = TransportKind.WIFI,
        brands = listOf("Samsung"),
        requiresPairing = true,
    ),
    LG_WEBOS(
        displayName = "LG webOS",
        kind = TransportKind.WIFI,
        brands = listOf("LG"),
        requiresPairing = true,
    ),
    SONY_BRAVIA(
        displayName = "Sony Bravia (IP Control)",
        kind = TransportKind.WIFI,
        brands = listOf("Sony"),
        requiresPairing = true, // PSK ustawiany ręcznie w menu TV
    ),
    ROKU_ECP(
        displayName = "Roku / TCL Roku TV",
        kind = TransportKind.WIFI,
        brands = listOf("Roku", "TCL", "Hisense", "Philips Roku TV"),
        requiresPairing = false,
    ),
    PHILIPS_JOINTSPACE(
        displayName = "Philips (JointSpace)",
        kind = TransportKind.WIFI,
        brands = listOf("Philips"),
        requiresPairing = false, // dotyczy API v6; nowsze modele wymagają parowania HTTPS
    ),
    BLUETOOTH_HID(
        displayName = "Bluetooth (HID)",
        kind = TransportKind.BLUETOOTH,
        brands = listOf("Android TV", "Google TV", "Sony", "Philips", "TCL", "Xiaomi", "Nvidia Shield"),
        requiresPairing = true,
    );

    companion object {
        fun byKind(kind: TransportKind): List<Protocol> = entries.filter { it.kind == kind }
    }
}

/**
 * Zapisany pilot: urządzenie + wszystko, co potrzebne, żeby się z nim połączyć ponownie.
 *
 * [address] to adres IP dla Wi-Fi albo adres MAC dla Bluetootha.
 * [credentials] trzyma sekrety specyficzne dla protokołu (token Samsunga, client-key LG,
 * PSK Sony). Klucze są zdefiniowane w [Credentials].
 */
@Serializable
data class RemoteDevice(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val address: String,
    @SerialName("model") val model: String? = null,
    val credentials: Map<String, String> = emptyMap(),
) {
    val kind: TransportKind get() = protocol.kind

    fun withCredential(key: String, value: String): RemoteDevice =
        copy(credentials = credentials + (key to value))
}

object Credentials {
    /** Samsung Tizen: token zwracany przez TV po zaakceptowaniu połączenia. */
    const val SAMSUNG_TOKEN = "samsung_token"

    /** LG webOS: `client-key` z odpowiedzi na `register`. */
    const val LG_CLIENT_KEY = "lg_client_key"

    /** Sony Bravia: Pre-Shared Key ustawiony w Ustawienia → Sieć → IP Control. */
    const val SONY_PSK = "sony_psk"
}

/** Urządzenie znalezione w sieci lub w otoczeniu Bluetooth, jeszcze niezapisane. */
data class DiscoveredDevice(
    val name: String,
    val address: String,
    val protocol: Protocol,
    val model: String? = null,
    /** Skąd pochodzi wpis — przydatne przy diagnostyce ("SSDP", "mDNS", "BT"). */
    val source: String,
) {
    fun toRemoteDevice(id: String): RemoteDevice = RemoteDevice(
        id = id,
        name = name,
        protocol = protocol,
        address = address,
        model = model,
    )
}
