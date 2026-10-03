package com.example.arruler.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A point in plan coordinates, meters (x east, y north). */
@Serializable
data class PlanPoint(val x: Float, val y: Float)

/** One captured room: outline in plan meters plus the numbers measured for it. */
@Serializable
data class SavedRoom(
    val id: String,
    val name: String,
    /** Outline vertices in order, meters on the floor plane (2D plan coordinates). */
    val outline: List<PlanPoint>,
    val heightM: Float? = null,
    val areaM2: Float,
    val perimeterM: Float,
    val volumeM3: Float? = null,
    /** Epoch milliseconds. */
    val capturedAt: Long,
)

/** A named set of rooms sharing one plan coordinate system. Times are epoch milliseconds. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rooms: List<SavedRoom> = emptyList(),
    val schema: Int = 1,
) {
    /** Sum of the room areas in m2. */
    val totalAreaM2: Float get() = rooms.sumOf { it.areaM2.toDouble() }.toFloat()
}

/** JSON (de)serialisation of a [Project]; unknown keys are ignored so newer files still load. */
object ProjectCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decode(text: String): Project = json.decodeFromString(Project.serializer(), text)
}
