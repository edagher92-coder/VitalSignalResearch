# Samsung Health Sensor SDK adapter

The open watch module intentionally has **no proprietary Samsung dependency**.
It compiles against AndroidX Health Services and the public Wearable Data Layer,
while `SamsungSensorAdapter` is the stable boundary for the private integration.

## Private pilot setup

1. Download the currently approved Samsung Health Sensor SDK package from the
   [official Samsung developer portal](https://developer.samsung.com/health/sensor/overview.html)
   and review its licence and device requirements.
2. Copy the supplied sensor API AAR to this directory as
   `samsung-health-sensor-api.aar`. Do not commit the licensed binary.
3. Add this line to `wear/build.gradle.kts` inside `dependencies`:

   ```kotlin
   implementation(files("libs/samsung-health-sensor-api.aar"))
   ```

4. Implement `SamsungSensorAdapter` in a private integration source file. Keep
   all `com.samsung.android.service.health.tracking.*` imports inside that
   adapter; translate SDK tracker events and status codes into the public
   `SensorPacket`, `SensorCapability`, and `RawQualitySignals` models.
5. Install the adapter once during app startup:

   ```kotlin
   ResearchCaptureRuntime.adapters.install(SamsungHealthSensorAdapter(context))
   ```

6. Enable Samsung Health Sensor SDK developer mode on the physical pilot watch
   and verify every requested tracker through the SDK capability API. Developer
   mode is for development and a private sideloaded pilot; public distribution
   requires the applicable Samsung partner approval.

## Capture rules encoded by this scaffold

- All-day heart-rate and step baselines belong to AndroidX Health Services
  passive monitoring, not a continuously running foreground service.
- High-fidelity continuous trackers run only in an explicit, visible research
  session backed by `ResearchCaptureService`.
- Only one Samsung on-demand tracker may run at a time, and the app must enforce
  the SDK's 30-second maximum window.
- Samsung tracker callbacks may be batched while the display is off. Preserve
  source timestamps and never substitute callback-arrival time.
- The Samsung SDK does not persist or transfer samples. Encrypt and batch them,
  queue them through the Data Layer, and delete the watch copy only after the
  phone validates its checksum and returns an acknowledgement.

Official setup and tracker references:

- [Samsung Health Sensor SDK developer guide](https://developer.samsung.com/health/sensor/guide/development-environment.html)
- [Samsung tracker types](https://developer.samsung.com/health/sensor/guide/tracker-types.html)
- [Android Health Services passive data](https://developer.android.com/health-and-fitness/guides/health-services/passive)
- [Wear OS Data Layer](https://developer.android.com/training/wearables/data/overview)
