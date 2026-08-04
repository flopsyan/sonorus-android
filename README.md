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
