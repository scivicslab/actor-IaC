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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for encrypted secret configuration files.
 *
 * <p>This class reads an encrypted INI-format file containing secrets
 * (SSH keys, passphrases, sudo passwords), decrypts it, and provides
 * access to the secrets with host/group/global priority.</p>
 *
 * <h2>File Format (before encryption)</h2>
 * <pre>
 * [secrets:all]
 * ssh_key=-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNz...
 * ssh_passphrase=MyPassphrase123
 * sudo_password=MySudoPassword
 *
 * [secrets:webservers]
 * sudo_password=WebServerSudoPassword
 *
 * [secrets:host:web1.example.com]
 * ssh_key=...different key...
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * InputStream encryptedInput = new FileInputStream("secrets.enc");
 * String key = System.getenv("ACTOR_IAC_SECRET_KEY");
 * EncryptedSecretConfig config = EncryptedSecretConfig.parse(encryptedInput, key);
 *
 * Map<String, String> secrets = config.getSecretsForHost("web1.example.com", "webservers");
 * String sshKey = secrets.get("ssh_key");
 * String passphrase = secrets.get("ssh_passphrase");
 * }</pre>
 *
 * @author devteam@scivicslab.com
 */
public class EncryptedSecretConfig {

    private final Map<String, String> globalSecrets = new HashMap<>();
    private final Map<String, Map<String, String>> groupSecrets = new HashMap<>();
    private final Map<String, Map<String, String>> hostSecrets = new HashMap<>();

    /**
     * Parses an encrypted secret configuration file.
     *
     * @param encryptedInput InputStream of the encrypted file
     * @param encryptionKey Base64-encoded encryption key
     * @return parsed EncryptedSecretConfig
     * @throws IOException if reading or decryption fails
     */
    public static EncryptedSecretConfig parse(InputStream encryptedInput, String encryptionKey) throws IOException {
        try {
            // Read encrypted content
            StringBuilder encrypted = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(encryptedInput))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    encrypted.append(line);
                }
            }

            // Decrypt
            String decrypted = SecretEncryptor.decrypt(encrypted.toString(), encryptionKey);

            // Parse decrypted content
            return parseDecrypted(decrypted);

        } catch (SecretEncryptor.EncryptionException e) {
            throw new IOException("Failed to decrypt secret configuration", e);
        }
    }

    /**
     * Parses decrypted INI-format content.
     *
     * @param content decrypted INI content
     * @return parsed EncryptedSecretConfig
     * @throws IOException if parsing fails
     */
    private static EncryptedSecretConfig parseDecrypted(String content) throws IOException {
        EncryptedSecretConfig config = new EncryptedSecretConfig();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            String currentSection = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Section header
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    continue;
                }

                // Key-value pair
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0 && currentSection != null) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = line.substring(equalsIndex + 1).trim();

                    // Unescape newlines in multi-line values (e.g., SSH keys)
                    value = value.replace("\\n", "\n");

                    if (currentSection.equals("secrets:all")) {
                        config.globalSecrets.put(key, value);
                    } else if (currentSection.startsWith("secrets:host:")) {
                        String hostname = currentSection.substring("secrets:host:".length());
                        config.hostSecrets.computeIfAbsent(hostname, k -> new HashMap<>()).put(key, value);
                    } else if (currentSection.startsWith("secrets:")) {
                        String groupName = currentSection.substring("secrets:".length());
                        config.groupSecrets.computeIfAbsent(groupName, k -> new HashMap<>()).put(key, value);
                    }
                }
            }
        }

        return config;
    }

    /**
     * Gets secrets for a specific host, applying priority rules.
     * Priority: host-specific > group-specific > global
     *
     * @param hostname Hostname
     * @param groupNames Group names this host belongs to
     * @return Map of secrets for this host
     */
    public Map<String, String> getSecretsForHost(String hostname, String... groupNames) {
        Map<String, String> result = new HashMap<>(globalSecrets);

        // Apply group secrets (later groups override earlier ones)
        for (String groupName : groupNames) {
            Map<String, String> groupSecretMap = groupSecrets.get(groupName);
            if (groupSecretMap != null) {
                result.putAll(groupSecretMap);
            }
        }

        // Apply host-specific secrets (highest priority)
        Map<String, String> hostSecretMap = hostSecrets.get(hostname);
        if (hostSecretMap != null) {
            result.putAll(hostSecretMap);
        }

        return result;
    }

    /**
     * Gets global secrets.
     *
     * @return Map of global secrets
     */
    public Map<String, String> getGlobalSecrets() {
        return new HashMap<>(globalSecrets);
    }
}
