package com.taptaptips.server.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import jakarta.annotation.PostConstruct
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Initialize Firebase Admin SDK for sending push notifications.
 * 
 * Supports two modes:
 * 1. Environment variable (Render production): FIREBASE_CREDENTIALS_BASE64
 * 2. Local file (development): firebase-adminsdk.json in src/main/resources/
 */
@Configuration
class FirebaseConfig {
    private val logger = LoggerFactory.getLogger(FirebaseConfig::class.java)
    
    @PostConstruct
    fun initialize() {
        try {
            // Check if already initialized
            if (FirebaseApp.getApps().isEmpty()) {
                val credentials = loadCredentials()
                
                val options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build()
                
                FirebaseApp.initializeApp(options)
                logger.info("✅ Firebase Admin SDK initialized successfully")
            } else {
                logger.info("✅ Firebase Admin SDK already initialized")
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to initialize Firebase Admin SDK", e)
            logger.error("⚠️ FCM push notifications will NOT work!")
            logger.error("📝 Set FIREBASE_CREDENTIALS_BASE64 env var or add firebase-adminsdk.json to resources/")
        }
    }
    
    /**
     * Load Firebase credentials from environment variable or local file.
     * Priority: Environment variable > Local file
     */
    private fun loadCredentials(): GoogleCredentials {
        // Try environment variable first (for Render deployment)
        val base64Credentials = System.getenv("FIREBASE_CREDENTIALS_BASE64")
        
        if (!base64Credentials.isNullOrBlank()) {
            logger.info("🔐 Loading Firebase credentials from environment variable")
            
            // Decode base64 to get JSON bytes
            val decodedBytes = Base64.getDecoder().decode(base64Credentials.trim())
            val credentialsStream = ByteArrayInputStream(decodedBytes)
            
            return GoogleCredentials.fromStream(credentialsStream)
        }
        
        // Fallback to local file (for local development)
        logger.info("📁 Loading Firebase credentials from local file")
        val serviceAccount = ClassPathResource("firebase-adminsdk.json").inputStream
        
        return GoogleCredentials.fromStream(serviceAccount)
    }
}