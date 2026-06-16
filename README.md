# Scale Bridge

Citește greutatea de la cântarul BLE `IF_xxx` (chip Telink) și o scrie în
**Health Connect**, de unde **Zepp** (≥ 9.10.0) o preia automat.

## Protocol (reverse-engineered de pe IF_B6A)

- Service `0xFFF0`, notify characteristic `0xFFF1`. Cântarul streamuiește singur
  (nu trebuie scris nimic pe `0xFFF2`).
- Frame, 14 bytes:
  ```
  10 00 00 C5 0E 03 80 | W_hi W_lo | imp_hi imp_lo | 11 | flag | cksum
  ```
  - `weight_kg = ((data[7] << 8) | data[8]) / 100.0`
  - `data[12]`: `0xA0` = instabil (live), `0xAA` = stabil
- Exemplu: `... 35 98 ...` → `0x3598 = 13720 / 100 = 137.2 kg`.

App-ul scrie o singură dată per cântărire (se „re-armează" pe frame-urile live,
trage pe primul frame stabil).

## Build & instalare (Android Studio)

1. **Open** folderul ăsta în Android Studio (nu „Import").
2. La sync, dacă se plânge de wrapper, alege **Use Gradle wrapper** / lasă-l să
   descarce Gradle 8.7. Acceptă instalarea SDK 34 dacă o cere.
3. Conectează telefonul (USB debugging pornit) și **Run ▶**. Sau
   `Build > Build APK(s)` și copiezi APK-ul pe telefon (sideload).

> Dacă dependența `androidx.health.connect:connect-client:1.1.0-rc03` nu se
> rezolvă, pune ultima versiune din *Project Structure > Dependencies* și
> re-sync. `Metadata.manualEntry()` e din aceeași librărie — dacă pică la
> compilare, IDE-ul îți oferă varianta corectă pt versiunea ta.

## Folosire

1. Pornește **Bluetooth**. Deschide Scale Bridge, apasă **START**, acordă
   permisiunile (Bluetooth + Health Connect → Write Weight).
2. **Urcă pe cântar.** Când se stabilizează, app-ul scrie greutatea în Health
   Connect (vezi mesajul „Scris în Health Connect: … ✓").

## Conectare la Zepp

1. Telefon: **Health Connect** (Android 14 are built-in; altfel din Play Store) →
   verifică în „Data and access" că **Scale Bridge** are permisiune pe *Weight*.
2. **Zepp** (≥ 9.10.0): *Profile → Settings → Health Connect* (sau *Third-party
   access*) → activează și permite **citirea Weight**.
3. De acum, fiecare cântărire ajunge: `IF_xxx → Scale Bridge → Health Connect → Zepp`.

## Limitări

- Scrie **doar greutatea**. Impedanța (`data[9..10]`) e citită din frame dar nu e
  publicată (body-fat ar cere formulă BIA + profil; nu e implementat).
- Detectează cântarul după prefixul de nume `IF_`. Dacă al tău are alt nume,
  schimbă `TARGET_NAME_PREFIX` în `MainActivity.kt`.
