package app.tsukaima.data.api

import kotlinx.serialization.json.Json

val HermesJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
