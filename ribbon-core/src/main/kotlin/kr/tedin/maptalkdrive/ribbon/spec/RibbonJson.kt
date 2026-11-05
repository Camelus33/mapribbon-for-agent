package kr.tedin.maptalkdrive.ribbon.spec

import kotlinx.serialization.json.Json

/** The wire format. Defaults are encoded so every message carries its `spec` version. */
val RibbonJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}
