/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.actoriac;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utility for encrypting and decrypting secrets using AES-256-GCM.
 *
 * <p>This class provides authenticated encryption with AES-256 in GCM mode,
 * which provides both confidentiality and integrity protection.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Generate a new encryption key
 * String key = SecretEncryptor.generateKey();
 * System.out.println("ACTOR_IAC_SECRET_KEY=" + key);
 *
 * // Encrypt a file
 * String plaintext = Files.readString(Path.of("secrets.ini"));
 * String encrypted = SecretEncryptor.encrypt(plaintext, key);
 * Files.writeString(Path.of("secrets.enc"), encrypted);
 *
 * // Decrypt a file
 * String encryptedContent = Files.readString(Path.of("secrets.enc"));
 * String decrypted = SecretEncryptor.decrypt(encryptedContent, key);
 * }</pre>
 *
 * @author devteam@scivicslab.com
 */
public class SecretEncryptor {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // 128 bits

    /**
     * Generates a new random encryption key.
     *
     * @return Base64-encoded encryption key
     * @throws EncryptionException if key generation fails
     */
    public static String generateKey() throws EncryptionException {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(ALGORITHM);
            keyGenerator.init(KEY_SIZE, new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            return Base64.getEncoder().encodeToString(secretKey.getEncoded());
        } catch (Exception e) {
            throw new EncryptionException("Failed to generate encryption key", e);
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     *
     * @param plaintext the text to encrypt
     * @param base64Key Base64-encoded encryption key
     * @return Base64-encoded encrypted data (IV + ciphertext + tag)
     * @throws EncryptionException if encryption fails
     */
    public static String encrypt(String plaintext, String base64Key) throws EncryptionException {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            SecretKey secretKey = new SecretKeySpec(keyBytes, ALGORITHM);

            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // Encrypt
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            // Combine IV + ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            // Return Base64-encoded result
            return Base64.getEncoder().encodeToString(byteBuffer.array());

        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts encrypted data using AES-256-GCM.
     *
     * @param encryptedBase64 Base64-encoded encrypted data (IV + ciphertext + tag)
     * @param base64Key Base64-encoded encryption key
     * @return decrypted plaintext
     * @throws EncryptionException if decryption fails
     */
    public static String decrypt(String encryptedBase64, String base64Key) throws EncryptionException {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            SecretKey secretKey = new SecretKeySpec(keyBytes, ALGORITHM);

            // Decode encrypted data
            byte[] encryptedData = Base64.getDecoder().decode(encryptedBase64);

            // Extract IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            // Decrypt
            byte[] plaintextBytes = cipher.doFinal(ciphertext);
            return new String(plaintextBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt data", e);
        }
    }

    /**
     * Exception thrown when encryption/decryption operations fail.
     */
    public static class EncryptionException extends Exception {
        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
