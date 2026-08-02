# Security policy

## Supported versions

Only the latest `main` revision is supported for security fixes while the
project is in early development. Releases will document their support window.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use the repository's
GitHub Security Advisory workflow when enabled, or contact the maintainers
through the private contact listed in the repository profile. Include a
minimal reproduction, affected commit/version, Android version, device class,
and the impact. Do not include personal photos, model weights, credentials,
database files, or private logs.

We will acknowledge a report when received, assess whether it affects the
source, build tooling, model-pack validation, privacy boundary, or sample data,
and coordinate disclosure with the reporter.

## Scope

Important reports include data leakage, unsafe model-pack activation, bypass of
sensitive-evidence authentication, unintended network transmission, arbitrary
tool execution, exported debug components, and destructive gallery cleanup.
