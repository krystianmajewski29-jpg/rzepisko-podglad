# Uniwersalny Pilot

Aplikacja na Androida zamieniająca telefon w pilot do telewizora. Dwa tryby pracy:

- **Wi-Fi** — sterowanie po sieci lokalnej natywnymi protokołami producentów
  (Samsung, LG, Sony, Roku/TCL, Philips).
- **Bluetooth** — telefon zgłasza się jako klawiatura HID, więc działa z Android TV,
  Google TV, Nvidia Shield i większością telewizorów przyjmujących klawiaturę BT,
  niezależnie od marki i bez dostępu do sieci.

## Obsługiwany sprzęt

| Protokół | Marki | Port | Parowanie |
|---|---|---|---|
| Samsung Tizen | Samsung od 2016 r. | 8002 (WSS) | potwierdzenie na TV → token zapisywany |
| LG webOS | LG | 3000 (WS) | potwierdzenie na TV → `client-key` zapisywany |
| Sony Bravia IRCC | Sony | 80 (HTTP) | klucz PSK wpisywany ręcznie |
| Roku ECP | Roku, TCL, Hisense, Sharp, Philips Roku TV | 8060 (HTTP) | brak |
| Philips JointSpace v6 | Philips 2014–2015 | 1925 (HTTP) | brak |
| Bluetooth HID | Android TV, Google TV i pochodne | — | parowanie w ustawieniach Androida |

Czego **nie** obsługuje:

- Nowsze Philipsy (JointSpace v6.2+ na porcie 1926) — wymagają HTTPS z uwierzytelnianiem
  Digest i parowania PIN-em. Dla nich właściwy jest tryb Bluetooth.
- Protokół Android TV Remote v2 (protobuf + parowanie certyfikatem). Ten sam sprzęt
  obsługujemy przez Bluetooth HID.
- Nadajnik podczerwieni — aplikacja celowo nie używa `ConsumerIrManager`; wyłącznie
  sieć i Bluetooth.

## Budowanie

Wymagany Android SDK (compileSdk 35) i JDK 17+.

```bash
./gradlew assembleDebug     # APK
./gradlew testDebugUnitTest # testy jednostkowe
```

## Architektura

```
core/        RemoteKey, RemoteDevice, RemoteTransport — wspólny język całej aplikacji
wifi/        po jednym sterowniku na protokół + wspólny WebSocket i klienci HTTP
bluetooth/   deskryptor HID, mapowanie klawiszy, rejestracja profilu w systemie
discovery/   SSDP dla Wi-Fi, lista sparowanych urządzeń dla Bluetootha
data/        trwałe zapisywanie pilotów (DataStore) i fabryka transportów
ui/          Compose: ekran pilota i ekran urządzeń, ViewModele
```

Sercem jest [`RemoteTransport`](app/src/main/java/pl/rzepisko/pilot/core/RemoteTransport.kt):
UI operuje wyłącznie na kanonicznym enumie `RemoteKey`, a każdy sterownik tłumaczy go na
własny protokół i deklaruje przez `supportedKeys`, czego nie potrafi. Dzięki temu ekran
pilota wyszarza przyciski niedostępne na danym urządzeniu, a dodanie nowej marki nie
wymaga zmian w warstwie prezentacji.

### Dodanie nowej marki

1. Nowa wartość w `Protocol` (nazwa, rodzaj transportu, marki, czy wymaga parowania).
2. Klasa dziedzicząca po `AbstractTransport` z mapą `RemoteKey → kod protokołu`.
3. Gałąź w `TransportFactory`.
4. Rozpoznawanie w `SsdpResponseParser`, jeśli urządzenie odpowiada na SSDP.

`KeyCoverageTest` automatycznie sprawdzi, czy nowy sterownik obsługuje komplet
klawiszy podstawowych.

## Bezpieczeństwo

Samsung serwuje samopodpisany certyfikat, którego nie da się zweryfikować — żaden CA
go nie podpisał. Aplikacja używa dla niego klienta z wyłączoną walidacją
(`HttpClients.trustingLocalTv`), ale **wyłącznie** po sprawdzeniu, że adres należy do
sieci prywatnej (`LocalNetwork.requirePrivate`). Połączenie z adresem publicznym jest
odrzucane, zanim powstanie jakiekolwiek żądanie.

Tokeny parowania trzymane są w DataStore w prywatnym katalogu aplikacji.

## Uprawnienia

- `CHANGE_WIFI_MULTICAST_STATE` — bez blokady multicastu Android odrzuca ramki SSDP
  i wyszukiwanie zawsze zwraca pustą listę.
- `BLUETOOTH_CONNECT` — proszone dopiero przy wejściu w tryb Bluetooth. Zadeklarowane
  bez `ACCESS_FINE_LOCATION`, bo nie używamy Bluetootha do ustalania położenia.
- `VIBRATE` — potwierdzenie naciśnięcia przycisku.

## Stan projektu

Wersja 0.1.0. Kod nie był jeszcze uruchomiony na fizycznym sprzęcie — mapowania kodów
klawiszy i przebiegi parowania opierają się na dokumentacji protokołów i wymagają
weryfikacji na realnych telewizorach.
