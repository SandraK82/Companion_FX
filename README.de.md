🌐 [English](README.md) | **Deutsch** | [Français](README.fr.md)

> ⚠️ **Warnung:** Diese App sollte nur auf einem separaten Zweithandy im Follower-Modus benutzt werden, da sie die normale Smartphone-Nutzung stark einschränkt.

# Companion FX

Begleit-App für CamAPS FX, die Glukosewerte vom Bildschirm abliest und zu Nightscout hochlädt.

## Funktionen

- **Bildschirm-Auslesen**: Liest Glukosewerte, Trends und Pumpendaten von CamAPS FX
- **Nightscout-Integration**: Lädt Glukosewerte zu deiner Nightscout-Instanz hoch
- **SAGE/IAGE-Tracking**: Überwacht Sensoralter und Insulinalter, lädt zu Nightscout hoch
- **Homescreen-Widget**: Zeigt aktuellen Glukosewert und Trendgrafik an
- **Mehrsprachig**: Unterstützt deutsche, englische und französische CamAPS FX Versionen

## Installation

1. APK herunterladen und installieren
2. Erforderliche Berechtigungen erteilen (siehe unten)
3. Nightscout-URL und API-Secret in den Einstellungen konfigurieren
4. Bedienungshilfen-Dienst aktivieren

## Erforderliche Berechtigungen

### Bedienungshilfen-Dienst (Erforderlich)

Die App nutzt Androids Bedienungshilfen-Dienst, um Glukosedaten von CamAPS FX auszulesen.

**Einrichtung:**
1. Android **Einstellungen** öffnen
2. Zu **Bedienungshilfen** gehen (oder nach "Bedienungshilfen" suchen)
3. **Companion FX** in der Liste finden
4. Den Dienst aktivieren
5. Den Berechtigungsdialog bestätigen

### Akkuoptimierung (Empfohlen)

Für zuverlässige Hintergrund-Auslesungen die Akkuoptimierung für die App deaktivieren:

1. Android **Einstellungen** öffnen
2. Zu **Apps** > **Companion FX** gehen
3. Auf **Akku** tippen
4. **Uneingeschränkt** oder **Nicht optimieren** wählen

Bei einigen Geräten (Samsung, Xiaomi, Huawei) muss möglicherweise zusätzlich:
- Die App zur Liste "Geschützte Apps" oder "Autostart" hinzugefügt werden
- "Adaptive Akkunutzung" für diese App deaktiviert werden

### Benachrichtigungsberechtigung (Android 13+)

Die App fragt beim ersten Start nach der Benachrichtigungsberechtigung. Diese wird benötigt für:
- Anzeige der Vordergrund-Dienst-Benachrichtigung
- Sperrbildschirm-Auslesebenachrichtigungen

## Konfiguration

### Nightscout-Einstellungen

1. Nightscout-URL eingeben (z.B. `https://dein-nightscout.herokuapp.com`)
2. API-Secret eingeben
3. Verbindung testen
4. Nightscout-Sync aktivieren

### Ausleseintervall

Konfiguriere, wie oft die App Glukosewerte ausliest:
- Standard: 1 Minute
- Optionen: 1 Min, 5 Min, 15 Min

### SAGE/IAGE-Intervall

Konfiguriere, wie oft Sensor- und Insulinalter überprüft werden:
- Standard: 30 Minuten
- Optionen: 1 Min, 15 Min, 30 Min, 1 Stunde, 6 Stunden

## Unterstützte Geräte

- **Android**: 8.0 (API 26) und höher
- **CamAPS FX**: Alle Versionen mit deutscher, englischer oder französischer Oberfläche

## Fehlerbehebung

### App liest keine Glukosewerte

1. Sicherstellen, dass der Bedienungshilfen-Dienst aktiviert ist
2. Prüfen, dass CamAPS FX als Ziel-App eingestellt ist
3. Überprüfen, dass Akkuoptimierung deaktiviert ist

### Nightscout-Upload schlägt fehl

1. Nightscout-URL prüfen (kein abschließender Schrägstrich)
2. API-Secret überprüfen
3. Verbindung in den Einstellungen testen

### Widget aktualisiert sich nicht

1. Prüfen, dass der Dienst läuft (Benachrichtigung sollte sichtbar sein)
2. Widget entfernen und neu hinzufügen
3. Akkuoptimierung deaktivieren

## Datenschutz

- Alle Daten werden lokal auf deinem Gerät gespeichert
- Daten werden nur an deine persönliche Nightscout-Instanz gesendet
- Es werden keine Daten an andere Server gesendet

## Lizenz

MIT-Lizenz - siehe [LICENSE](LICENSE)

## Haftungsausschluss

Diese App ist nicht mit CamAPS FX, Ypsomed oder Abbott verbunden. Es ist eine unabhängige Begleit-App für das persönliche Diabetes-Management. Überprüfe Glukosewerte immer mit deinem CGM oder Blutzuckermessgerät für medizinische Entscheidungen.
