# Samsung Health Data SDK

The research build intentionally compiles without Samsung's proprietary AAR.

For a physical-device integration build:

1. Request/download the current Samsung Health Data SDK from Samsung Developer.
2. Accept Samsung's licence and copy the supplied Data SDK AAR into this folder.
3. Implement `SamsungHealthDataSource` in `data/samsung` against that exact SDK version.
4. Keep reads explicit and user-approved. The private pilot is read-only.
5. Use Samsung Health's Developer Mode while sideloading the private pilot. Public
   distribution requires Samsung partnership approval and a production access key.

Do not commit proprietary AARs, developer keys, access tokens, or exported health data.
