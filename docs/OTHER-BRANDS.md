# Other brands: left, right and case battery

How QuickBuds gets battery levels of earbuds outside `models.json`. The supported OnePlus, OPPO and realme
buds use their own protocol ([PROTOCOL.md](./PROTOCOL.md)) and nothing here.

## Sources

Code: `app/src/main/kotlin/com/spizganed/quickbuds/bluetooth/battery/`, run by `OtherEarbudsMonitor`.

| Source | Gives | When | Needs |
|---|---|---|---|
| Standard level (`ACTION_BATTERY_LEVEL_CHANGED`) | one level for the pair | the headset reports it | BLUETOOTH_CONNECT |
| Fast Pair advertisement (`0xFE2C`) | left, right, case, charging | mostly with the case open | BLUETOOTH_SCAN |
| AirPods / Beats advertisement (Apple `0x004C`, type `0x07`) | left, right, case in 10 % steps, charging | near the phone | BLUETOOTH_SCAN |
| Fast Pair message stream (RFCOMM `df21fe2c-…`) | left, right, case, charging | on connect and on change | BLUETOOTH_CONNECT |
| Brand protocol (RFCOMM, `BrandProtocol`) | left, right, case (charging per brand) | a short session every 10 min | BLUETOOTH_CONNECT |

- `BatteryResolver` picks one source: the highest rank (brand, then Fast Pair stream, then the broadcasts),
  then the newest. It drops a source with no level, an own report older than 30 min, and levels with no bud
  within 30 % of the standard level.
- One level stays one level. QuickBuds never splits it into left and right.
- An advertisement has a random address. QuickBuds matches it by levels and signal (Fast Pair) or by signal
  alone (Apple, only with one pair of AirPods or Beats connected).
- A brand session connects, sends the battery reads, reads for 5 s and closes. The brand's own app finds the
  channel free between sessions. A failed connect is tried again after 30 min. The raw bytes of a session
  without levels go to the log (`BRAND BATTERY:`).

## Compatibility

Status: **Verified** (tested on real earbuds here), **Experimental** (from published source code, not
tested here), **Probe** (sends the documented read, logs the reply, shows nothing), **Not supported**.

| Brand / models | Source | Left | Right | Case | Charging | Status | Facts from |
|---|---|---|---|---|---|---|---|
| Any Fast Pair earbuds | advertisement | ✔ | ✔ | ✔ | ✔ | Experimental (shipped since 4.10.8) | Google spec |
| Any Fast Pair earbuds | message stream | ✔ | ✔ | ✔ | ✔ | Experimental: Play services opens the same channel | Google spec |
| AirPods, Beats | advertisement | ✔ 10 % | ✔ 10 % | ✔ 10 % | ✔ | Experimental | OpenPods `PodsStatus.java` |
| Samsung Galaxy Buds2 and later | `2e73a4ad-…`, listen only | ✔ | ✔ | ✔ | — | Experimental | GalaxyBudsClient, krisgfx9, Gadgetbridge |
| Samsung Galaxy Buds+, Live | SPP + name | ✔ | ✔ | ✔ | — | Experimental | Gadgetbridge |
| Samsung Galaxy Buds (2019) | `00001102-…34fd` | ✔ | ✔ | — | — | Experimental | Gadgetbridge |
| Nothing, CMF | `aeac4a03-…`, read `0xC007` | ✔ | ✔ | ✔ | ✔ | Experimental | Gadgetbridge, krisgfx9 |
| SoundPEATS | SPP + name, GAIA `0x0306/07/23` | ✔ | ✔ | ✔ | — | Experimental (source tested an H3) | OpenPeats |
| EarFun | SPP + name, GAIA `0x0306/07` | ✔ | ✔ | Air Pro 4 only (`0x0317`) | — | Experimental | Gadgetbridge |
| boAt (WuQi chip) | `0x7034`, read `01 05` | — | — | — | — | Probe | boat-tws-commands |
| Sony WF / WH | — | | | | | Not supported yet: see Limits | |
| Soundcore, Huawei / Honor, Xiaomi / Redmi, Bose, JBL | — | | | | | Not supported yet | |
| Noise, Marshall, other boAt chips | — | | | | | Not supported: no published protocol | |

A model becomes Verified when a log shows its levels and they match the earbuds' own app.

## Limits

- **Sony:** payload `0x22` is the battery read in protocol v2 and **power off** in v1. Gadgetbridge picks the
  version per model, and each session needs an init with sequence numbers and acknowledgements. Not added until
  that model table is in.
- **Brands with a login or keys:** Xiaomi / Redmi need a challenge-response first. Not added.
- **JBL:** BLE GATT, not RFCOMM. Needs a BLE session reader.
- **AirPods:** the exact percent is in the encrypted part of the advertisement. Only 10 % steps.
- **Android 12+:** QuickBuds scans with `neverForLocation`. Android may drop some advertisements from such a
  scan. Whether Apple's is one of them is not known yet: the first AirPods test answers it.
- **Fast Pair message stream:** Play services opens it on each connect. Whether a second reader gets the
  battery message is not known yet: the log answers it.
- **Never sent:** any command that is not a battery read. Some brands' other commands reset or unpair the
  earbuds (boAt `05 01`, Sony v1 `0x22`).

## Add a brand protocol

1. Find the facts in a published source: service UUID, the battery read, the reply layout. Code under GPL or
   AGPL is a source of facts only: write the adapter, do not copy.
2. Add an `object` in `bluetooth/battery/brands/` that implements `BrandProtocol`:
   - `uuid`: the brand's own service. On the generic SPP service, also check the brand name in `matches`.
   - `requests()`: battery reads only, byte for byte as the source sends them.
   - `parse()`: check headers, lengths and checksums; return the newest levels; -1 for a part not reported.
3. Add it to `BrandProtocols.ALL`: brand services first, generic SPP last.
4. Add unit tests in `app/src/test/…/bluetooth/battery/brands/`: the request bytes, a reply from the source,
   a bad checksum, a frame not complete yet.
5. Add a row to the table above as Experimental. After a test with real earbuds, mark it Verified.

## Test on a phone

1. Connect the earbuds. Open QuickBuds once.
2. Wait 10 s, then export the log (Dev tools). Look for:
   - `BRAND BATTERY: <id> (experimental) for these earbuds`, then `L=… R=… C=…` or `no levels in N bytes: …`,
   - `FASTPAIR: apple L=…` for AirPods and Beats,
   - `OTHER EARBUDS: battery from <source>`.
3. Compare the levels with the brand's own app. Send the log lines with the result.
