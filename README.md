# VoiceTunes

An Android app that plays your own music library by voice from a
[Navidrome](https://www.navidrome.org/) server (or any Subsonic-compatible one), with Android Auto
support.

It exists because Alexa+ broke "MyMedia for Alexa" on phones: asking Alexa to play your own music
now plays silence. VoiceTunes does the same job without Alexa or any cloud service: tap the
microphone, say what you want, and it plays from your server.

## What you can say

- "play Hotel California", "play Dreams I'll Never See by Molly Hatchet" (a song, then the rest of its album)
- "play the playlist Road Trip", "play Road Trip playlist"
- "play the album Rumours", "play Fleetwood Mac" (an artist: all their songs, shuffled)
- "shuffle Eagles", "next", "back", "pause", "resume", "refresh" (fetch the library again)

Matching is fuzzy (spoken numbers, "the", apostrophes, remaster tags and small misrecognitions
are all handled), and it's done on the phone against a saved copy of your library, so it's quick.

## Android Auto

VoiceTunes shows up as a media app: Shuffle everything, Playlists, Artists and Albums, plus
search. Tap Android Auto's microphone, or press the steering-wheel voice button and say "play
... on VoiceTunes". Because it isn't from the Play Store, turn on Android Auto's developer settings
(Android Auto settings, tap *Version* ten times, then the menu > Developer settings > *Unknown
sources*). Then open the app grid on the car's screen and pick VoiceTunes (*Customize launcher*
in Android Auto's settings decides which apps the grid shows).

Android Auto refuses to run while any VPN is on (it shows "communication error 21"), so you can't
reach a home server through a VPN while driving. Use an away address instead (below).

## Setup

1. Install Navidrome on a computer at home and point it at your music.
2. Install the APK (from Releases) on your phone.
3. In VoiceTunes' settings enter:
   - **Navidrome address at home**, e.g. `http://192.168.1.20:4533`
   - **User name** and **password**. The password itself isn't stored: the app keeps only the
     Subsonic API token (md5 of password + salt).
4. It downloads your library list and you're ready to go.

## Away from home

Fill in **Away address** with an HTTPS address that reaches your server from the internet. The
app uses the home address when it answers and switches to the away address when it doesn't
(and back), checking again whenever the phone changes networks. A queue started at home keeps
playing after you drive off.

Use HTTPS: the Subsonic API sends the login token with every request, and over plain HTTP anyone
on the same network could capture and reuse it.

One way to set it up, with [Caddy](https://caddyserver.com/) as the HTTPS front door on the
Navidrome computer:

1. Get a hostname that follows your home IP address (your router's dynamic DNS, DuckDNS, etc.).
2. Forward port 443 on your router to the Navidrome computer and allow it in its firewall.
3. Caddyfile (only the API goes through; Navidrome's web page stays private):

   ```
   music.example.com {
   	@api path /rest/*
   	handle @api {
   		reverse_proxy localhost:4533
   	}
   	handle {
   		respond 404
   	}
   }
   ```

   Caddy gets a Let's Encrypt certificate by itself, and the app needs nothing more.

### If Let's Encrypt can't validate your hostname

Some dynamic DNS services (TP-Link's `tplinkdns.com`, for one) answer IPv6 lookups with "no such
domain", and Let's Encrypt then refuses to issue a certificate. In that case:

1. Add `tls internal` inside the site block, so Caddy issues the certificate from its own
   authority (add `skip_install_trust` in the global options to keep it out of the computer's
   trusted certificates).
2. Copy Caddy's root certificate to the phone. It's `pki/authorities/local/root.crt` in Caddy's
   data folder (on Windows when Caddy runs as a service:
   `C:\Windows\System32\config\systemprofile\AppData\Roaming\Caddy`).
3. In VoiceTunes' settings tap **Certificate file…**, choose it, check the fingerprint and tap
   *Trust it*.

The certificate is trusted for the away address only, and a self-signed certificate works the
same way.

## Building

Java, Gradle and Media3 (ExoPlayer + MediaLibraryService). With JDK 17 or later:

```
gradlew assembleDebug
```

`tools/MatchTest.java` checks the voice matching on a PC against a tab-separated export of a
library and a list of phrases (see the comment at its top).
