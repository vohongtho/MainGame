# Tree Navigator — Production Deployment Contract

## 1. Runtime architecture

Production navigation uses a fixed tree identity and geographic target:

- `treeId`
- latitude
- longitude
- optional height above terrain

The app resolves that target through **ARCore Geospatial Terrain Anchors**. GPS is a global reference and is not allowed to drag an already resolved marker.

The generated “test tree” uses the same Terrain Anchor pipeline; only the source of the coordinates is synthetic.

## 2. Production target integration

A target can be supplied with Android Intent extras:

```text
TREE_ID
TREE_LAT
TREE_LNG
TREE_TERRAIN_OFFSET_M
```

or via deep link:

```text
treenavigator://navigate?treeId=T123&lat=1.3521&lng=103.8198&offset=0
```

The last valid target is persisted and re-resolved after app restart.

## 3. Google Cloud / ARCore authorization — REQUIRED

Enable the **ARCore API** in a Google Cloud project and authorize the Android app.

Preferred production option: keyless authorization tied to the release package/signing certificate.

Alternative: create a restricted ARCore API key and save it in GitHub Actions as:

```text
ARCORE_API_KEY
```

The workflow injects this value at build time. Never commit the key to the repository.

If neither API-key nor valid keyless authorization is configured, Geospatial/Terrain Anchor resolution must be treated as unavailable; the app must not silently fall back to a fake local target.

## 4. Release signing — REQUIRED for distribution

The repository currently builds an installable debug APK for field verification. A production store/internal-distribution artifact must use the organization’s release signing process (for example Play App Signing or a protected CI keystore).

Do not register an ephemeral CI debug certificate as the production keyless ARCore identity.

## 5. Required device conditions

- ARCore-supported Android device
- device must support Geospatial mode
- Google Play Services for AR installed/current
- precise location permission granted
- camera permission granted
- internet connectivity for Geospatial service

## 6. Runtime states

The UI must distinguish these states instead of pretending a marker is valid:

```text
ACQUIRING
LOCALIZING
WAITING FOR GEO LOCALIZATION
RESOLVING TERRAIN
GEOSPATIAL LOCKED
TARGET OFF SCREEN
TARGET BEHIND
LOCALIZATION DEGRADED
TARGET ERROR
GEOSPATIAL UNSUPPORTED
```

The tree marker is rendered only when the Terrain Anchor and Geospatial localization are usable.

## 7. Acceptance criteria

A build is not accepted as production-ready unless all of the following pass on a physical supported phone outdoors:

1. A real tree lat/lng resolves to a Terrain Anchor.
2. Restarting the app restores the same tree identity and re-resolves the geographic target.
3. Walking toward the tree reduces AR world-space distance.
4. Walking away increases AR world-space distance.
5. Walking past the tree moves it behind the camera and the UI requests a turn-around.
6. Pitch/roll/yaw move the marker through AR camera projection rather than a screen-fixed coordinate.
7. GPS jitter after lock does not directly move the marker.
8. Loss of Geospatial confidence hides the marker and reports localization degradation.
9. Recovery restores the marker only after valid localization returns.
10. Authorization, terrain-resolution, and tracking failures are visible in diagnostics.

## 8. CI gate

Current CI runs unit tests before building the APK. The artifact is uploaded only after tests, compilation, APK packaging, and file verification succeed.
