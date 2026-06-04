# Advanced Architecture & Feature Roadmap

This document outlines the roadmap for evolving "Inquery" into a proactive, context-aware data analysis agent. It covers advanced interaction flows, intelligent query generation, multi-step orchestration, and reliability mechanisms.

## 1. User Interaction & Flow (UX)

### 1.1 Pre-Query Clarification (Parameter Confirmation)
**Goal:** Prevent incorrect queries by validating intent before execution.
*   **Flow:**
    1.  User asks: "Sales for last month."
    2.  LLM generates SQL but *pauses* execution.
    3.  Agent asks: "I prepared a query for **'Seoul Region'** (default) for **'October 2024'**. Is this correct?"
    4.  User: "No, Gyeonggi-do."
    5.  Agent updates parameters -> Executes SQL.

### 1.2 Proactive Clarification (Ambiguity Resolution)
**Goal:** Resolve ambiguity early through active questioning.
*   **Flow:**
    1.  User asks: "Show me active users."
    2.  Agent detects ambiguity in "active".
    3.  Agent presents UI Options: "How should we define 'Active User'?"
        *   [ ] Logged in within 7 days
        *   [ ] Purchased within 30 days
    4.  User selects option -> Agent generates SQL.

### 1.3 Post-Analysis Follow-up (Continuous Discovery)
**Goal:** Encourage deeper exploration.
*   **Flow:**
    1.  Agent presents analysis results.
    2.  Agent suggests **Follow-up Questions**:
        *   "Why did sales drop in the 3rd week?"
        *   "Compare this result by region?"

### 1.4 User Feedback Loop
**Goal:** Continuously improve agent performance.
*   **Mechanism:**
    *   Simple feedback UI (:thumbsup: / :thumbsdown:) after every result.
    *   **Negative Feedback:** Triggers an internal "Analysis Agent" to diagnose the failure and log it as a negative example for future penalty.

---

## 2. Intelligent Query Engine

### 2.1 Compressive RAG (Metadata Summarization)
**Goal:** Handle large schemas efficiently by reducing context noise.
*   **Logic:**
    *   Instead of feeding full DDL, a specialized LLM node selects and summarizes only relevant tables/columns.
    *   *Example:* For a "Sales" query, extract only `sales_data` table and `amount`, `date` columns, ignoring 50+ other columns.

### 2.2 Successful Query Logging (Few-Shot Learning)
**Goal:** Leverage past successes to improve future accuracy.
*   **Logic:**
    *   Store successfully executed and user-validated queries in a Vector DB.
    *   **Retrieval:** When a new question arrives, retrieve similar past successful SQLs as "Few-Shot Examples" for the prompt.

### 2.3 Chain-of-Table / Plan-and-Solve
**Goal:** Improve complex query logic by planning first.
*   **Flow:**
    1.  **Step 1 (Plan):** LLM generates a natural language plan (e.g., "Get users from Table A, filter by age, then join with Table B").
    2.  **Step 2 (Generate):** Generate SQL based on the plan.

### 2.4 JSON Output & Constrained Decoding
**Goal:** Maximize speed and reliability.
*   **Technique:**
    *   Force LLM output to JSON format (e.g., `{"sql": "...", "reasoning": "..."}`).
    *   Use **Constrained Decoding** to limit token generation to valid JSON syntax.
    *   **Benefit:** Removes "chatter", reduces token usage, and ensures 100% parsable output.

---

## 3. Advanced Analysis Orchestration

### 3.1 Multi-Step Orchestrator-Worker Pattern
**Goal:** Handle complex, multi-domain questions.
*   **Scenario:** "Analyze last year's sales trend and compare with competitor market share."
*   **Architecture:**
    *   **Orchestrator:** Breaks down the request.
        1.  Internal Data: "Get sales trend" (SQL Worker)
        2.  External Data: "Search competitor market share" (Search Worker)
        3.  Synthesis: "Combine and report" (Reporter Worker)
    *   **Workers:** Execute tasks in parallel.

### 3.2 Time-Series Overlay (Contextual Analysis)
**Goal:** Explain "Why" by correlating data with events.
*   **Logic:**
    1.  **Detect Anomaly:** SQL shows sales drop on Nov 11.
    2.  **Context Search:** Agent searches Jira/Slack for "Nov 11" + keywords ("error", "deploy", "outage").
    3.  **Synthesis:** "Sales dropped 30% on Nov 11. Coincides with Jira Ticket #404 (Payment Server Error)."

### 3.3 Ownership Routing
**Goal:** Connect users with human experts when AI fails.
*   **Logic:**
    *   If confidence is low or analysis fails, look up "Data Owner" in metadata.
    *   **Action:** "I can't fully explain this. Shall I connect you with **Kim Cheol-soo**, who deployed the payment logic recently?"

---

## 4. Reliability & Verification

### 4.1 Test-Driven Analytics (TDA)
**Goal:** Self-verification of generated queries.
*   **Flow:**
    1.  **Main Agent:** Generates Analysis Query (Query A).
    2.  **Tester Agent:** Generates Validation Query (Query B) (e.g., "Check for Cartesian products", "Verify row counts").
    3.  **Execution:** Run both. If Query B fails (e.g., impossible values), Agent self-corrects before showing results.

### 4.2 Adversarial Reviewer (Devil's Advocate)
**Goal:** Prevent hallucination and confirmation bias.
*   **Flow:**
    1.  **Analyst Agent:** Generates insight.
    2.  **Reviewer Agent (Persona: Critical Senior):** "This logic is flawed. Did you check for seasonality?"
    3.  **Refinement:** Analyst Agent revises the report based on critique.

---

## 5. Proactive Intelligence

### 5.1 Hypothesis-Driven Approach
**Goal:** Mimic human analytical reasoning.
*   **Loop:**
    1.  **Hypothesis:** "Maybe sales dropped due to marketing end?"
    2.  **Action:** Check marketing schedule.
    3.  **Observation:** "Marketing is ongoing. Hypothesis rejected."
    4.  **Next Hypothesis:** "Check system errors."

### 5.2 Proactive Insight (Shadow Mode)
**Goal:** Alert users before they ask.
*   **Logic:**
    *   Background agent monitors key metrics.
    *   If a significant pattern emerges (e.g., "Metrics normal, but CS channels are exploding"), proactively notify the stakeholder.
