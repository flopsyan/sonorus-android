# Sonorus für Android

Nativer Kotlin-Client für [Sonorus](https://github.com/flopsyan/sonorus), gebaut
gegen dessen bestehende REST-API. **Am Server muss dafür nichts geändert
werden**: ein nativer Client sendet weder `Origin` noch `Sec-Fetch-Site`, und
`rejectCrossSite` lässt genau solche Anfragen bewusst durch.

Was die App gegenüber der Web-App bringt: Wiedergabe läuft im Hintergrund
weiter, und die Benachrichtigung zeichnet Android selbst - mit Titel, Cover,
Fortschritt und Vor/Zurück, statt dessen, was der Browser gerade gewährt.

Im großen Player wird das **Cover zur Seite gewischt**, um zum nächsten oder
vorherigen Song zu springen; nach unten gewischt schließt es den Player.

Der Fortschrittsbalken lässt sich antippen **und ziehen**: festhalten, schieben,
loslassen - gesprungen wird erst beim Loslassen. Das gilt im großen Player wie
auf der Leiste am unteren Rand.

## Android Auto

Im Auto steht Sonorus unter den Medien-Apps und lässt sich dort durchblättern:
Zufallsmix, Zuletzt gehört, Playlists, Bewertungen, Interpreten, Alben, Genres
und Alle Songs. Ein Tippen auf einen Song spielt **die Liste**, in der er steht,
ab genau dieser Stelle - dasselbe, was ein Tippen am Handy tut. Die Sprachsuche
("Spiele …") sucht über Titel, Interpret und Album zugleich.

Das Auto steuert denselben Player wie das Handy: eine Fahrt zählt ganz normal in
die Statistik, und die Warteschlange ist danach am Handy dieselbe. Heruntergeladene
Songs spielt es von der Platte, und ohne Netz zeigt es genau diese Downloads.

## Downloads und Offline-Betrieb

Songs, Alben, Playlists, Genres und Bewertungslisten lassen sich auf das Gerät
laden ("Herunterladen" im Kopf einer Sammlung oder im Menü eines Songs). Was
geladen ist, spielt danach **immer** von der Platte - auch mit Verbindung, was
unterwegs Datenvolumen spart.

**Ohne Netz startet die App direkt in ihre Downloads**, ohne Zwischenschritt:
kein Login-Formular, kein Wartebalken, keine Anfrage, die erst in einen Timeout
laufen müsste. Bibliothek, Interpreten, Alben, Genres, Playlists, Suche und
Songtexte kommen dann aus dem, was auf dem Gerät liegt; ein Streifen unter der
Titelleiste sagt, dass die kurze Bibliothek gemeint ist. Sobald wieder ein
Server erreichbar ist, wechselt die App von selbst zurück.

Was eine Verbindung braucht, ist offline abgeschaltet und sagt das auch:
Bewerten, Playlists ändern, Statistik, Mitteilungen, Konten, Scan und Import.

Unter **Downloads** (Seitenleiste) steht, was auf dem Gerät liegt und wie viel
Platz es braucht. Dort sitzen auch die beiden Schalter: *Nur über WLAN* und ein
*Offline-Modus* von Hand, der auch bei bestehender Verbindung bei den Downloads
bleibt.

## APK bauen

```bash
export JAVA_HOME=~/Android/jdk21
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleRelease
```

Das fertige APK liegt unter `app/build/outputs/apk/release/app-release.apk`.

Für einen **signierten** Release braucht es eine `keystore.properties` im
Projektwurzelverzeichnis (nicht im Repo, siehe `.gitignore`):

```properties
storeFile=/pfad/zum/sonorus-release.keystore
storePassword=…
keyAlias=sonorus
keyPassword=…
```

Fehlt die Datei, baut das Projekt trotzdem - nur eben unsigniert.

**Der Signaturschlüssel ist dauerhaft.** Geht er verloren, lässt sich eine
installierte App nicht mehr aktualisieren, sondern nur deinstallieren und neu
installieren. Playlists, Bewertungen und der Verlauf liegen auf dem Server und
überleben das; verloren geht nur die lokale Warteschlange.

## Aufs Handy bringen

Per USB, mit aktiviertem USB-Debugging:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Ohne Kabel: die APK-Datei aufs Handy kopieren (Cloud, Mail, USB-Massenspeicher)
und dort im Dateimanager öffnen. Android fragt dann einmalig nach der Erlaubnis,
Apps aus dieser Quelle zu installieren.

Beim ersten Start werden Server-Adresse, Benutzername und Passwort abgefragt.

## Die Server-Adresse muss HTTPS sein

Der Release-Build erlaubt kein Klartext-HTTP. Das ist kein Schikane-Default,
sondern passt zur Sache: das Session-Cookie trägt das `Secure`-Flag und wird
über HTTP ohnehin nicht zurückgesendet. Über `https://sonorus.example.com`
funktioniert es, über eine nackte LAN-IP nicht.

Der Debug-Build (`assembleDebug`) erlaubt HTTP zu `10.0.2.2` und `localhost`,
damit man ihn im Emulator gegen eine lokale Testinstanz fahren kann.

## Versionen

`compileSdk` bleibt bei **36** und AGP bei **8.13.2**, weil API 37 bislang nur
im Preview-Kanal existiert. Die androidx-Bibliotheken sind deshalb auf die
jeweils letzte Fassung gepinnt, die 36 akzeptiert - siehe die Notiz in
`gradle/libs.versions.toml`. Wer eine davon hochzieht, muss compileSdk und AGP
mitziehen.

## Was noch fehlt

- Playlists lassen sich in der Seitenleiste nicht per Ziehen sortieren. Der
  Endpunkt (`PUT /api/playlists/order`) ist im Client vorhanden, nur die Geste
  fehlt.
- Der Cover-Zuschnitt ist gebaut, aber noch nicht mit einem echten Foto auf
  einem Gerät durchgespielt.
