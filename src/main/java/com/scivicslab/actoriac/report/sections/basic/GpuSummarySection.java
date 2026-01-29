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

package com.scivicslab.actoriac.report.sections.basic;

import com.scivicslab.actoriac.report.SectionBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POJO section builder that summarizes GPU information from logs.
 *
 * <p>Pure business logic - no {@code CallableByActionName}.
 * Use {@link GpuSummarySectionIIAR} to expose as an actor.</p>
 *
 * <p>Parses GPU information from workflow execution logs, supporting
 * both NVIDIA (nvidia-smi) and AMD (ROCm) GPU data formats.</p>
 *
 * <h2>Output example:</h2>
 * <pre>
 * [GPU Summary]
 * 192.168.5.13, gpu, NVIDIA GeForce RTX 4080
 * 192.168.5.13, vram, 16GB
 * 192.168.5.13, driver, 550.54.14
 * 192.168.5.13, toolkit, CUDA 12.4
 * 192.168.5.13, arch, 8.9
 * 192.168.5.14, gpu, NVIDIA GeForce RTX 4080
 * ...
 *
 * Summary: 2 NVIDIA, 1 AMD
 * </pre>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class GpuSummarySection implements SectionBuilder {

    private static final Logger logger = Logger.getLogger(GpuSummarySection.class.getName());

    private Connection connection;
    private long sessionId = -1;

    /**
     * Sets the database connection for log queries.
     *
     * @param connection the JDBC connection to the H2 log database
     */
    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    /**
     * Sets the session ID to query logs from.
     *
     * @param sessionId the session ID
     */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String generate() {
        if (connection == null || sessionId < 0) {
            logger.warning("GpuSummarySection: connection or sessionId not set");
            return "";
        }

        try {
            return buildGpuSummary();
        } catch (SQLException e) {
            logger.warning("GpuSummarySection: SQL error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Build GPU summary from logs.
     */
    private String buildGpuSummary() throws SQLException {
        String sql = "SELECT actor_name, message FROM logs " +
                     "WHERE session_id = ? AND actor_name IN (" +
                     "  SELECT DISTINCT actor_name FROM logs " +
                     "  WHERE session_id = ? AND message LIKE '%GPU INFO%'" +
                     ") AND (message LIKE '%GPU INFO%' OR message LIKE '%NVIDIA%' " +
                     "OR message LIKE '%GeForce%' OR message LIKE '%Quadro%' " +
                     "OR message LIKE '%CUDA_VERSION%' OR message LIKE '%Radeon%' " +
                     "OR message LIKE '%AMD_GPU%' OR message LIKE '%ROCM_VERSION%' " +
                     "OR message LIKE '%GFX_ARCH%' OR message LIKE '%GPU_NAME%' " +
                     "OR message LIKE '%AMD%' OR message LIKE '%VGA%') " +
                     "ORDER BY actor_name, timestamp";

        Map<String, GpuInfo> nodeGpus = new LinkedHashMap<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            ps.setLong(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nodeId = rs.getString("actor_name");
                    String message = rs.getString("message");
                    GpuInfo gpuInfo = nodeGpus.computeIfAbsent(nodeId, k -> new GpuInfo());

                    parseGpuMessage(message, gpuInfo);
                }
            }
        }

        if (nodeGpus.isEmpty()) {
            return "";  // No GPU info, skip this section
        }

        return formatOutput(nodeGpus);
    }

    /**
     * Parse GPU information from a log message.
     */
    private void parseGpuMessage(String message, GpuInfo gpuInfo) {
        for (String line : message.split("\n")) {
            String cleanLine = line.replaceFirst("^\\[node-[^\\]]+\\]\\s*", "").trim();
            if (cleanLine.contains("GPU INFO") || cleanLine.isEmpty()) continue;

            // Parse NVIDIA CUDA_VERSION line
            if (cleanLine.startsWith("CUDA_VERSION:")) {
                gpuInfo.toolkit = "CUDA " + cleanLine.replaceFirst("CUDA_VERSION:\\s*", "").trim();
                continue;
            }

            // Parse AMD ROCm output
            if (cleanLine.startsWith("AMD_GPU:")) {
                gpuInfo.isAmd = true;
                continue;
            }
            if (cleanLine.startsWith("GPU_NAME:")) {
                gpuInfo.name = cleanLine.replaceFirst("GPU_NAME:\\s*", "").trim();
                continue;
            }
            if (cleanLine.startsWith("VRAM_BYTES:")) {
                try {
                    long vramBytes = Long.parseLong(cleanLine.replaceFirst("VRAM_BYTES:\\s*", "").trim());
                    long vramGB = vramBytes / (1024L * 1024L * 1024L);
                    gpuInfo.vram = vramGB + "GB";
                } catch (NumberFormatException e) {
                    // ignore
                }
                continue;
            }
            if (cleanLine.startsWith("DRIVER_VERSION:")) {
                gpuInfo.driver = cleanLine.replaceFirst("DRIVER_VERSION:\\s*", "").trim();
                continue;
            }
            if (cleanLine.startsWith("ROCM_VERSION:")) {
                gpuInfo.toolkit = "ROCm " + cleanLine.replaceFirst("ROCM_VERSION:\\s*", "").trim();
                continue;
            }
            if (cleanLine.startsWith("GFX_ARCH:")) {
                gpuInfo.arch = cleanLine.replaceFirst("GFX_ARCH:\\s*", "").trim();
                continue;
            }

            // Parse nvidia-smi CSV output
            Pattern nvidiaCsvPattern = Pattern.compile(
                "^(NVIDIA [^,]+|[^,]*GeForce[^,]*|[^,]*Quadro[^,]*|[^,]*Tesla[^,]*|[^,]*A100[^,]*|[^,]*H100[^,]*|[^,]*GB[0-9]+[^,]*),\\s*(?:(\\d+)\\s*MiB|\\[N/A\\]),\\s*([\\d.]+),\\s*([\\d.]+)$"
            );
            Matcher nvidiaMatcher = nvidiaCsvPattern.matcher(cleanLine);
            if (nvidiaMatcher.find()) {
                gpuInfo.name = nvidiaMatcher.group(1).trim();
                String vramStr = nvidiaMatcher.group(2);
                if (vramStr != null) {
                    int vramMB = Integer.parseInt(vramStr);
                    gpuInfo.vram = (vramMB >= 1024) ? (vramMB / 1024) + "GB" : vramMB + "MB";
                }
                gpuInfo.driver = nvidiaMatcher.group(3).trim();
                gpuInfo.arch = nvidiaMatcher.group(4).trim();
                continue;
            }

            // Parse lspci output for AMD/Intel GPUs (fallback)
            Pattern lspciPattern = Pattern.compile(
                "(?:VGA compatible controller|3D controller|Display controller):\\s*(.+?)(?:\\s*\\(rev|$)");
            Matcher lspciMatcher = lspciPattern.matcher(cleanLine);
            if (lspciMatcher.find()) {
                String gpuName = lspciMatcher.group(1).trim();
                if (gpuInfo.name == null) {
                    gpuInfo.name = gpuName;
                }
            }
        }
    }

    /**
     * Format the output string.
     */
    private String formatOutput(Map<String, GpuInfo> nodeGpus) {
        // Count GPU types
        int nvidiaCount = 0, amdCount = 0, otherCount = 0;
        for (GpuInfo gpu : nodeGpus.values()) {
            if (gpu.name != null) {
                if (gpu.name.contains("NVIDIA") || gpu.name.contains("GeForce") ||
                    gpu.name.contains("Quadro") || gpu.name.contains("Tesla")) {
                    nvidiaCount++;
                } else if (gpu.isAmd || gpu.name.contains("AMD") || gpu.name.contains("Radeon")) {
                    amdCount++;
                } else {
                    otherCount++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[GPU Summary]\n");
        for (Map.Entry<String, GpuInfo> entry : nodeGpus.entrySet()) {
            String nodeShort = entry.getKey().replaceFirst("^node-", "");
            GpuInfo gpu = entry.getValue();
            if (gpu.name != null) {
                sb.append(String.format("%s, gpu, %s%n", nodeShort, gpu.name));
            }
            if (gpu.vram != null) {
                sb.append(String.format("%s, vram, %s%n", nodeShort, gpu.vram));
            }
            if (gpu.driver != null) {
                sb.append(String.format("%s, driver, %s%n", nodeShort, gpu.driver));
            }
            if (gpu.toolkit != null) {
                sb.append(String.format("%s, toolkit, %s%n", nodeShort, gpu.toolkit));
            }
            if (gpu.arch != null) {
                sb.append(String.format("%s, arch, %s%n", nodeShort, gpu.arch));
            }
        }

        sb.append("\nSummary: ");
        boolean first = true;
        if (nvidiaCount > 0) {
            sb.append(nvidiaCount).append(" NVIDIA");
            first = false;
        }
        if (amdCount > 0) {
            if (!first) sb.append(", ");
            sb.append(amdCount).append(" AMD");
            first = false;
        }
        if (otherCount > 0) {
            if (!first) sb.append(", ");
            sb.append(otherCount).append(" Other");
        }
        sb.append("\n");

        return sb.toString();
    }

    @Override
    public String getTitle() {
        return null;  // Title is embedded in content
    }

    @Override
    public int getOrder() {
        return 600;  // After Transition History section
    }

    /**
     * GPU information holder.
     */
    private static class GpuInfo {
        String name;
        String vram;
        String driver;
        String toolkit;  // CUDA x.x or ROCm x.x
        String arch;     // compute cap (NVIDIA) or gfx ID (AMD)
        boolean isAmd;
    }
}
