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

On the phone screen, but for now not on the Android Auto screen, text
scrolls by and the word currently being sounded out is highlighted.

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

### Release builds

A release build is smaller and faster. To sign it with your own key, create a
keystore once:

    keytool -genkeypair -v -keystore morsecar.jks -keyalg RSA -keysize 4096 -validity 10000 -alias morsecar

then a `keystore.properties` in the project root (git-ignored, like the `.jks`):

    storeFile=morsecar.jks
    storePassword=...
    keyAlias=morsecar
    keyPassword=...

and build with `./gradlew :app:assembleRelease`. Keep the keystore safe: updates
must be signed with the same key. Switching between debug and release builds
means uninstalling first, which erases saved positions and settings.

See [PLAN.md](PLAN.md) for the architecture, status, and a device testing checklist.
