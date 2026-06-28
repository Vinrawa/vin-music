package com.vinmusic.recommendation

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify

class FirestoreRecommendationManagerTest {

    private lateinit var mockedLog: org.mockito.MockedStatic<android.util.Log>

    @org.junit.Before
    fun setUp() {
        mockedLog = org.mockito.Mockito.mockStatic(android.util.Log::class.java)
    }

    @org.junit.After
    fun tearDown() {
        mockedLog.close()
    }

    @Test
    fun `normalizeTags maps raw tags to standard genres and moods`() {
        val manager = FirestoreRecommendationManager()
        val rawTags = listOf("lo-fi", "chill out", "sad song", "hip-hop", "Bollywood")
        val result = manager.normalizeTags(rawTags)

        val genres = result["genres"] ?: emptyList()
        val moods = result["moods"] ?: emptyList()

        assertTrue(genres.contains("Lofi"))
        assertTrue(genres.contains("Rap/Hip-Hop"))
        assertTrue(genres.contains("Bollywood"))
        assertTrue(moods.contains("Chill/Relaxed"))
        assertTrue(moods.contains("Sad"))
    }

    @Test
    fun `normalizeTags limits output to maximum of 10 tags`() {
        val manager = FirestoreRecommendationManager()
        val rawTags = listOf(
            "lofi", "rap", "bollywood", "punjabi", "pop", "indie", "rock",
            "chill", "romantic", "sad", "energetic", "happy", "dark"
        )
        val result = manager.normalizeTags(rawTags)
        val genres = result["genres"] ?: emptyList()
        val moods = result["moods"] ?: emptyList()

        assertTrue(genres.size <= 10)
        assertTrue(moods.size <= 10)
    }

    @Test
    fun `completeAnalysis writes correct map to Firestore and merges`() {
        val manager = FirestoreRecommendationManager()
        
        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockCollection = mock(CollectionReference::class.java)
        val mockDocument = mock(DocumentReference::class.java)
        
        `when`(mockFirestore.collection("songs")).thenReturn(mockCollection)
        `when`(mockCollection.document("testKey")).thenReturn(mockDocument)
        
        val captor = ArgumentCaptor.forClass(Map::class.java)
        val mockTask = com.google.android.gms.tasks.Tasks.forResult<Void>(null)
        
        `when`(mockDocument.set(any(Map::class.java), any(com.google.firebase.firestore.SetOptions::class.java)))
            .thenReturn(mockTask)
            
        manager.firestore = mockFirestore
        
        kotlinx.coroutines.runBlocking {
            manager.completeAnalysis(
                songKey = "testKey",
                bpm = 120f,
                energy = 0.8f,
                genreTags = listOf("Lofi"),
                moodTags = listOf("Chill/Relaxed"),
                title = "Test Song",
                artist = "Test Artist"
            )
        }
        
        verify(mockDocument).set(captor.capture(), any(com.google.firebase.firestore.SetOptions::class.java))
        
        val capturedMap = captor.value
        assertEquals("ready", capturedMap["status"])
        assertEquals(120f, capturedMap["bpmReal"])
        assertEquals(0.8f, capturedMap["energyReal"])
        assertEquals(listOf("Lofi"), capturedMap["genreTags"])
        assertEquals(listOf("Chill/Relaxed"), capturedMap["moodTags"])
        assertEquals("Test Song", capturedMap["title"])
        assertEquals("Test Artist", capturedMap["artist"])
    }

    @Test
    fun `completeAnalysis omits bpmReal if bpm is null`() {
        val manager = FirestoreRecommendationManager()
        
        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockCollection = mock(CollectionReference::class.java)
        val mockDocument = mock(DocumentReference::class.java)
        
        `when`(mockFirestore.collection("songs")).thenReturn(mockCollection)
        `when`(mockCollection.document("testKey")).thenReturn(mockDocument)
        
        val captor = ArgumentCaptor.forClass(Map::class.java)
        val mockTask = com.google.android.gms.tasks.Tasks.forResult<Void>(null)
        
        `when`(mockDocument.set(any(Map::class.java), any(com.google.firebase.firestore.SetOptions::class.java)))
            .thenReturn(mockTask)
            
        manager.firestore = mockFirestore
        
        kotlinx.coroutines.runBlocking {
            manager.completeAnalysis(
                songKey = "testKey",
                bpm = null,
                energy = 0.8f,
                genreTags = listOf("Lofi"),
                moodTags = listOf("Chill/Relaxed"),
                title = "Test Song",
                artist = "Test Artist"
            )
        }
        
        verify(mockDocument).set(captor.capture(), any(com.google.firebase.firestore.SetOptions::class.java))
        
        val capturedMap = captor.value
        assertEquals("ready", capturedMap["status"])
        assertFalse(capturedMap.containsKey("bpmReal"))
        assertEquals(0.8f, capturedMap["energyReal"])
    }

    @Test
    fun `completeAnalysis omits bpmReal if bpm is out of range`() {
        val manager = FirestoreRecommendationManager()
        
        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockCollection = mock(CollectionReference::class.java)
        val mockDocument = mock(DocumentReference::class.java)
        
        `when`(mockFirestore.collection("songs")).thenReturn(mockCollection)
        `when`(mockCollection.document("testKey")).thenReturn(mockDocument)
        
        val captor = ArgumentCaptor.forClass(Map::class.java)
        val mockTask = com.google.android.gms.tasks.Tasks.forResult<Void>(null)
        
        `when`(mockDocument.set(any(Map::class.java), any(com.google.firebase.firestore.SetOptions::class.java)))
            .thenReturn(mockTask)
            
        manager.firestore = mockFirestore
        
        kotlinx.coroutines.runBlocking {
            manager.completeAnalysis(
                songKey = "testKey",
                bpm = 35f, // Out of range
                energy = 0.8f,
                genreTags = listOf("Lofi"),
                moodTags = listOf("Chill/Relaxed"),
                title = "Test Song",
                artist = "Test Artist"
            )
        }
        
        verify(mockDocument).set(captor.capture(), any(com.google.firebase.firestore.SetOptions::class.java))
        
        val capturedMap = captor.value
        assertFalse(capturedMap.containsKey("bpmReal"))
    }
}
