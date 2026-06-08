# BigBox3D

Kotlin Compose UI widget that renders the big box of a PC game in 3D, e.g. 
<video src="https://github.com/user-attachments/assets/2cbbfdf6-3f5a-484d-b3a9-fb9f471dd593" controls></video>

KMP library with targets (currently): Android, Web, JVM/Desktop, iOS. See more info in CLAUDE.md.

## KMP library usage

```kotlin
implementation("io.github.jhavatar:bigbox3d-compose:1.0.6")
```

**From URLs** (loaded at runtime):
```kotlin
BigBox3D(
    textures = BoxTextureUrls(
        front = "https://…/front.webp",
        back  = "https://…/back.webp",
        // Explicit left + right:
        sides = SideSource.Explicit(left = "https://…/left.webp", right = "https://…/right.webp"),
        // Or a spine image — right face is auto-generated as its horizontal mirror:
        // sides = SideSource.Spine("https://…/spine.webp"),
        // Or solid color — pass null to auto-derive color from the front image's edges:
        // sides = SideSource.ColorFill(),
        // Or procedural cardboard texture derived from the front/back image colors:
        // sides = SideSource.Cardboard(source = SideSource.FaceSource.Front),
        caps = CapSource.Explicit(top = "https://…/top.webp", bottom = "https://…/bottom.webp"),
        // Or solid color (auto-derived from front edge average when color not supplied):
        // caps = CapSource.ColorFill(),
        // Or procedural cardboard texture — Spine source falls back to Front if no spine URL:
        // caps = CapSource.Cardboard(source = CapSource.FaceSource.Spine),
    ),
    rotationSpeed = RotationSpeed.VERY_SLOW,     // NONE / VERY_SLOW / SLOW / NORMAL / FAST / VERY_FAST
    ambientBrightness = AmbientBrightness.NORMAL, // DARK / NORMAL / BRIGHT / VERY_BRIGHT — shadow floor
    brightness = Brightness.AUTO,                  // AUTO / DARK / NORMAL / BOOST / STRONG_BOOST — pre-lighting multiplier; AUTO detects from front face luminance
    onFrontLuminance = { pct -> /* 0–100: <15 very dark, 15–30 dark, 30–50 mid, >50 light */ },
    onLoadingChange = { isLoading -> /* fires true while atlas loads, false when ready */ },
)
```

**From bundled resources** (Compose Multiplatform `composeResources/files/`):
```kotlin
// In a coroutine / LaunchedEffect
BigBox3D(
    textures = BoxRawImages(
        front  = loadRawImageFromBytes(Res.readBytes("files/front.webp")),
        back   = loadRawImageFromBytes(Res.readBytes("files/back.webp")),
        left   = loadRawImageFromBytes(Res.readBytes("files/left.webp")),
        right  = loadRawImageFromBytes(Res.readBytes("files/right.webp")),
        top    = loadRawImageFromBytes(Res.readBytes("files/top.webp")),
        bottom = loadRawImageFromBytes(Res.readBytes("files/bottom.webp")),
    ),
)
```

`edgeAverageColor()` and `averageLuminancePercent()` are available as `RawImage` extensions for manual color/brightness inspection after loading.

## BigBox3DProgress — loading indicator

`BigBox3DProgress` is a convenience wrapper that uses `BigBox3D` as a loading spinner.

```kotlin
BigBox3DProgress(
    textures = spinnerTextures,
    visible = isLoading,
)
```

When `visible` becomes `false` the render loop pauses immediately (zero GPU cost), the last frame fades out, and the composable collapses to zero size once the fade finishes.

`BigBox3D` shows an empty sized box while loading (no built-in spinner). Wire `onLoadingChange` to know when each item is loading and overlay your own indicator — or use `BigBox3DProgress` directly (see `BigBox3DProgressPool` in the demo app for a ready-made helper).

### Atlas caching — zero reload on re-appearance

`BigBox3D` maintains a process-wide atlas cache keyed by `BoxTexture.boxKey()`. On re-appearance with the same textures the atlas is served from cache on the first frame — `isLoading` is `false` immediately, `onLoadingChange` never fires, and no spinner is shown.

**The `BoxRawImages` instance must be stable** (same object reference) for the cache to hit. Create it once with `remember` or as a top-level val:

```kotlin
// ✓ Stable instance — cache always hits on re-appearance
val spinnerTextures = remember {
    BoxRawImages(
        front  = loadRawImageFromBytes(Res.readBytes("files/front.webp")),
        // …
    )
}
BigBox3DProgress(textures = spinnerTextures, visible = isLoading)

// ✗ New instance every composition — always cache miss, always rebuilds
BigBox3DProgress(
    textures = BoxRawImages(front = loadRawImageFromBytes(…), …),
    visible = isLoading,
)
```

For `BoxTextureUrls` the key encodes the URL set, so the same URLs always hit the cache regardless of instance identity.

> [!NOTE]
> Images used were scraped from [Big Box Collection](https://bigboxcollection.com).

## Original legacy Android only library
### How to get it in your build
Step 1. Add it in your settings.gradle.kts at the end of repositories:
```
	dependencyResolutionManagement {
		repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
		repositories {
			mavenCentral()
			maven { url = uri("https://jitpack.io") }
		}
	}
```
Step 2. Add the dependency
```
	dependencies {
	        implementation("com.github.jhavatar:gamebigbox:v1.0.1")
	}
```


