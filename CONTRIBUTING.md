# Contributing

Thanks for helping. Bug reports, site fixes and pull requests are all welcome.

## Reporting a bug

Open an issue with:
- the app version (Settings, or the APK name) and your Android version,
- which AI or website was involved,
- what you did, what you expected and what happened,
- for an AI page problem, the web log: on the writing page tap ⋮ › Web log, then copy it. Remove anything private (your prompts, names) before posting.

## A site stopped working

AI sites change their pages often. Most fixes are one of these:
- **Taught site:** AI accounts › the site › Teach, then teach the step that broke again.
- **Built-in site:** update its selectors in `app/src/main/assets/ai_runner.js` (the `SITES` table near the top). Please say in the pull request which page layout you tested against and the date.

## Development

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

- Kotlin, Jetpack Compose, Material 3, Room. JDK 17 or newer.
- Keep logic that can be tested (text handling, prompts, parsing) out of Compose code, and add a unit test in `app/src/test/`.
- Database changes: bump the version in `AppDatabase.kt`, add an `AutoMigration` (or a manual migration) and commit the new schema JSON in `app/schemas/`.
- UI follows the tokens in `ui/theme/Theme.kt` and the shared pieces in `ui/common/` (`SectionHead`, `Sheet`, `MetaText`, the button wrappers). Labels in sentence case, no colours or fonts outside the theme.
- The AI must always reply in the story's language. Keep that rule in any new prompt in `ai/Prompts.kt`.

## Licence of contributions

By contributing you agree that your contribution is licensed under the GNU General Public License v3.0, the same as the project.
