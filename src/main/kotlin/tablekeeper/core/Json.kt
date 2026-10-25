package tablekeeper.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** The app's one Jackson mapper: module.json, props JSONB and form conditions. */
object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()
}
