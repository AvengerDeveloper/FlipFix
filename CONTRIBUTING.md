# Contributing to FlipFix

Thanks for helping. One person runs FlipFix in their spare time, so replies can take a few days.

You don't need to write code to help:

* **Report a bug**: [open an issue](https://github.com/AvengerDeveloper/FlipFix/issues/new/choose) using the **Bug report** form.
* **Tell us how it runs on your phone**, especially a Galaxy Z Flip5 or Z Flip7, which haven't been tested. Use the **Phone report** form, even when everything works.
* **Suggest an idea** with the **Idea** form. Check first that it fits [what FlipFix is for](#what-fits-flipfix).

> **Issues are public.** Before you attach a screenshot, screen recording, log or the output of a `tools/` script, check it for anything personal: notification previews, names, photos, Wi-Fi or Bluetooth device names. Crop or blur it, or leave it out.

**Found a security problem**, such as a way for another app to misuse FlipFix's permissions? Don't open a public issue. Use **Report a vulnerability** on the repository's **Security** tab, so it can be fixed before it's public.

## What fits FlipFix

FlipFix does one job: keep the cover video playing after unlock, with Samsung's clock, icons and music player on top. A change fits if it keeps these promises:

* **Offline.** No `INTERNET` permission, no analytics, no crash reporting, nothing that leaves the phone.
* **No new permissions** unless we've agreed on it in an issue first. Each one needs a line in the README's permissions table.
* **No reading the screen.** The accessibility service keeps `canRetrieveWindowContent` off, and snapshots stay in memory.
* **No Samsung files in the repository.** Fonts, images and templates are loaded from the phone at runtime.
* **No new libraries.** The app is plain Kotlin and Android's own Views. Ask in an issue before adding a dependency.

For anything bigger than a small fix, open an issue first so we can agree on the approach before you spend time on it.

## Making a change: fork, branch, pull request

New to GitHub? This is the usual way to send a change to a project you don't own. You copy the project (a **fork**), make your change on a **branch** in your copy, then ask for it to be pulled into the original (a **pull request**, or PR).

1. **Fork.** Click **Fork** at the top of [the repository page](https://github.com/AvengerDeveloper/FlipFix). You get your own copy at `github.com/YOUR-NAME/FlipFix`.
2. **Clone your fork** to your computer, and remember where the original lives:
   ```bash
   git clone https://github.com/YOUR-NAME/FlipFix.git
   cd FlipFix
   git remote add upstream https://github.com/AvengerDeveloper/FlipFix.git
   ```
3. **Make a branch** for this one change. Don't work on `main`:
   ```bash
   git switch -c fix/clock-on-flip7
   ```
4. **Build it and try it on your phone.** You need Android Studio or JDK 21.
   ```bash
   ./gradlew :app:installDebug
   ```
   Your build is signed with your own debug key, so uninstall a release copy of FlipFix first, then run the two `adb` commands from [the README's Step 3](README.md#step-3-grant-the-two-one-time-permissions) again. To work on the setup page instead: `cd site`, `npm ci`, `npm run build`, copy an APK to `site/dist/FlipFix.apk`, `npm run serve`, then open http://localhost:8642 in Chrome or Edge.
5. **Commit** in small steps. Say what changes and why, in plain words:
   ```bash
   git add -A
   git commit -m "Keep the clock when the date shows only the month"
   ```
6. **Push** the branch to your fork:
   ```bash
   git push -u origin fix/clock-on-flip7
   ```
7. **Open the pull request.** GitHub shows a **Compare & pull request** button on your fork. Check that it goes into `AvengerDeveloper/FlipFix`, branch `main`, then fill in the form. Link the issue it fixes, for example `Fixes #12`.
8. **Wait for the build.** GitHub builds a debug APK for every pull request, so others can try your change: open the pull request's **Checks** tab, then the **Build** run, and download it under **Artifacts**.
9. **Answer the review.** If changes are asked for, commit and push them to the same branch. The pull request updates itself, so there's no need to open a new one.
10. **Catch up with `main`** if it moved on while you worked:
    ```bash
    git fetch upstream
    git rebase upstream/main
    git push --force-with-lease
    ```

## Testing

FlipFix has no automated tests. It depends on how Samsung's cover screen behaves, so changes are tested by hand on a phone. In your pull request, say:

* which phone and One UI version you used (**Settings → About phone → Software information**);
* which cover clock style or preset;
* what you did and what you saw: lock, unlock, swipe to the widgets and back, open an app on the cover, unfold.

No phone to test on? Say so in the pull request. Someone else can try the APK the build makes.

## License

FlipFix is under the [MIT License](LICENSE). By sending a pull request you agree that your contribution is licensed under it too.

Be kind and patient with each other. Everyone here is a volunteer.
