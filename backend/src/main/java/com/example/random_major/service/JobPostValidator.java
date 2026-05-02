package com.example.random_major.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validator for job post inputs to ensure they are legitimate job postings
 * before sending to ML model for prediction.
 *
 * This prevents invalid inputs like random text, gibberish, or non-job content
 * from reaching the model and wasting computational resources.
 */
public class JobPostValidator {

    private static final Logger log = LoggerFactory.getLogger(JobPostValidator.class);

    // Constants for validation rules
    private static final int MIN_TEXT_LENGTH = 100;
    private static final int MIN_WORD_COUNT = 30;
    private static final double MAX_SPECIAL_CHAR_RATIO = 0.40;
    private static final int MIN_REPEATED_CHAR_THRESHOLD = 5;

    // Job-related keywords that indicate legitimate job postings
    private static final Set<String> JOB_KEYWORDS = new HashSet<>(Arrays.asList(
        "job", "hiring", "role", "position", "apply",
        "salary", "experience", "responsibilities",
        "requirements", "qualification", "skills",
        "employment", "vacancy", "opening", "opportunity",
        "candidate", "recruitment", "work", "career",
        "benefits", "compensation", "description", "duties"
    ));

    // Pattern to detect repeated characters (e.g., "aaaaaa", "!!!!!!")
    private static final Pattern REPEATED_CHARS_PATTERN = Pattern.compile("(.+)\\1{4,}");

    /**
     * Validates whether the input text represents a legitimate job posting.
     *
     * @param text The job post text to validate
     * @return true if the text passes all validation rules, false otherwise
     */
    public static boolean isValidJobPost(String text) {
        log.debug("Validating job post text (length: {})", text != null ? text.length() : 0);

        // Handle null or empty input
        if (text == null || text.trim().isEmpty()) {
            log.warn("Job post validation failed: null or empty text");
            return false;
        }

        String trimmedText = text.trim();

        // Rule 1: Minimum text length
        if (trimmedText.length() < MIN_TEXT_LENGTH) {
            log.warn("Job post validation failed: text too short ({} chars, minimum {})",
                    trimmedText.length(), MIN_TEXT_LENGTH);
            return false;
        }

        // Rule 2: Minimum word count
        int wordCount = countWords(trimmedText);
        if (wordCount < MIN_WORD_COUNT) {
            log.warn("Job post validation failed: insufficient word count ({} words, minimum {})",
                    wordCount, MIN_WORD_COUNT);
            return false;
        }

        // Rule 3: Must contain at least one job-related keyword
        if (!containsJobKeyword(trimmedText)) {
            log.warn("Job post validation failed: no job-related keywords found");
            return false;
        }

        // Rule 4: Reject gibberish - too many special characters
        double specialCharRatio = calculateSpecialCharacterRatio(trimmedText);
        if (specialCharRatio > MAX_SPECIAL_CHAR_RATIO) {
            log.warn("Job post validation failed: too many special characters ({:.2f}%, maximum {:.2f}%)",
                    specialCharRatio * 100, MAX_SPECIAL_CHAR_RATIO * 100);
            return false;
        }

        // Rule 5: Reject repeated character patterns
        if (hasRepeatedCharacters(trimmedText)) {
            log.warn("Job post validation failed: contains repeated character patterns");
            return false;
        }

        log.debug("Job post validation passed: {} chars, {} words, special chars: {:.2f}%",
                trimmedText.length(), wordCount, specialCharRatio * 100);
        return true;
    }

    /**
     * Counts the number of words in the text.
     * Words are separated by whitespace.
     */
    private static int countWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Split by whitespace and filter out empty strings
        return (int) Arrays.stream(text.split("\\s+"))
                .filter(word -> !word.isEmpty())
                .count();
    }

    /**
     * Checks if the text contains at least one job-related keyword.
     */
    private static boolean containsJobKeyword(String text) {
        String lowerText = text.toLowerCase();
        return JOB_KEYWORDS.stream()
                .anyMatch(keyword -> lowerText.contains(keyword.toLowerCase()));
    }

    /**
     * Calculates the ratio of special characters to total characters.
     * Special characters include punctuation, symbols, etc.
     */
    private static double calculateSpecialCharacterRatio(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }

        long specialCharCount = text.chars()
                .filter(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch))
                .count();

        return (double) specialCharCount / text.length();
    }

    /**
     * Checks for repeated character patterns that indicate gibberish.
     */
    private static boolean hasRepeatedCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // Check for patterns like "aaaaaa", "!!!!!!", etc.
        return REPEATED_CHARS_PATTERN.matcher(text).find();
    }
}