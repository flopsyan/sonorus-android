# Sonorus for Android

Native Kotlin client for [Sonorus](https://github.com/flopsyan/sonorus), built
against the REST API that server already has. **Nothing has to be changed on the
server side**: a native client sends neither `Origin` nor `Sec-Fetch-Site`, and
`rejectCrossSite` deliberately lets exactly those requests through.

What the app adds over the web app: playback keeps running in the background,
and Android draws the notification itself - title, cover art, progress and
next/previous - instead of whatever the browser happens to grant.

In the full player the **cover art is swiped sideways** to jump to the next or
previous song; swiping it down closes the player. Its head says which list is
playing ("Wiedergabe aus Album" and the name below it), and the three dots
beside it open the song: add it to a playlist, put it in the queue, or go to its
album or its interpret. Artist and album under the title are two separate links,
each going where it says. A title too long to fit is cut off, and **tapping it
runs it through once** so it can be read.

The progress bar can be tapped **and dragged**: hold, slide, release - the seek
happens on release, not before. That goes for the full player as well as the bar
along the bottom edge.

## Android Auto

In the car Sonorus appears among the media apps and can be browsed there:
shuffle mix, recently played, playlists, ratings, artists, albums, genres and
all songs. Tapping a song plays **the list** it sits in, starting at exactly
that point - the same thing tapping it on the phone does. Voice search ("play
…") searches title, artist and album at once.

The car drives the same player as the phone: a trip counts towards the
statistics like any other listening, and the queue is the same one afterwards.
Downloaded songs play from disk, and with no connection it shows exactly those
downloads.

## Split screen: the strip

Squeezed into a short window - split screen with the divider dragged most of the
way over - the app drops the library and shows the transport instead. It is meant
for navigation on the other half of the screen, where a top bar, a page and six
tabs would only be furniture cut in half.

There are two sizes of it, and the app picks whichever fits:

- **the strip**, from 175 dp of height: artwork, name, interpret and album, a
  seek rail you can drag, and previous / play / next;
- **the panel**, under that: one line - artwork, name, interpret and the three
  controls - with the progress as a hairline across the top. Nothing on it is
  draggable, because a scrub target three pixels tall between two buttons is a
  mis-tap at the wheel.

It switches on its own, below **320 dp of window height and only in multi-window**,
so a phone held sideways still gets the whole app. Dragging the divider back
restores the shell on the page it was on.

## Qualität

Two settings of their own, both per **device** and not per account: what is
streamed, and what a download fetches. A phone on a train and a browser on the
LAN are the same login and want opposite things.

**Only lossless is ever re-encoded** - FLAC, WAV, ALAC, APE, WavPack, DSD. An
MP3, an AAC or an Opus file is handed over as it lies whatever its bitrate, so
picking "Opus 128" changes nothing at all for a podcast that is already an MP3.
The chip under the transport therefore says the format really coming out of the
speaker, not the one that was asked for, and one tap on it switches the setting
mid-song. The rule sits in `Quality.served` and mirrors `willTranscode` on the
server: **change one side and change the other**, `QualityTest` is what catches
it if you do not.

## Statistik

The same page the web app has, and it counts **all four libraries**: music,
podcasts, audiobooks and radio plays. The playtime, the chart and the averages
are all of them together; a Spielzeit table splits the selected period per
library, and "Meistgehörtes Gesprochenes" ranks shows, books and radio plays.
The three music top lists stay music alone - one 70-minute episode outweighs a
dozen songs.

## Downloads and offline use

Songs, albums, playlists, genres and rating lists can be pulled onto the device
("Download" in the head of a collection, in a song's menu, or - for whatever is
playing right now - straight from the full player, beside the "+"). Whatever is
downloaded then **always** plays from disk - connection or not, which saves
mobile data on the road.

**With no connection the app starts straight into its downloads**, with no step
in between: no login form, no progress bar, no request that would have to run
into a timeout first. Library, artists, albums, genres, playlists, search and
lyrics then come from what is on the device; a strip under the title bar says
that the short library is the one being shown. As soon as a server can be
reached again, the app switches back by itself.

Anything that needs a connection is switched off while offline and says so:
rating, changing playlists, statistics, notices, accounts, scan and import.

The offline library is built out of the songs on the device, so it carries no
**album ratings**: an offline album shows no stars and sorting the grid by
rating falls back to the title. The stars are back as soon as a server is.

Under **Downloads** (in the sidebar) is what lies on the device and how much
space it takes. That is also where the two switches live: *Wi-Fi only* and a
manual *offline mode*, which stays on the downloads even when a connection
exists.

## Building the APK

```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
```

The finished APK lands in `app/build/outputs/apk/release/app-release.apk`.

For a **signed** release you need a `keystore.properties` in the project root
(not in the repository, see `.gitignore`):

```properties
storeFile=/path/to/sonorus-release.keystore
storePassword=…
keyAlias=sonorus
keyPassword=…
```

Without that file the project still builds - just unsigned.

**The signing key is permanent.** Lose it and an installed app can no longer be
updated, only uninstalled and installed afresh. Playlists, ratings and history
live on the server and survive that; the only thing lost is the local queue.

## Getting it onto the phone

Over USB, with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without a cable: copy the APK to the phone (cloud, mail, USB mass storage) and
open it there in the file manager. Android will ask once for permission to
install apps from that source.

The first start asks for the server address, username and password.

## The server address has to be HTTPS

The release build allows no cleartext HTTP. That is not a default put there to
be awkward, it follows from the thing itself: the session cookie carries the
`Secure` flag and is not sent back over HTTP anyway. An HTTPS address works, a
bare LAN IP does not.

The debug build (`assembleDebug`) allows HTTP to `10.0.2.2` and `localhost`, so
it can be run in the emulator against a local test instance.

## Versions

`compileSdk` stays at **36** and AGP at **8.13.2**, because API 37 so far exists
only in the preview channel. The androidx libraries are therefore pinned to the
last version each that accepts 36 - see the note in
`gradle/libs.versions.toml`. Anyone raising one of them has to raise compileSdk
and AGP along with it.

## What is still missing

- Playlists cannot be reordered by dragging in the sidebar. The endpoint
  (`PUT /api/playlists/order`) is present in the client, only the gesture is
  missing.
- The cover art crop is built, but has not yet been run through with a real
  photo on a device.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
