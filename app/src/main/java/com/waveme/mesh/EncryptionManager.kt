package com.waveme.mesh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class EncryptionManager {

    private val KEY_ALIAS = "WaveMeshKey"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private val AES_KEY_SIZE = 256
    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128

    init {
        createKeyIfNotExists()
    }

    private fun createKeyIfNotExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE
            )
            keyPairGenerator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .build()
            )
            keyPairGenerator.generateKeyPair()
        }
    }

    fun getPublicKeyStr(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        val publicKey = entry?.certificate?.publicKey ?: return ""
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    private fun getPrivateKey(): PrivateKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        return entry?.privateKey
    }

    private fun getPublicKeyFromString(keyStr: String): PublicKey? {
        return try {
            val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
            keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Encrypts plain text using Hybrid Encryption (AES content key + RSA key wrap).
     * Format: base64(encryptedAesKey) + ":" + base64(iv) + ":" + base64(encryptedContent)
     */
    suspend fun encrypt(plainText: String, recipientPublicKeyStr: String): String? {
        return withContext(Dispatchers.Default) {
            try {
                // 1. Generate a random AES key
                val aesKey = generateAesKey()
                
                // 2. Encrypt the content with AES
                val iv = ByteArray(GCM_IV_LENGTH)
                SecureRandom().nextBytes(iv)
                val cipherAes = Cipher.getInstance(AES_TRANSFORMATION)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipherAes.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec)
                val encryptedContent = cipherAes.doFinal(plainText.toByteArray(Charsets.UTF_8))

                // 3. Wrap (encrypt) the AES key with the recipient's RSA public key
                val publicKey = getPublicKeyFromString(recipientPublicKeyStr) ?: return@withContext null
                val cipherRsa = Cipher.getInstance(RSA_TRANSFORMATION)
                val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
                cipherRsa.init(Cipher.WRAP_MODE, publicKey, oaepSpec)
                val wrappedKey = cipherRsa.wrap(aesKey)

                // 4. Combine parts: wrappedKey:iv:encryptedContent
                val wrappedKeyBase64 = Base64.encodeToString(wrappedKey, Base64.NO_WRAP)
                val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
                val contentBase64 = Base64.encodeToString(encryptedContent, Base64.NO_WRAP)

                "$wrappedKeyBase64:$ivBase64:$contentBase64"
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Decrypts hybrid encrypted text.
     */
    suspend fun decrypt(cipherText: String): String? {
        return withContext(Dispatchers.Default) {
            try {
                val parts = cipherText.split(":")
                if (parts.size != 3) return@withContext null

                val wrappedKey = Base64.decode(parts[0], Base64.NO_WRAP)
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val encryptedContent = Base64.decode(parts[2], Base64.NO_WRAP)

                // 1. Unwrap the AES key using our RSA private key
                val privateKey = getPrivateKey() ?: return@withContext null
                val cipherRsa = Cipher.getInstance(RSA_TRANSFORMATION)
                val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
                cipherRsa.init(Cipher.UNWRAP_MODE, privateKey, oaepSpec)
                val aesKey = cipherRsa.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY) as SecretKey

                // 2. Decrypt the content using the unwrapped AES key
                val cipherAes = Cipher.getInstance(AES_TRANSFORMATION)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipherAes.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec)
                val decryptedBytes = cipherAes.doFinal(encryptedContent)

                String(decryptedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Encrypts a file using Hybrid Encryption.
     * Returns wrappedKey:iv which must be sent to the recipient.
     */
    suspend fun encryptFile(sourceFile: File, destFile: File, recipientPublicKeyStr: String): String? {
        return withContext(Dispatchers.Default) {
            try {
                val aesKey = generateAesKey()

                // Encrypt the AES key with RSA
                val publicKey = getPublicKeyFromString(recipientPublicKeyStr) ?: return@withContext null
                val cipherRsa = Cipher.getInstance(RSA_TRANSFORMATION)
                val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
                cipherRsa.init(Cipher.WRAP_MODE, publicKey, oaepSpec)
                val wrappedKey = cipherRsa.wrap(aesKey)

                // Encrypt file content with AES
                val iv = ByteArray(GCM_IV_LENGTH)
                SecureRandom().nextBytes(iv)
                val cipherAes = Cipher.getInstance(AES_TRANSFORMATION)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipherAes.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec)

                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            val encrypted = cipherAes.update(buffer, 0, bytesRead)
                            if (encrypted != null) output.write(encrypted)
                        }
                        val final = cipherAes.doFinal()
                        if (final != null) output.write(final)
                    }
                }

                val wrappedKeyBase64 = Base64.encodeToString(wrappedKey, Base64.NO_WRAP)
                val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
                "$wrappedKeyBase64:$ivBase64"
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Decrypts a file using Hybrid Encryption.
     * encryptionInfo should be wrappedKey:iv
     */
    suspend fun decryptFile(sourceFile: File, destFile: File, encryptionInfo: String): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val parts = encryptionInfo.split(":")
                if (parts.size != 2) return@withContext false

                val wrappedKey = Base64.decode(parts[0], Base64.NO_WRAP)
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)

                // Unwrap the AES key
                val privateKey = getPrivateKey() ?: return@withContext false
                val cipherRsa = Cipher.getInstance(RSA_TRANSFORMATION)
                val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
                cipherRsa.init(Cipher.UNWRAP_MODE, privateKey, oaepSpec)
                val aesKey = cipherRsa.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY) as SecretKey

                // Decrypt the file content
                val cipherAes = Cipher.getInstance(AES_TRANSFORMATION)
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipherAes.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec)

                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            val decrypted = cipherAes.update(buffer, 0, bytesRead)
                            if (decrypted != null) output.write(decrypted)
                        }
                        val final = cipherAes.doFinal()
                        if (final != null) output.write(final)
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun generateAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE)
        return keyGen.generateKey()
    }
}
