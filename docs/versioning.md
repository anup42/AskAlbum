# Application versioning

The current Android application version is `0.0.2` with Android `versionCode` 2.

The project uses semantic versions while it is pre-1.0:

- Increment the patch component for a meaningful compatible improvement, bug fix, security/privacy fix, model/runtime integration, device-compatibility change, or performance improvement. Example: `0.0.1` to `0.0.2`.
- Increment the minor component and reset the patch component when a substantial user-facing capability or implementation phase becomes usable. Example: `0.0.2` to `0.1.0`.
- Reserve `1.0.0` for the first release that satisfies the documented production acceptance criteria.
- Increment Android `versionCode` for every newly distributed APK, regardless of which semantic-version component changes. Rebuilding identical source does not require a new version.

Documentation-only, comment-only, formatting, and test-fixture changes do not require a version increment unless they accompany a newly distributed APK.
