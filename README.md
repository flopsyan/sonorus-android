# Sonorus für Android

Nativer Kotlin-Client für [Sonorus](https://github.com/flopsyan/sonorus), gebaut
gegen die REST-API, die der Server ohnehin hat. **Am Server muss nichts geändert
werden**: Ein nativer Client schickt weder `Origin` noch `Sec-Fetch-Site`, und
`rejectCrossSite` lässt genau diese Anfragen absichtlich durch.

Was die App gegenüber der Web-App draufhat: Die Wiedergabe läuft im Hintergrund
weiter, und Android zeichnet die Benachrichtigung selbst - Titel, Cover,
Fortschritt und Weiter/Zurück - statt dem, was der Browser gerade gewährt.

Im großen Player wird das **Cover seitlich gewischt**, um zum nächsten oder
vorherigen Song zu springen; nach unten gewischt schließt es den Player. Der Kopf
sagt, welche Liste gerade läuft ("Wiedergabe aus Album" und darunter der Name),
und die drei Punkte daneben öffnen den Song: zur Playlist hinzufügen, in die
Warteschlange legen oder zum Album bzw. zum Interpreten gehen. Interpret und
Album unter dem Titel sind zwei getrennte Links, jeder führt dorthin, wo er
hinzeigt. Ein zu langer Titel wird abgeschnitten, und **ein Tipp darauf lässt ihn
einmal durchlaufen**, damit er lesbar ist.

Die Fortschrittsleiste lässt sich antippen **und ziehen**: halten, schieben,
loslassen - gesprungen wird beim Loslassen, nicht vorher. Das gilt für den großen
Player wie für die Leiste am unteren Rand.

## Android Auto

Im Auto taucht Sonorus unter den Medien-Apps auf und lässt sich dort durchgehen:
Zufallsmix, zuletzt gehört, Playlists, Bewertungen, Interpreten, Alben, Genres
und alle Songs. Ein Tipp auf einen Song spielt **die Liste**, in der er steht,
genau ab dieser Stelle - dasselbe, was ein Tipp auf dem Handy tut. Die Sprachsuche
("spiele …") durchsucht Titel, Interpret und Album gleichzeitig.

Das Auto steuert denselben Player wie das Handy: Eine Fahrt zählt für die
Statistik wie jedes andere Hören, und die Warteschlange ist hinterher dieselbe.
Heruntergeladene Songs laufen von der Platte, und ohne Verbindung zeigt es genau
diese Downloads.

## Geteilter Bildschirm: der Streifen

In ein kurzes Fenster gequetscht - geteilter Bildschirm mit dem Trenner fast ganz
herübergezogen - lässt die App die Bibliothek weg und zeigt stattdessen die
Transportleiste. Gedacht ist das für die Navigation auf der anderen
Bildschirmhälfte, wo eine Titelleiste, eine Seite und sechs Tabs nur halbierte
Möblierung wären.

Es gibt zwei Größen davon, und die App nimmt, was passt:

- **den Streifen**, ab 175 dp Höhe: Cover, Name, Interpret und Album, eine
  ziehbare Suchleiste und Zurück / Play / Weiter;
- **die Leiste**, darunter: eine Zeile - Cover, Name, Interpret und die drei
  Bedienelemente - mit dem Fortschritt als Haarlinie darüber. Nichts daran ist
  ziehbar, denn ein drei Pixel hohes Ziel zwischen zwei Knöpfen ist am Lenkrad
  ein Fehlgriff.

Umgeschaltet wird von selbst, **unter 320 dp Fensterhöhe und nur im
Mehrfenstermodus**, damit ein quer gehaltenes Handy noch die ganze App bekommt.
Zieht man den Trenner zurück, ist die Hülle wieder da, auf der Seite, auf der sie
war.

## Qualität

Zwei eigene Einstellungen, beide pro **Gerät** und nicht pro Konto: was gestreamt
wird und was ein Download holt. Ein Handy im Zug und ein Browser im LAN sind
dieselbe Anmeldung und wollen das Gegenteil voneinander.

**Umgewandelt wird nur Verlustfreies** - FLAC, WAV, ALAC, APE, WavPack, DSD. Eine
MP3, eine AAC- oder eine Opus-Datei wird ausgeliefert, wie sie liegt, egal mit
welcher Bitrate; "Opus 128" zu wählen ändert für einen Podcast, der schon eine MP3
ist, also gar nichts. Der Chip unter der Transportleiste sagt deshalb das Format,
das wirklich aus dem Lautsprecher kommt, nicht das, was gewünscht war - und ein
Tipp darauf öffnet die Auswahl mitten im Song.

Ist "Lossless nur über WLAN" an und du bist auf mobilen Daten, lässt sich das
Original **einmalig für genau diesen Song** trotzdem anfordern. Das gilt auch für
einen Song, der bereits klein heruntergeladen auf dem Gerät liegt: Er kommt dann
für dieses eine Mal in voller Qualität vom Server, der nächste läuft wieder vom
Gerät.

Die Regel steckt in `Quality.served` und spiegelt `willTranscode` auf dem Server:
**Ändert man die eine Seite, ändert man die andere**, und `QualityTest` ist das,
was es merkt, wenn nicht.

## Statistik

Dieselbe Seite, die die Web-App hat, und sie zählt **alle vier Bibliotheken**:
Musik, Podcasts, Hörbücher und Hörspiele. Spielzeit, Diagramm und Durchschnitte
sind alle zusammen; eine Spielzeit-Tabelle teilt den gewählten Zeitraum nach
Bibliothek auf, und "Meistgehörtes Gesprochenes" reiht Sendungen, Bücher und
Hörspiele. Die drei Musik-Bestenlisten bleiben allein Musik - eine 70-Minuten-
Folge wiegt ein Dutzend Songs auf.

Eine Wiedergabe wird über die Stunden verteilt, durch die sie wirklich lief: Ein
Hörspiel, das um 14:40 beginnt und zweieinhalb Stunden läuft, sind zwanzig Minuten
in der 14-Uhr-Säule, je eine Stunde in 15 und 16 Uhr und zehn Minuten in 17 Uhr -
gezählt wird es trotzdem als **eine** Wiedergabe. Welche Stunde das ist, entscheidet
die Uhr des Servers, damit dieselbe Vergangenheit auf jedem Gerät und in jedem Land
gleich aussieht.

## E-Books

Die fünfte Bibliothek, und die einzige, die gelesen statt gehört wird: ein Regal
mit Autoren und ihren Büchern, und eine Leseansicht dafür.

Die Leseansicht selbst kommt **vom Server** (`public/reader/`) und läuft in einer
WebView - ein EPUB ist HTML mit eigenem Stylesheet, und ein Buch, das seine
Formatierung verliert, liest sich falsch. Was die App drumherum tut:

- **Umblättern** durch Tippen auf die linke oder rechte Seite, oder durch Wischen.
  Die Seitenleiste gibt die Wischgeste her, solange ein Buch offen ist.
- **Die Seitenzahl des ganzen Buchs**, dauerhaft im unteren Rand: `38/379
  (10,0 %)`. Ein EPUB hat keine feste Seitenzahl - eine Seite ist, was bei dieser
  Schriftgröße auf diesen Bildschirm passt - also wird sie gemessen: Im
  Hintergrund wird jedes Kapitel einmal gesetzt und gezählt, gespeichert pro Buch,
  Schrift und Bildschirm. Bis das durch ist, steht dort eine Schätzung aus den
  Zeichenzahlen, damit ein Buch aufgeht, statt zu laden.
- **Schrift, Größe, Zeilenabstand und Rand**, live übernommen, ohne das Buch zu
  schließen. Die Größe geht bis auf 6 px herunter.
- **Eine ziehbare Fortschrittsleiste** durchs ganze Buch, und danach ein Knopf
  zurück an die verlassene Stelle - er steht eine halbe Minute lang da.
- **Die Stelle wird als Anteil eines Kapitels gemerkt**, nicht als Seitenzahl:
  Eine Seite ist auf einem anderen Gerät eine andere Seite.

Ein Buch lässt sich **herunterladen** und dann ohne jeden Server lesen. Mit der
EPUB-Datei wandert die Leseansicht selbst mit - Stylesheet, Skript und die vier
Ubuntu-Schnitte -, sonst ginge ein heruntergeladenes Buch beim ersten Mal ohne
Verbindung als unformatierter Text auf. Der Lesestand wird auch offline gemerkt
und nachgereicht, sobald der Server wieder da ist.

## Downloads und Offline-Betrieb

Songs, Alben, Playlists, Genres, Bewertungslisten, Hörbücher, Hörspiele,
Podcast-Folgen, alle Songs und E-Books lassen sich auf das Gerät holen
("Herunterladen" im Kopf einer Sammlung, im Menü eines Songs oder - für das, was
gerade läuft - direkt aus dem großen Player, neben dem "+"). Was heruntergeladen
ist, läuft danach **immer** von der Platte - mit oder ohne Verbindung, was
unterwegs mobile Daten spart.

**Ohne Verbindung startet die App direkt in ihre Downloads**, ohne Zwischenschritt:
kein Anmeldeformular, kein Ladebalken, keine Anfrage, die erst in einen Timeout
laufen müsste. Bibliothek, Interpreten, Alben, Genres, Playlists, Suche, Songtexte
und heruntergeladene Bücher kommen dann von dem, was auf dem Gerät liegt; ein
Streifen unter der Titelleiste sagt, dass die kurze Bibliothek gezeigt wird.
Sobald wieder ein Server erreichbar ist, schaltet die App von selbst zurück.

Alles, was eine Verbindung braucht, ist offline abgeschaltet und sagt das:
bewerten, Playlists ändern, Statistik, Mitteilungen, Konten, Scan und Import.

Die Offline-Bibliothek wird aus den Songs auf dem Gerät gebaut und trägt deshalb
keine **Album-Bewertungen**: Ein Album zeigt offline keine Sterne, und das Raster
nach Bewertung zu sortieren fällt auf den Titel zurück. Die Sterne sind wieder da,
sobald ein Server da ist.

Unter **Downloads** (in der Seitenleiste) steht, was auf dem Gerät liegt und wie
viel Platz es braucht. Dort sitzen auch die beiden Schalter: *Nur über WLAN* und
ein manueller *Offline-Modus*, der auf den Downloads bleibt, auch wenn eine
Verbindung besteht.

Ein Download, der abgebrochen wurde, bleibt abgebrochen - auch wenn der Song zu
einer heruntergeladenen Playlist gehört. Ihn erneut anzufordern hebt das wieder
auf.

## Das APK bauen

```bash
export JAVA_HOME=/pfad/zu/jdk21
export ANDROID_HOME=/pfad/zum/android-sdk
./gradlew assembleRelease
```

Das fertige APK landet in `app/build/outputs/apk/release/app-release.apk`.

Für einen **signierten** Release braucht es eine `keystore.properties` im
Projektwurzelverzeichnis (nicht im Repository, siehe `.gitignore`):

```properties
storeFile=/pfad/zu/sonorus-release.keystore
storePassword=…
keyAlias=sonorus
keyPassword=…
```

Ohne diese Datei baut das Projekt trotzdem - eben unsigniert.

**Der Signierschlüssel ist endgültig.** Geht er verloren, lässt sich eine
installierte App nicht mehr aktualisieren, nur deinstallieren und neu
installieren. Playlists, Bewertungen und Verlauf liegen auf dem Server und
überleben das; verloren geht allein die lokale Warteschlange.

## Auf das Handy bekommen

Über USB, mit aktiviertem USB-Debugging:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Ohne Kabel: das APK aufs Handy kopieren (Cloud, Mail, USB-Massenspeicher) und
dort im Dateimanager öffnen. Android fragt einmal nach der Erlaubnis, Apps aus
dieser Quelle zu installieren.

Der erste Start fragt nach Serveradresse, Benutzername und Passwort.

## Die Serveradresse muss HTTPS sein

Der Release-Build lässt kein unverschlüsseltes HTTP zu. Das ist keine Schikane,
sondern folgt aus der Sache selbst: Das Sitzungs-Cookie trägt das `Secure`-Flag
und wird über HTTP ohnehin nicht zurückgeschickt. Eine HTTPS-Adresse
funktioniert, eine nackte LAN-IP nicht.

Der Debug-Build (`assembleDebug`) erlaubt HTTP zu `10.0.2.2` und `localhost`,
damit er sich im Emulator gegen eine lokale Testinstanz fahren lässt.

## Versionen

`compileSdk` bleibt bei **36** und AGP bei **8.13.2**, weil API 37 bisher nur im
Preview-Kanal existiert. Die androidx-Bibliotheken sind deshalb jeweils auf die
letzte Version festgenagelt, die 36 akzeptiert - siehe die Notiz in
`gradle/libs.versions.toml`. Wer eine davon anhebt, muss compileSdk und AGP
mit anheben.

## Was noch fehlt

- Playlists lassen sich in der Seitenleiste nicht per Ziehen umsortieren. Der
  Endpunkt (`PUT /api/playlists/order`) ist im Client vorhanden, es fehlt nur die
  Geste.
- Der Bildzuschnitt fürs Cover ist gebaut, aber noch nie mit einem echten Foto
  auf einem Gerät durchgespielt worden.

## Lizenz

Apache License 2.0 - siehe [LICENSE](LICENSE).
