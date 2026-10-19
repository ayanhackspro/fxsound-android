# Licensing and Attribution Checklist

## FxSound
Upstream: https://github.com/fxsound2/fxsound-app
The repository declares AGPL-3.0. Preserve copyright notices and licence headers in files derived from upstream.

Before distributing:
- Record the exact upstream commit.
- Record all modified upstream files and local patches.
- Include the full applicable AGPL licence text.
- Provide the Corresponding Source as required by the applicable licence terms.
- Review obligations for distributing binaries and any network interaction that may trigger AGPL provisions.
- Do not imply official endorsement by FxSound.
- Do not use FxSound logos/trademarks as if the app were official.
- Name the product independently unless trademark review says otherwise.

## Dependencies
Create a `THIRD_PARTY_NOTICES.md` listing:
- Dependency name and version/commit.
- Source URL.
- Licence.
- Local modifications.
- Required notices.
- Whether it is included in binary distribution.

Review JUCE and any transitive DSP dependencies independently; do not assume every dependency has identical terms just because the upstream project is AGPL.

## Android and Google libraries
Record the licence and version for Oboe, AndroidX, Compose, Kotlin and other included libraries as applicable.

## Release gate
Do not publish a binary until a qualified reviewer has checked the applicable licence obligations and the source distribution mechanism.
