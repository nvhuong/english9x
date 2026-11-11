/**
 * Public surface of the AI grading (P4.2): {@link com.english9x.grading.ai.api.AiGrader} is the one way a Gemini answer is asked for. The grading job
 * and the CMS dry-run of a grading skill (P2.5) both use it; only the job persists anything.
 */
@org.springframework.modulith.NamedInterface("ai")
package com.english9x.grading.ai.api;
