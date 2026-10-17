package com.anvex.core.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Safe numeric access shared by all JVM-only provider adapters. */
internal val JsonElement?.longOrNullCompat: Long?
    get() = (this as? JsonPrimitive)?.longOrNull
