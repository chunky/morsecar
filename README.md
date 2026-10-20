# MorseCar

## What?

An Android app for getting better at morse code on your
commute. Specifically, in addition to the normal android interface,
it has an android auto interface so it can be used in your car with
minimum interaction.

The app reads textfiles from a folder on your phone, then outputs morse code.

On the screen, it shows sliders that lets you control WPM
for letters, letter spacing, and word spacing, plus a slider for the
tone frequency.

It also has a few preset buttons that set the speed sliders to
typical choices (eg Farnsworth timing, 20wpm across-the-board, etc).

It has a convenient interface for selecting what book to read; click a
button, select a folder, then select a book within that folder.

It remembers where you were in each of the books you are listening to,
and provides buttons to go forward/backward 15 or 30 seconds.

On the phone screen, text scrolls by and the word currently being
sounded out is highlighted. On the Android Auto screen (and the media
notification), the artwork box shows that word, highlighted, with a few
words either side.

## Building

Requires JDK 17+ and the Android SDK (`local.properties` points at it).

    ./gradlew :morse:test :app:testDebugUnitTest   # unit tests (JVM)
    ./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk

    # Render text to a WAV to hear the engine without a phone
    ./gradlew :morse:renderWav --args="--farnsworth 20/10 --out /tmp/cq.wav CQ CQ DE K1ABC"

## Sample books

Every `.txt` file in the top-level `samples/` folder is bundled into the APK
and listed as a "Samples" folder on the phone and in Android Auto, with no
setup. Add or remove files there and rebuild. `SamplesTest` checks that each
one loads.

## Installing

With USB debugging enabled on the phone:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

For Android Auto to list a self-installed app, enable Android Auto's developer
mode (Android Auto settings → tap *Version* ten times), then turn on *Unknown
sources* in its developer settings.

### Signing

Release and debug builds are both signed with your own key when
`keystore.properties` exists in the project root (it's git-ignored). Using one
key means Android Studio's Run and a release APK can replace each other on the
phone without uninstalling, which would erase saved positions and settings.

The key lives outside the project, so cleaning or re-cloning can't delete it:

    ~/.android/morsecar-signing/morsecar-release.jks
    ~/.android/morsecar-signing/keystore.properties   (password; a copy of ./keystore.properties)

**Back up that folder.** Without it, no future build can be installed over the
existing app.

On a new machine, copy that folder back and put a copy of its
`keystore.properties` in the project root. Without one, builds fall back to
Android Studio's debug key (release builds come out unsigned).

To make a fresh key instead, the setup used was:

    keytool -genkeypair -keystore morsecar-release.jks -storetype PKCS12 \
      -alias morsecar -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=MorseCar"

with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` in
`keystore.properties`.

### Installing a release build

    ./gradlew :app:assembleRelease
    adb install -r app/build/outputs/apk/release/app-release.apk

See [PLAN.md](PLAN.md) for the architecture, status, and a device testing checklist.
