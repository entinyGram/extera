# exteraSDK

Python SDK sources for the entinyGram plugin engine. `sdk/` is what `SdkUpdater` unpacks over the baseline shipped in the APK.

Release (signing key stays outside the repo):

    python sdk_release.py <version> --key <path to sdk-signing.pem>

Then upload `out/sdk-<version>.zip` and `out/sdk.json` as assets of the public `sdk` release.
