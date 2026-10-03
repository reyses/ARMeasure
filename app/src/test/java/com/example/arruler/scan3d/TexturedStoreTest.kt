package com.example.arruler.scan3d

import com.example.arruler.objscan.TexturedObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TexturedStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun atlasObjectRoundTripsWithItsColourPly() {
        val root = tmp.newFolder("scans")
        File(root, "a").mkdirs()
        val t = TexturedObject("mtllib mesh.mtl\nv 0 0 0\n", "newmtl m\nmap_Kd texture.png\n", byteArrayOf(1, 2, 3), null, byteArrayOf(4, 5))
        ScanFiles.saveTextured(root, "a", t)
        val back = ScanFiles.loadTextured(root, "a")!!
        assertTrue(back.hasAtlas)
        assertEquals(t.obj, back.obj)
        assertArrayEquals(t.png, back.png)
        assertArrayEquals(byteArrayOf(4, 5), back.colourPly)
    }

    @Test fun vertexColourOnlyObjectIsStoredAndLoadedWithoutAnAtlas() {
        val root = tmp.newFolder("scans2")
        File(root, "b").mkdirs()
        ScanFiles.saveTextured(root, "b", TexturedObject("", "", ByteArray(0), null, byteArrayOf(7, 8, 9)))
        val d = File(ScanFiles.dir(root, "b"), ScanFiles.TEXTURED_DIR)
        assertFalse(File(d, "mesh.obj").exists())
        assertTrue(File(d, ScanFiles.COLOUR_PLY).isFile)
        val back = ScanFiles.loadTextured(root, "b")!!
        assertFalse(back.hasAtlas)
        assertArrayEquals(byteArrayOf(7, 8, 9), back.colourPly)
    }

    @Test fun anObjectWithoutTextureFilesLoadsAsNull() {
        val root = tmp.newFolder("scans3")
        File(root, "c").mkdirs()
        assertNull(ScanFiles.loadTextured(root, "c"))
        assertNotNull(TexturedObject("o", "m", byteArrayOf(1)))
    }
}
