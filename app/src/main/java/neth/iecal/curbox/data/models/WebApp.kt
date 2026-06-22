package neth.iecal.curbox.data.models

import java.util.UUID

data class WebApp(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String
)
