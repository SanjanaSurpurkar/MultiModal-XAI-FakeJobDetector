package com.example.random_major.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JobPostValidator Tests")
class JobPostValidatorTest {

    // ──────────────────────────────────────────────────────────────────
    // VALID JOB POST TESTS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should validate legitimate job post with all requirements")
    void testValidJobPost() {
        String validJobText = """
            Senior Software Engineer Position

            We are hiring a Senior Software Engineer to join our dynamic team.

            Job Responsibilities:
            - Design and develop scalable software solutions
            - Collaborate with cross-functional teams
            - Participate in code reviews and mentoring

            Requirements:
            - 5+ years of experience in Java development
            - Strong knowledge of Spring Boot framework
            - Experience with microservices architecture

            Skills: Java, Spring, Microservices, AWS

            Salary: Competitive package with benefits

            Apply now at: https://company.com/careers
            """;

        assertTrue(JobPostValidator.isValidJobPost(validJobText),
                "Valid job post should pass validation");
    }

    @Test
    @DisplayName("Should validate minimal valid job post")
    void testMinimalValidJobPost() {
        String minimalJobText = "We are hiring a software engineer position. This job requires experience in programming. The candidate should have skills in Java development. Responsibilities include coding and testing. Requirements: 2 years experience. Apply now for this great opportunity.";

        assertTrue(JobPostValidator.isValidJobPost(minimalJobText),
                "Minimal valid job post should pass validation");
    }

    // ──────────────────────────────────────────────────────────────────
    // INVALID JOB POST TESTS - LENGTH REQUIREMENTS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should reject null input")
    void testNullInput() {
        assertFalse(JobPostValidator.isValidJobPost(null),
                "Null input should be rejected");
    }

    @Test
    @DisplayName("Should reject empty string")
    void testEmptyString() {
        assertFalse(JobPostValidator.isValidJobPost(""),
                "Empty string should be rejected");
    }

    @Test
    @DisplayName("Should reject whitespace-only string")
    void testWhitespaceOnly() {
        assertFalse(JobPostValidator.isValidJobPost("   \n\t   "),
                "Whitespace-only string should be rejected");
    }

    @Test
    @DisplayName("Should reject text shorter than minimum length")
    void testTooShortText() {
        String shortText = "This is a job."; // 14 characters, < 100

        assertFalse(JobPostValidator.isValidJobPost(shortText),
                "Text shorter than 100 characters should be rejected");
    }

    // ──────────────────────────────────────────────────────────────────
    // INVALID JOB POST TESTS - WORD COUNT REQUIREMENTS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should reject text with insufficient word count")
    void testInsufficientWordCount() {
        String lowWordCountText = "We hiring engineer. Need experience. Skills required. Apply here.";
        // This is 10 words, < 30 minimum

        assertFalse(JobPostValidator.isValidJobPost(lowWordCountText),
                "Text with fewer than 30 words should be rejected");
    }

    // ──────────────────────────────────────────────────────────────────
    // INVALID JOB POST TESTS - KEYWORD REQUIREMENTS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should reject text without job-related keywords")
    void testNoJobKeywords() {
        String noKeywordsText = """
            This is a random text about technology and programming.
            We discuss various topics including software development,
            artificial intelligence, machine learning, and cloud computing.
            The article covers different aspects of modern technology trends.
            """;

        assertFalse(JobPostValidator.isValidJobPost(noKeywordsText),
                "Text without job-related keywords should be rejected");
    }

    @Test
    @DisplayName("Should validate text with minimum keyword requirement")
    void testMinimumKeywordRequirement() {
        String textWithKeyword = """
            We have an opening for a developer position that requires programming skills and experience in software development.
            The role involves working with various technologies including Java, Python, and web frameworks.
            Candidates should have at least two years of professional experience in software engineering.
            This is not a random text but an actual job posting with specific requirements and qualifications.
            The position offers competitive salary and benefits for the right candidate who meets our criteria.
            """;

        assertTrue(JobPostValidator.isValidJobPost(textWithKeyword),
                "Text with at least one job keyword should pass validation");
    }

    // ──────────────────────────────────────────────────────────────────
    // INVALID JOB POST TESTS - GIBBERISH DETECTION
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should reject gibberish with too many special characters")
    void testTooManySpecialCharacters() {
        String gibberishText = "!!!@@@###$$$%%%^^^&&&***(((!!!)))???:::;;;{{{}}}";
        // This has way more than 40% special characters

        assertFalse(JobPostValidator.isValidJobPost(gibberishText),
                "Text with >40% special characters should be rejected");
    }

    @Test
    @DisplayName("Should reject repeated character patterns")
    void testRepeatedCharacters() {
        String repeatedText = "We are hiring a developer. aaaaaaa bbbbbbb ccccccc ddddddd.";

        assertFalse(JobPostValidator.isValidJobPost(repeatedText),
                "Text with repeated character patterns should be rejected");
    }

    @Test
    @DisplayName("Should handle edge case with exactly 40% special characters")
    void testExactlyFortyPercentSpecialChars() {
        // Create text with exactly 40% special characters and include keywords and enough words
        String baseText = "We are hiring a developer for this position. The job requires skills and experience in programming. Responsibilities include coding, testing, and deployment. Qualifications needed for this role. Apply now for this great opportunity.";
        StringBuilder text = new StringBuilder(baseText);
        // Add letters to reach sufficient length for 60 non-special chars
        while (text.length() < 60) {
            text.append("a");
        }
        // Add special chars to reach exactly 40% (40 special chars for 100 total)
        while (text.length() < 100) {
            text.append("!");
        }

        // This should pass since it's exactly 40%, not >40%
        assertTrue(JobPostValidator.isValidJobPost(text.toString()),
                "Text with exactly 40% special characters should pass");
    }

    // ──────────────────────────────────────────────────────────────────
    // EDGE CASES AND BOUNDARY TESTS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should handle text with exactly minimum word count")
    void testExactlyMinimumWordCount() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 29; i++) {
            text.append("word").append(i).append(" ");
        }
        text.append("hiring"); // 30th word, and it's a keyword

        assertTrue(JobPostValidator.isValidJobPost(text.toString()),
                "Text with exactly 30 words should pass word count check");
    }

    @Test
    @DisplayName("Should be case-insensitive for keywords")
    void testCaseInsensitiveKeywords() {
        String mixedCaseText = """
            We are HIRING a developer for this POSITION. The ROLE requires SKILLS and EXPERIENCE in programming.
            Candidates should have qualifications in software development and be able to handle responsibilities.
            This job opportunity offers competitive salary and benefits for the right person.
            The position involves working on various projects and collaborating with the team.
            Apply now if you meet the requirements and have the necessary experience for this role.
            """;

        assertTrue(JobPostValidator.isValidJobPost(mixedCaseText),
                "Keywords should be detected case-insensitively");
    }
}