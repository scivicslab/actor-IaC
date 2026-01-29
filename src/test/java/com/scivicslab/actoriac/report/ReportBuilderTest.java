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

package com.scivicslab.actoriac.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ReportBuilder and ReportSection components.
 */
class ReportBuilderTest {

    private ReportBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ReportBuilder();
    }

    // ========================================================================
    // ReportBuilder Basic Tests
    // ========================================================================

    @Nested
    @DisplayName("ReportBuilder 基本機能")
    class BasicTests {

        @Test
        @DisplayName("空のビルダーはヘッダーのみ出力")
        void build_empty_returnsHeaderOnly() {
            String report = builder.build();

            assertTrue(report.contains("=== Workflow Execution Report ==="));
        }

        @Test
        @DisplayName("セクションを追加して出力")
        void build_withSection_includesSection() {
            builder.addSection(new SimpleSection("Test Section", "Test content"));

            String report = builder.build();

            assertTrue(report.contains("--- Test Section ---"));
            assertTrue(report.contains("Test content"));
        }

        @Test
        @DisplayName("複数セクションは追加順に出力")
        void build_multipleSections_orderedByInsertion() {
            builder.addSection(new SimpleSection("First", "Content 1"));
            builder.addSection(new SimpleSection("Second", "Content 2"));
            builder.addSection(new SimpleSection("Third", "Content 3"));

            String report = builder.build();

            int firstIdx = report.indexOf("First");
            int secondIdx = report.indexOf("Second");
            int thirdIdx = report.indexOf("Third");

            assertTrue(firstIdx < secondIdx);
            assertTrue(secondIdx < thirdIdx);
        }

        @Test
        @DisplayName("タイトルがnullのセクションはタイトル行なし")
        void build_sectionWithNullTitle_noTitleLine() {
            builder.addSection(new SimpleSection(null, "Direct content"));

            String report = builder.build();

            assertTrue(report.contains("Direct content"));
            assertFalse(report.contains("--- null ---"));
        }

        @Test
        @DisplayName("空のコンテンツのセクションは出力されない")
        void build_sectionWithEmptyContent_skipped() {
            builder.addSection(new SimpleSection("Empty", ""));
            builder.addSection(new SimpleSection("HasContent", "Some content"));

            String report = builder.build();

            assertFalse(report.contains("--- Empty ---"));
            assertTrue(report.contains("--- HasContent ---"));
        }
    }

    // ========================================================================
    // WorkflowInfoSection Tests
    // ========================================================================

    @Nested
    @DisplayName("WorkflowInfoSection")
    class WorkflowInfoSectionTests {

        @Test
        @DisplayName("ワークフロー情報を出力")
        void getContent_returnsWorkflowInfo() {
            WorkflowInfoSection section = new WorkflowInfoSection(
                "/path/to/workflow.yaml",
                "my-workflow",
                "This is a test workflow."
            );

            String content = section.getContent();

            assertTrue(content.contains("[Workflow Info]"));
            assertTrue(content.contains("File: /path/to/workflow.yaml"));
            assertTrue(content.contains("Name: my-workflow"));
            assertTrue(content.contains("[Description]"));
            assertTrue(content.contains("This is a test workflow."));
        }

        @Test
        @DisplayName("descriptionがnullの場合は省略")
        void getContent_nullDescription_omitted() {
            WorkflowInfoSection section = new WorkflowInfoSection(
                "/path/to/workflow.yaml",
                "my-workflow",
                null
            );

            String content = section.getContent();

            assertTrue(content.contains("[Workflow Info]"));
            assertFalse(content.contains("[Description]"));
        }

        @Test
        @DisplayName("タイトルはnull（直接出力）")
        void getTitle_returnsNull() {
            WorkflowInfoSection section = new WorkflowInfoSection("path", "name", "desc");

            assertNull(section.getTitle());
        }

    }

    // ========================================================================
    // JsonStateSection Tests
    // ========================================================================

    @Nested
    @DisplayName("JsonStateSection")
    class JsonStateSectionTests {

        @Test
        @DisplayName("YAMLコンテンツを出力")
        void getContent_returnsYaml() {
            String yaml = "cluster:\n  name: prod\n  nodes: 2\n";
            JsonStateSection section = new JsonStateSection("node-localhost", yaml);

            String content = section.getContent();

            assertEquals(yaml, content);
        }

        @Test
        @DisplayName("タイトルにアクター名を含む")
        void getTitle_includesActorName() {
            JsonStateSection section = new JsonStateSection("node-localhost", "content");

            String title = section.getTitle();

            assertTrue(title.contains("node-localhost"));
        }

    }

    // ========================================================================
    // Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("統合テスト")
    class IntegrationTests {

        @Test
        @DisplayName("全セクションを組み合わせたレポート")
        void build_allSections_fullReport() {
            // Workflow Info
            builder.addSection(new WorkflowInfoSection(
                "kubectl/main-cluster-status.yaml",
                "main-cluster-status",
                "Collect Kubernetes cluster status."
            ));

            // JsonState
            String yaml = "cluster:\n  cluster: https://192.168.5.23:16443\n  total: 2\n";
            builder.addSection(new JsonStateSection("node-localhost", yaml));

            String report = builder.build();

            // ヘッダー
            assertTrue(report.contains("=== Workflow Execution Report ==="));

            // Workflow Info (order 100)
            assertTrue(report.contains("[Workflow Info]"));
            assertTrue(report.contains("main-cluster-status.yaml"));

            // JsonState (order 400)
            assertTrue(report.contains("node-localhost"));
            assertTrue(report.contains("cluster: https://192.168.5.23:16443"));

            // 順序確認
            int workflowInfoIdx = report.indexOf("[Workflow Info]");
            int jsonStateIdx = report.indexOf("node-localhost");

            assertTrue(workflowInfoIdx < jsonStateIdx);
        }
    }

    // ========================================================================
    // Helper Classes for Testing
    // ========================================================================

    /**
     * Simple ReportSection implementation for testing.
     */
    static class SimpleSection implements ReportSection {
        private final String title;
        private final String content;

        SimpleSection(String title, String content) {
            this.title = title;
            this.content = content;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public String getContent() {
            return content;
        }
    }
}
