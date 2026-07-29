package pl.rzepisko.pilot.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import pl.rzepisko.pilot.core.RemoteException

/**
 * Rejestruje telefon w systemie jako urządzenie HID (klawiatura + pilot multimedialny).
 *
 * To musi być singleton na proces: Android pozwala aplikacji zarejestrować **jedną**
 * usługę HID naraz, a `registerApp()` zawołane drugi raz unieważnia poprzednią
 * rejestrację. Kilka [BluetoothHidTransport] współdzieli więc jeden kontroler.
 *
 * Wymaga Androida 9 (API 28) — wcześniej `BluetoothHidDevice` nie istnieje i telefon
 * nie potrafi udawać peryferium.
 */
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidController private constructor(private val appContext: Context) {

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hid-reports").apply { isDaemon = true }
    }

    private val registrationMutex = Mutex()

    @Volatile
    private var proxy: BluetoothHidDevice? = null

    @Volatile
    private var registered = false

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    /** Zbiera oczekiwania na zmianę stanu połączenia z konkretnym adresem. */
    private val connectionWaiters = mutableMapOf<String, MutableList<(Int) -> Unit>>()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (!isRegistered) _connectedDevice.value = null
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> _connectedDevice.value = device
                BluetoothProfile.STATE_DISCONNECTED ->
                    if (_connectedDevice.value?.address == device.address) {
                        _connectedDevice.value = null
                    }
            }
            synchronized(connectionWaiters) {
                connectionWaiters.remove(device.address)
            }?.forEach { it(state) }
        }
    }

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requireReady(): BluetoothAdapter {
        val a = adapter ?: throw RemoteException("To urządzenie nie ma Bluetootha")
        if (!a.isEnabled) throw RemoteException("Włącz Bluetooth, żeby korzystać z tego pilota")
        if (!hasConnectPermission()) {
            throw RemoteException("Brak zgody na dostęp do urządzeń Bluetooth")
        }
        return a
    }

    /** Pobiera proxy profilu HID i rejestruje usługę. Idempotentne. */
    @SuppressLint("MissingPermission") // sprawdzane w requireReady()
    suspend fun ensureRegistered(): BluetoothHidDevice = registrationMutex.withLock {
        proxy?.takeIf { registered }?.let { return@withLock it }
        val adapter = requireReady()

        val service = proxy ?: obtainProxy(adapter)
        proxy = service

        if (!registered) {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                SDP_NAME,
                SDP_DESCRIPTION,
                SDP_PROVIDER,
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidDescriptor.BYTES,
            )
            val accepted = service.registerApp(sdp, null, null, executor, callback)
            if (!accepted) {
                throw RemoteException("System odrzucił rejestrację pilota Bluetooth")
            }
            val ok = withTimeoutOrNull(REGISTER_TIMEOUT_MS) {
                while (!registered) kotlinx.coroutines.delay(50)
                true
            }
            if (ok != true) throw RemoteException("Rejestracja pilota Bluetooth nie doszła do skutku")
        }
        service
    }

    @SuppressLint("MissingPermission")
    private suspend fun obtainProxy(adapter: BluetoothAdapter): BluetoothHidDevice {
        val result = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine<BluetoothHidDevice?> { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                        if (profile == BluetoothProfile.HID_DEVICE && cont.isActive) {
                            cont.resume(service as BluetoothHidDevice)
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HID_DEVICE) {
                            proxy = null
                            registered = false
                        }
                    }
                }
                if (!adapter.getProfileProxy(appContext, listener, BluetoothProfile.HID_DEVICE)) {
                    cont.resume(null)
                }
            }
        }
        return result ?: throw RemoteException("System nie udostępnił profilu HID")
    }

    /** Urządzenia sparowane w systemie — z nich użytkownik wybiera telewizor. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        val adapter = adapter ?: return emptyList()
        if (!hasConnectPermission() || !adapter.isEnabled) return emptyList()
        return adapter.bondedDevices?.toList().orEmpty()
    }

    @SuppressLint("MissingPermission")
    fun deviceFor(address: String): BluetoothDevice {
        val adapter = requireReady()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw RemoteException("Nieprawidłowy adres Bluetooth: $address")
        }
        return adapter.getRemoteDevice(address)
    }

    /**
     * Łączy się z urządzeniem i czeka na potwierdzenie.
     *
     * Uwaga: to telewizor decyduje, czy przyjmie połączenie od klawiatury. Część
     * modeli akceptuje je tylko wtedy, gdy jest na ekranie parowania pilota.
     */
    @SuppressLint("MissingPermission")
    suspend fun connectTo(address: String): BluetoothDevice {
        val service = ensureRegistered()
        val target = deviceFor(address)

        if (_connectedDevice.value?.address == target.address) return target

        val state = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { cont ->
                synchronized(connectionWaiters) {
                    connectionWaiters.getOrPut(target.address) { mutableListOf() }
                        .add { newState -> if (cont.isActive) cont.resume(newState) }
                }
                if (!service.connect(target)) {
                    synchronized(connectionWaiters) { connectionWaiters.remove(target.address) }
                    if (cont.isActive) cont.resume(BluetoothProfile.STATE_DISCONNECTED)
                }
            }
        }

        if (state != BluetoothProfile.STATE_CONNECTED) {
            throw RemoteException(
                "Telewizor nie przyjął połączenia. Otwórz na nim ekran dodawania pilota " +
                    "(Ustawienia → Piloty i akcesoria) i spróbuj ponownie.",
            )
        }
        return target
    }

    @SuppressLint("MissingPermission")
    fun sendReport(device: BluetoothDevice, reportId: Int, data: ByteArray): Boolean {
        val service = proxy ?: return false
        return service.sendReport(device, reportId, data)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(device: BluetoothDevice) {
        proxy?.disconnect(device)
    }

    /** Zwalnia rejestrację — wołane, gdy żaden pilot Bluetooth nie jest już używany. */
    @SuppressLint("MissingPermission")
    fun unregister() {
        proxy?.let { service ->
            if (registered) service.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, service)
        }
        proxy = null
        registered = false
        _connectedDevice.value = null
    }

    companion object {
        private const val SDP_NAME = "Uniwersalny Pilot"
        private const val SDP_DESCRIPTION = "Pilot telewizyjny"
        private const val SDP_PROVIDER = "rzepisko.pl"
        private const val PROXY_TIMEOUT_MS = 5_000L
        private const val REGISTER_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: BluetoothHidController? = null

        fun get(context: Context): BluetoothHidController =
            instance ?: synchronized(this) {
                instance ?: BluetoothHidController(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Czy telefon w ogóle potrafi udawać urządzenie HID.
 *
 * Celowo poza [BluetoothHidController]: ta klasa jest oznaczona `@RequiresApi(P)`,
 * więc lint uznałby każde odwołanie do jej companiona — łącznie z samym sprawdzeniem
 * wersji — za użycie API 28. Adnotacja [ChecksSdkIntAtLeast] mówi lintowi, że wynik
 * tej funkcji jest równoważny warunkowi `SDK_INT >= P`, dzięki czemu
 * `if (isBluetoothHidSupported()) { ... }` wystarcza za jawne sprawdzenie wersji.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
fun isBluetoothHidSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
