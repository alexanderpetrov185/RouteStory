package com.alexander.routestory.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexander.routestory.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackDraftStoreTest {
    @Test fun survivesStoreRecreationAndClears() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        // Instrumentation runs under the target UID, not the test APK UID.
        // Isolate files from the user's actual recording draft.
        val testDir = java.io.File(target.cacheDir, "draft-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(target) {
            override fun getFilesDir(): java.io.File = testDir
        }
        val store = TrackDraftStore(context)
        val draft = TrackDraft("test", "Поездка", 1000, 42000, 123f,
            TrackingState.PAUSED,
            listOf(TrackPoint(55.0, 37.0, 20.0, 2f, 5f, 1000)),
            listOf(TrackPhoto(trackId = 0, uri = "/photos/test.jpg", timestamp = 1000,
                latitude = 55.0, longitude = 37.0)), System.currentTimeMillis() + 100)
        try {
            store.save(draft)
            val recreated = TrackDraftStore(context)
            assertEquals(draft, recreated.load())
            recreated.clear()
            assertNull(TrackDraftStore(context).load())
        } finally { store.clear() }
    }
}
