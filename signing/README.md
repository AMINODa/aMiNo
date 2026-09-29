# signing/

Keystore backup so the aMiNo signature survives workspace resets:

- `debug.keystore.base64` — base64 of the signing keystore (PKCS12, alias `androiddebugkey`, password `android`).
- To restore: `base64 -d signing/debug.keystore.base64 > ~/.android/debug.keystore`
- `signing.properties` (NOT committed, gitignored) points to the keystore file for signing.gradle.

Note: r1380 is the first release signed with THIS keystore (the r1375-r1379 key was
lost with a wiped workspace — users must uninstall the old build and re-pair once).
