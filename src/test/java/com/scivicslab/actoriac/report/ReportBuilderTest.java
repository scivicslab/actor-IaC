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
            builder.addSection(new SimpleSection("Test Section", "Test content", 100));

            String report = builder.build();

            assertTrue(report.contains("--- Test Section ---"));
            assertTrue(report.contains("Test content"));
        }

        @Test
        @DisplayName("複数セクションは順序通りに出力")
        void build_multipleSections_orderedByOrder() {
            builder.addSection(new SimpleSection("Second", "Content 2", 200));
            builder.addSection(new SimpleSection("First", "Content 1", 100));
            builder.addSection(new SimpleSection("Third", "Content 3", 300));

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
            builder.addSection(new SimpleSection(null, "Direct content", 100));

            String report = builder.build();

            assertTrue(report.contains("Direct content"));
            assertFalse(report.contains("--- null ---"));
        }

        @Test
        @DisplayName("空のコンテンツのセクションは出力されない")
        void build_sectionWithEmptyContent_skipped() {
            builder.addSection(new SimpleSection("Empty", "", 100));
            builder.addSection(new SimpleSection("HasContent", "Some content", 200));

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

        @Test
        @DisplayName("orderは100（先頭）")
        void getOrder_returns100() {
            WorkflowInfoSection section = new WorkflowInfoSection("path", "name", "desc");

            assertEquals(100, section.getOrder());
        }
    }

    // ========================================================================
    // TransitionSection Tests
    // ========================================================================

    @Nested
    @DisplayName("TransitionSection")
    class TransitionSectionTests {

        @Test
        @DisplayName("遷移サマリーを出力")
        void getContent_returnsTransitionSummary() {
            TransitionSection section = new TransitionSection();
            section.addTransition("0 -> 1", true, null);
            section.addTransition("1 -> 2", true, null);
            section.addTransition("2 -> 3", false, "action failed");

            String content = section.getContent();

            assertTrue(content.contains("[✓] 0 -> 1"));
            assertTrue(content.contains("[✓] 1 -> 2"));
            assertTrue(content.contains("[✗] 2 -> 3"));
            assertTrue(content.contains("action failed"));
            assertTrue(content.contains("Summary: 2 succeeded, 1 failed"));
        }

        @Test
        @DisplayName("タイトルはTransitions")
        void getTitle_returnsTransitions() {
            TransitionSection section = new TransitionSection();

            assertEquals("Transitions", section.getTitle());
        }

        @Test
        @DisplayName("orderは300")
        void getOrder_returns300() {
            TransitionSection section = new TransitionSection();

            assertEquals(300, section.getOrder());
        }

        @Test
        @DisplayName("遷移がない場合は空")
        void getContent_noTransitions_empty() {
            TransitionSection section = new TransitionSection();

            String content = section.getContent();

            assertTrue(content.isEmpty() || content.isBlank());
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

        @Test
        @DisplayName("orderは400")
        void getOrder_returns400() {
            JsonStateSection section = new JsonStateSection("actor", "content");

            assertEquals(400, section.getOrder());
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

            // Transitions
            TransitionSection transitions = new TransitionSection();
            transitions.addTransition("0 -> 1", true, null);
            transitions.addTransition("1 -> 2", true, null);
            builder.addSection(transitions);

            // JsonState
            String yaml = "cluster:\n  cluster: https://192.168.5.23:16443\n  total: 2\n";
            builder.addSection(new JsonStateSection("node-localhost", yaml));

            String report = builder.build();

            // ヘッダー
            assertTrue(report.contains("=== Workflow Execution Report ==="));

            // Workflow Info (order 100)
            assertTrue(report.contains("[Workflow Info]"));
            assertTrue(report.contains("main-cluster-status.yaml"));

            // Transitions (order 300)
            assertTrue(report.contains("--- Transitions ---"));
            assertTrue(report.contains("[✓] 0 -> 1"));

            // JsonState (order 400)
            assertTrue(report.contains("node-localhost"));
            assertTrue(report.contains("cluster: https://192.168.5.23:16443"));

            // 順序確認
            int workflowInfoIdx = report.indexOf("[Workflow Info]");
            int transitionsIdx = report.indexOf("--- Transitions ---");
            int jsonStateIdx = report.indexOf("node-localhost");

            assertTrue(workflowInfoIdx < transitionsIdx);
            assertTrue(transitionsIdx < jsonStateIdx);
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
        private final int order;

        SimpleSection(String title, String content, int order) {
            this.title = title;
            this.content = content;
            this.order = order;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public String getContent() {
            return content;
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
