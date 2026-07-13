# GrokifyOS agent notes

## Android releases (always)

After any Android app change the user can install:

1. Bump `versionCode` / `versionName` in `android/app/build.gradle.kts`.
2. Commit the change(s) with a message that includes the version (e.g. `… (v76)`).
3. Push to `origin` (`git push origin HEAD`).
4. Publish the OTA APK so the in-app updater can pick it up:

```bash
cd android && ./scripts/publish.sh debug --changelog "short notes"
```

Do this by default for shippable Android work — do not wait to be asked again.
