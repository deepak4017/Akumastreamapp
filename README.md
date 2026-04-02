# Akuma SMP — Live Streaming App
### Android · Kotlin · RTMP · MediaProjection

---

## Files in this package

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Main screen: credential input, platform toggle, stream start/stop |
| `StreamingService.kt` | Foreground service: MediaProjection capture + RootEncoder RTMP push |
| `activity_main.xml` | XML layout for MainActivity |
| `AndroidManifest.xml` | App permissions and service declaration |
| `build.gradle` | Dependencies including RootEncoder 2.4.6 |

---

## Setup in Android Studio

### 1. Create project
- New Project → Empty Views Activity
- Package: `com.akumasmp.streamer`
- Language: Kotlin
- Min SDK: API 26

### 2. Add JitPack to settings.gradle
```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }   // ← add this
    }
}
```

### 3. Replace generated files
Copy each file from this package into the corresponding location:

```
app/
├── src/main/
│   ├── java/com/akumasmp/streamer/
│   │   ├── MainActivity.kt          ← replace
│   │   └── StreamingService.kt      ← add new file
│   ├── res/layout/
│   │   └── activity_main.xml        ← replace
│   └── AndroidManifest.xml          ← replace
└── build.gradle                     ← replace (app-level only)
```

### 4. Add missing resources
Create `res/values/colors.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="background">#FFFFFF</color>
    <color name="text_primary">#1A1A1A</color>
    <color name="text_secondary">#666666</color>
    <color name="text_tertiary">#999999</color>
    <color name="live_green">#1DB954</color>
    <color name="stop_red">#E53935</color>
    <color name="live_white">#FFFFFF</color>
    <color name="divider">#E0E0E0</color>
</resources>
```

Create `res/drawable/bg_live_badge.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#E53935" />
    <corners android:radius="4dp" />
</shape>
```

Create `res/drawable/ic_live_notification.xml` (use any simple vector icon for the notification).

Create `res/drawable/ic_stop.xml` (stop icon for notification action).

Add to `res/values/strings.xml`:
```xml
<string name="app_name">Akuma SMP</string>
```

Also create `AkumaApp.kt`:
```kotlin
package com.akumasmp.streamer
import android.app.Application
class AkumaApp : Application()
```

### 5. Sync and build
- File → Sync Project with Gradle Files
- Build → Make Project

---

## How it works

```
User taps "Go Live"
       ↓
Request MediaProjection permission (Android system dialog)
       ↓
Start StreamingService (foreground)
       ↓
MediaProjection → VirtualDisplay → Surface
       ↓
RootEncoder GenericStream encodes H.264 video + AAC audio
       ↓
RTMP push → YouTube / Twitch ingest URL
       ↓
Persistent notification with "Stop" action
```

---

## Key notes

- **Android 14+ (API 34)**: The `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission AND
  `foregroundServiceType="mediaProjection"` on the service are both required.
  The manifest already has both — don't remove them.

- **Internal audio**: Requires the game to opt-in via `AudioAttributes` usage.
  Minecraft (Bedrock) supports this. Some apps block internal audio capture.

- **Stream keys**: Stored in `EncryptedSharedPreferences` using AES-256-GCM.
  They are never transmitted anywhere except the RTMP server URL you enter.

- **RTMP URL format**: `rtmp://server/app/streamkey`
  - YouTube: `rtmp://a.rtmp.youtube.com/live2/YOUR_STREAM_KEY`
  - Twitch: `rtmp://live.twitch.tv/app/YOUR_STREAM_KEY`

---

## RootEncoder library
GitHub: https://github.com/pedroSG94/RootEncoder
Version used: 2.4.6
License: Apache 2.0
