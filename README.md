# Challenge Timer

Fabric-Mod für Minecraft 26.1–26.3: ein Timer in der Actionbar – als Stoppuhr (zählt hoch) oder als Countdown (zählt runter, mit Titel „Zeit abgelaufen!“ am Ende).

## Befehle

| Befehl | Wirkung |
|---|---|
| `/timer` | aktuellen Stand anzeigen |
| `/timer start` / `/timer resume` | starten bzw. fortsetzen |
| `/timer pause` | anhalten |
| `/timer reset` | zurücksetzen (Countdown: auf die zuletzt gesetzte Zeit) |
| `/timer set <zeit>` | Zeit setzen – z. B. `1h30m`, `90m`, `45s`, `2d`, `1:30:00`, `90` (= Minuten) |
| `/timer modus stoppuhr` / `countdown` | Zählrichtung wählen |
| `/timer anzeige an` / `aus` | Actionbar ein- oder ausblenden |
| `/timer hilfe` | alle Befehle |

Der Stand wird pro Welt in `challengetimer.properties` (im Weltordner) gespeichert. Nach einem Neustart ist der Timer pausiert und läuft mit `/timer start` weiter. Im Einzelspieler-Pausemenü bleibt er automatisch stehen.

## Installation

Die `.jar` zusammen mit **Fabric API** in den `mods`-Ordner legen. Auf einem Server reicht die Mod auf dem Server – Spieler brauchen sie nicht.

## Bauen

Bei jedem Push baut GitHub Actions die Mod. Die fertige Datei liegt unter **Actions → letzter Lauf → Artifacts → ChallengeTimer**.
