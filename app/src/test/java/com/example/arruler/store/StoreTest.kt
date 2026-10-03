package com.example.arruler.store

import com.example.arruler.geometry.Polygon2
import com.example.arruler.geometry.Vec2
import com.example.arruler.geometry.Vec3
import com.example.arruler.measure.Units
import com.example.arruler.plan.PlanLayout
import com.example.arruler.plan.PlanTransform
import com.example.arruler.plan.FloorPlan
import com.example.arruler.plan.Room
import com.example.arruler.plan.hitRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.math.abs

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1000L
    private var seq = 0
    private fun repo(root: File = File(tmp.root, "projects"), writer: FileWriter? = null): ProjectRepository =
        if (writer == null) ProjectRepository(root, { clock++ }, { "id${seq++}" })
        else ProjectRepository(root, { clock++ }, { "id${seq++}" }, writer)

    private fun room(id: String, name: String = "R") = SavedRoom(
        id, name, listOf(PlanPoint(0f, 0f), PlanPoint(4f, 0f), PlanPoint(4f, 3f), PlanPoint(0f, 3f)),
        heightM = 2.5f, areaM2 = 12f, perimeterM = 14f, volumeM3 = 30f, capturedAt = 5L,
    )

    @Test
    fun roundTripThroughFiles() {
        val r = repo()
        val p = r.createProject("My place")
        r.addRoom(p.id, room("a", "Kitchen"))
        r.addRoom(p.id, room("b", "Hall").copy(heightM = null, volumeM3 = null))

        val reloaded = repo()
        val q = reloaded.project(p.id)!!
        assertEquals(r.project(p.id), q)
        assertEquals(listOf("Kitchen", "Hall"), q.rooms.map { it.name })
        assertEquals(30f, q.rooms[0].volumeM3!!, 0f)
        assertNull(q.rooms[1].heightM)
        assertEquals(12f, q.totalAreaM2 / 2f, 1e-6f)
        assertEquals(File(tmp.root, "projects/${p.id}.json").readText().contains("Kitchen"), true)
    }

    @Test
    fun decodeIgnoresUnknownKeys() {
        val p = ProjectCodec.decode("""{"id":"x","name":"n","createdAt":1,"updatedAt":2,"future":true}""")
        assertEquals("x", p.id)
        assertTrue(p.rooms.isEmpty())
    }

    @Test
    fun renameAndRoomEdits() {
        val r = repo()
        val p = r.createProject("A")
        r.renameProject(p.id, "B")
        r.addRoom(p.id, room("a", "One"))
        r.renameRoom(p.id, "a", "Two")
        assertEquals("B", r.project(p.id)!!.name)
        assertEquals("Two", r.project(p.id)!!.rooms.single().name)
        r.deleteRoom(p.id, "a")
        assertTrue(r.project(p.id)!!.rooms.isEmpty())
        assertFalse(r.addRoom("missing", room("z")))
    }

    @Test
    fun deleteRemovesFileAndState() {
        val r = repo()
        val p = r.createProject("A")
        val f = File(tmp.root, "projects/${p.id}.json")
        assertTrue(f.exists())
        r.deleteProject(p.id)
        assertFalse(f.exists())
        assertTrue(r.projects.value.isEmpty())
        assertTrue(repo().projects.value.isEmpty())
    }

    @Test
    fun failedWriteLeavesOldFileAndNoTempFile() {
        val ok = repo()
        val p = ok.createProject("A")
        val f = File(tmp.root, "projects/${p.id}.json")
        val before = f.readText()

        val failing = repo(writer = { t, b ->
            t.writeBytes(b.copyOf(b.size / 2)) // partial content in the temp file
            throw IOException("disk full")
        })
        try {
            failing.renameProject(p.id, "Changed")
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(before, f.readText())
        assertEquals("A", failing.project(p.id)!!.name) // state unchanged too
        assertEquals(listOf(f.name), File(tmp.root, "projects").list()!!.toList())
    }

    @Test
    fun staleTempFilesAreRemovedOnLoad() {
        val dir = File(tmp.root, "projects").apply { mkdirs() }
        File(dir, "dead.json.tmp").writeText("{ partial")
        File(dir, "bad.json").writeText("not json")
        val r = repo(dir)
        assertTrue(r.projects.value.isEmpty())
        assertFalse(File(dir, "dead.json.tmp").exists())
    }

    // ---- room capture ----

    /** A 5 x 3 m floor rectangle on world X/Z at y = 0, turned 30 degrees about Y. */
    private fun worldRect(offX: Float = 0f, offZ: Float = 0f, w: Float = 5f, d: Float = 3f, deg: Double = 30.0): List<Vec3> {
        val c = Math.cos(Math.toRadians(deg)).toFloat(); val s = Math.sin(Math.toRadians(deg)).toFloat()
        return listOf(0f to 0f, w to 0f, w to d, 0f to d).map { (x, z) ->
            Vec3(offX + x * c - z * s, 0f, offZ + x * s + z * c)
        }
    }

    @Test
    fun longestWallBecomesHorizontalAndAreaIsKept() {
        val cap = RoomCapture.capture(worldRect(), null)
        val pts = cap.raw.points
        assertEquals(15f, cap.raw.area(), 1e-3f)
        // first edge is the 5 m wall: horizontal in plan
        assertEquals(pts[0].y, pts[1].y, 1e-3f)
        assertEquals(5f, abs(pts[1].x - pts[0].x), 1e-3f)
        assertEquals(15f, cap.snapped.area(), 1e-2f)
    }

    @Test
    fun sharedFrameKeepsRelativePositions() {
        val first = RoomCapture.capture(worldRect(), null)
        val second = RoomCapture.capture(worldRect(offX = 7f, offZ = 2f), first.frame)
        // same shape translated by the world offset (7, 2): distance between the two rooms is kept
        val d = first.raw.points[0].distanceTo(second.raw.points[0])
        assertEquals(Math.hypot(7.0, 2.0).toFloat(), d, 1e-3f)
        // and the second room is NOT re-rotated: its first wall is not horizontal in general
        assertEquals(first.raw.points[1].y - first.raw.points[0].y, second.raw.points[1].y - second.raw.points[0].y, 1e-3f)
    }

    @Test
    fun savedRoomNumbersFollowTheChosenOutline() {
        val cap = RoomCapture.capture(worldRect(), null)
        val r = cap.toSavedRoom("id", "Den", snap = true, heightM = 2.4f, capturedAt = 9L)
        assertEquals(cap.snapped.area(), r.areaM2, 1e-4f)
        assertEquals(r.areaM2 * 2.4f, r.volumeM3!!, 1e-3f)
        assertEquals(4, r.outline.size)
        assertNull(cap.toSavedRoom("id", "Den", false, null, 9L).volumeM3)
    }

    // ---- plan helpers touched by this feature ----

    @Test
    fun imperialScaleBarUsesFeet() {
        val t = PlanTransform(100f, Vec2(0f, 0f), Vec2(0f, 0f)) // 100 px per m
        val ft = PlanLayout.niceScaleBar(t, 300f, Units.FT)
        assertEquals("5 ft", ft.label) // 5 ft = 152 px, 10 ft = 305 px > 300
        assertEquals(5f * 0.3048f, ft.meters, 1e-4f)
        assertEquals("2 m", PlanLayout.niceScaleBar(t, 300f, Units.M).label)
        assertEquals("50 cm", PlanLayout.niceScaleBar(PlanTransform(40f, Vec2(0f, 0f), Vec2(0f, 0f)), 30f, Units.CM).label)
        assertEquals("6 in", PlanLayout.niceScaleBar(PlanTransform(40f, Vec2(0f, 0f), Vec2(0f, 0f)), 10f, Units.INCH).label)
    }

    @Test
    fun hitRoomPicksSmallestContainingRoom() {
        fun sq(x: Float, y: Float, s: Float) =
            Polygon2(listOf(Vec2(x, y), Vec2(x + s, y), Vec2(x + s, y + s), Vec2(x, y + s)))
        val plan = FloorPlan(listOf(Room("big", sq(0f, 0f, 10f)), Room("small", sq(2f, 2f, 2f))))
        assertEquals(1, plan.hitRoom(Vec2(3f, 3f)))
        assertEquals(0, plan.hitRoom(Vec2(8f, 8f)))
        assertNull(plan.hitRoom(Vec2(20f, 20f)))
        assertNotNull(plan.hitRoom(Vec2(1f, 1f)))
    }
}
