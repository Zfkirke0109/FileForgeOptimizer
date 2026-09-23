# FileForge native-arm64 notices

The standard APK contains none of these native executables. The native-arm64 APK builds every executable from the pinned source revision in `native-tools.lock.json`; no downloaded executable is packaged.

| Component | Version/ref | License | Upstream |
|---|---|---|---|
| qpdf | 12.4.0 | Apache-2.0 | https://github.com/qpdf/qpdf |
| oxipng | 10.2.0 | MIT | https://github.com/oxipng/oxipng |
| jpegtran (libjpeg-turbo) | 3.1.4.1 | IJG and BSD-3-Clause | https://github.com/libjpeg-turbo/libjpeg-turbo |
| Zopfli / zopflipng | 1.0.3 | Apache-2.0 | https://github.com/google/zopfli |
| AOSP zipalign | platform-tools-31.0.0 | Apache-2.0 | https://android.googlesource.com/platform/build |
| AOSP libutils headers/sources | platform-tools-31.0.0 | Apache-2.0 | https://android.googlesource.com/platform/system/core |
| AOSP libziparchive API headers | platform-tools-31.0.0 | Apache-2.0 | https://android.googlesource.com/platform/system/libziparchive |

qpdf’s upstream `NOTICE.md` and every upstream license file are copied from the verified source checkout into the native APK’s `licenses/` assets by the build. Android system libraries listed in the lock file are dynamically provided by the operating system and are not bundled.

The small `native/zipalign-shim` adapter is original FileForge glue and is not copied from those upstream components. It implements the pinned libziparchive streaming-inflate interface with Android’s system zlib so the pinned AOSP zipalign sources can be built as an on-device PIE without importing opaque host binaries.
