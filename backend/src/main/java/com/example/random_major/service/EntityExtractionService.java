package com.example.random_major.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.random_major.entity.ExtractedData;

/**
 * EntityExtractionService: Extracts structured information from job text
 * 
 * WORKFLOW:
 * 1. Receive clean extracted text from TextExtractService
 * 2. Extract URL using regex pattern (first valid URL)
 * 3. Extract domain from URL using java.net.URI
 * 4. Extract company name using ROBUST 3-STEP HYBRID APPROACH:
 *    - STEP 1: Regex patterns (Company: XYZ, at XYZ, join XYZ, hiring at XYZ)
 *    - STEP 2: Known company keyword matching (case-insensitive)
 *    - STEP 3: Domain-based fallback (infosys.com → Infosys)
 * 5. Clean result (remove Ltd, Pvt, Inc, etc.)
 * 6. Return ExtractedData DTO with all fields
 * 
 * ROBUSTNESS IMPROVEMENTS:
 * - Handles noisy OCR text (messy formatting, lowercase/mixed case)
 * - Multi-layer extraction reduces null values
 * - Known company list ensures high accuracy for common IT companies
 * - Domain extraction with subdomain handling (careers.wipro.com → Wipro)
 * - Comprehensive logging for debugging
 * 
 * EDGE CASES HANDLED:
 * - Null or empty text → return ExtractedData with null values
 * - Single word domains → extract safely
 * - Noisy OCR text → works with mixed case and special characters
 * - Multiple URLs → takes first one
 * - Placeholder domains (example.com) → skipped
 */
@Service
public class EntityExtractionService {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);

    // ── Regex Patterns ──────────────────────────────────────────
    // URL patterns - matches:
    // 1. http://, https://, ftp:// protocols
    // 2. www. prefix URLs
    // 3. Bare domain names (apple.com, example.co.uk, etc.)
    private static final String URL_REGEX = "https?://[^\\s]+|ftp://[^\\s]+|www\\.[^\\s]+|[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";

    // Company name extraction patterns - match company name only (1-3 words before punctuation/keywords)
    private static final Pattern COMPANY_PATTERN_1 = Pattern.compile("(?i)company:\\s*([A-Za-z0-9&.-]+(?:\\s+[A-Za-z0-9&.-]+)?)(?:\\s+(?:is|are|has|have|pvt|ltd|inc|corp|llc|limited)|[.,]|$)");
    private static final Pattern COMPANY_PATTERN_2 = Pattern.compile("(?i)\\bat\\s+([A-Za-z0-9&.-]+(?:\\s+[A-Za-z0-9&.-]+)?)(?:\\s+(?:is|are|has|have|pvt|ltd|inc|corp|llc|limited|for|in|and)|[.,]|$)");
    private static final Pattern COMPANY_PATTERN_3 = Pattern.compile("(?i)join\\s+([A-Za-z0-9&.-]+(?:\\s+[A-Za-z0-9&.-]+)?)(?:\\s+(?:is|are|has|have|pvt|ltd|inc|corp|llc|limited|for|and)|[.,]|$)");
    private static final Pattern COMPANY_PATTERN_4 = Pattern.compile("(?i)hiring\\s+at\\s+([A-Za-z0-9&.-]+(?:\\s+[A-Za-z0-9&.-]+)?)(?:\\s+(?:is|are|has|have|pvt|ltd|inc|corp|llc|limited|for)|[.,]|$)");
    private static final Pattern COMPANY_PATTERN_5 = Pattern.compile("(?i)hiring\\s+for\\s+([A-Za-z0-9&.-]+(?:\\s+[A-Za-z0-9&.-]+)?)");
    private static final Pattern COMPANY_PATTERN_6 = Pattern.compile("(?i)work\\s+at\\s+([A-Za-z0-9&.-]+(?:\\s+[A-Za-z0-9&.-]+)?)(?:\\s|[.,]|$)");
    
    // Email regex pattern - extracts email addresses
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    // Known companies list - covers common IT and global companies
    // Supports both exact names and variations
    private static final List<String> KNOWN_COMPANIES = Arrays.asList(
        // Major Indian IT Companies
        "Infosys", "TCS", "Wipro", "Accenture", "HCL", "Tech Mahindra", "Capgemini",
        "Cognizant", "Mindtree", "Zoho", "Persistent", "iGate",
        
        // Global Tech Giants
        "Google", "Microsoft", "Amazon", "Apple", "Meta", "Facebook", "IBM", "Oracle",
        "Salesforce", "Cisco", "Dell", "Hewlett", "HP", "VMware", "Adobe", "Atlassian",
        "Slack", "Stripe", "Uber", "Airbnb", "Netflix", "Twitter", "LinkedIn", "Snapchat",
        
        // Other Major Companies
        "Walmart", "Target", "Flipkart", "PayPal", "Visa", "Mastercard",
        "JPMorgan", "Goldman", "Morgan Stanley", "Bank of America",
        "Samsung", "LG", "Sony", "Nokia", "Philips",
        
        // Financial/Enterprise
        "Merrill", "Bloomberg", "Reuters", "Thomson"
    );

    // Words to remove for cleaning (legal entity types)
    private static final String[] CLEANUP_WORDS = {
        "\\bLtd\\b", "\\bLtd\\.?\\b", "\\bPvt\\b", "\\bPvt\\.?\\b", "\\bInc\\b", "\\bInc\\.?\\b", 
        "\\bCorp\\b", "\\bCorporation\\b", "\\bLLC\\b", "\\bLLC\\.?\\b",
        "\\bLimited\\b", "\\bPrivate\\b", "\\bPublic\\b"
    };

    // Common subdomains to ignore when extracting company from domain
    private static final String[] COMMON_SUBDOMAINS = {
        "www", "jobs", "careers", "hiring", "apply", "recruit", "recruitment",
        "mail", "webmail", "smtp", "ftp", "admin", "api", "cdn", "cdn1", "cdn2",
        "blog", "news", "support", "help", "forum", "dev", "stage", "test",
        "demo", "beta", "alpha", "v1", "v2", "portal", "app"
    };

    // Placeholder company names to reject (common test/example values)
    private static final String[] PLACEHOLDER_COMPANIES = {
        "example", "test", "sample", "demo", "placeholder", "fake", "temp"
    };

    /**
     * Extracts structured information from job text
     * 
     * @param text The clean extracted text from TextExtractService
     * @return ExtractedData DTO with companyName, url, and domain
     */
    public ExtractedData extractFromText(String text) {
        log.debug("Starting entity extraction from text...");

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ EDGE CASE: Null or empty text                                  │
        // └─────────────────────────────────────────────────────────────────┘
        if (text == null || text.trim().isEmpty()) {
            log.warn("⚠️  Text is null or empty, returning ExtractedData with null values");
            return new ExtractedData(null, null, null);
        }

        // Log text preview for debugging
        String textPreview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
        log.info("📝 Input text: {}", textPreview);

        ExtractedData result = new ExtractedData();

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ STEP 1: URL Extraction                                         │
        // └─────────────────────────────────────────────────────────────────┘
        String extractedUrl = extractUrl(text);
        result.setUrl(extractedUrl);
        log.debug("Extracted URL: {}", extractedUrl);

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ STEP 2: Email & Domain Extraction                              │
        // └─────────────────────────────────────────────────────────────────┘
        String email = extractEmail(text);
        result.setEmail(email);
        if (email != null) {
            log.debug("📧 Extracted email: {}", email);
        }

        String domain = null;
        
        // Try to extract domain from URL first
        if (extractedUrl != null) {
            domain = extractDomain(extractedUrl);
            result.setDomain(domain);
            log.debug("📧 Extracted domain from URL: {}", domain);
        }
        
        // If no domain from URL, try to extract from email
        if (domain == null && email != null) {
            domain = extractDomainFromEmail(email);
            if (domain != null) {
                result.setDomain(domain);
                log.info("📧 Extracted email domain: {}", domain);
            }
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ STEP 3: Company Name Extraction (ROBUST 3-STEP HYBRID)         │
        // └─────────────────────────────────────────────────────────────────┘
        String companyName = extractCompanyName(text, domain);
        
        // Clean result
        if (companyName != null) {
            companyName = cleanCompanyName(companyName);
        }

        result.setCompanyName(companyName);
        
        // ┌─────────────────────────────────────────────────────────────────┐
        // │ VALIDATION: Reject placeholder company names                   │
        // └─────────────────────────────────────────────────────────────────┘
        if (companyName != null && isPlaceholderCompany(companyName)) {
            log.warn("⚠️  Company name is a placeholder: '{}' - rejecting", companyName);
            result.setCompanyName(null);
            companyName = null;
        }
        
        // ┌─────────────────────────────────────────────────────────────────┐
        // │ LOGGING: Final extracted data with source                      │
        // └─────────────────────────────────────────────────────────────────┘
        log.info("✅ Entity extraction completed:");
        log.info("   Company: '{}' | URL: '{}' | Domain: '{}'", 
            companyName, extractedUrl, domain);

        return result;
    }

    /**
     * Extracts the first valid URL from text using regex
     * 
     * @param text The input text
     * @return First valid URL found, or null if no URL found
     */
    private String extractUrl(String text) {
        Pattern urlPattern = Pattern.compile(URL_REGEX);
        Matcher matcher = urlPattern.matcher(text);

        if (matcher.find()) {
            String url = matcher.group();
            log.debug("Found URL: {}", url);
            return url;
        }

        log.debug("❌ No URL found in text");
        return null;
    }

    /**
     * Extracts domain name from URL using java.net.URI
     * 
     * Handles subdomains by extracting the main domain (second-level domain + TLD)
     * Example: https://jobs.infosys.com/careers → infosys.com
     * Example 2: https://infosys.com/careers → infosys.com
     * 
     * @param url The URL string
     * @return Main domain name, or null if extraction fails
     */
    private String extractDomain(String url) {
        try {
            // Handle URLs without scheme (e.g., www.example.com)
            String urlToProcess = url;
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ftp://")) {
                urlToProcess = "https://" + url;
            }

            URI uri = new URI(urlToProcess);
            String host = uri.getHost();

            if (host != null && !host.isEmpty()) {
                // Remove 'www.' prefix if present
                String cleaned = host.replaceFirst("^www\\.", "");
                
                // Extract main domain (last two parts: domain + TLD)
                // e.g., jobs.company.com → company.com, infosys.com → infosys.com
                String[] parts = cleaned.split("\\.");
                if (parts.length >= 2) {
                    // Take the last two parts (main domain and TLD)
                    String domain = parts[parts.length - 2] + "." + parts[parts.length - 1];
                    log.debug("Extracted domain from URL '{}': {}", url, domain);
                    return domain;
                } else {
                    // Single part domain, return as is
                    log.debug("Extracted domain from URL '{}': {}", url, cleaned);
                    return cleaned;
                }
            }
        } catch (URISyntaxException e) {
            log.warn("❌ Invalid URL format: {}, Error: {}", url, e.getMessage());
        }

        return null;
    }

    /**
     * ROBUST 3-STEP HYBRID COMPANY NAME EXTRACTION
     * 
     * Implements a multi-layer approach to minimize null values and improve accuracy:
     * STEP 1: Regex pattern matching (high precision)
     * STEP 2: Known company keyword matching (handles noisy OCR and unstructured text)
     * STEP 3: Domain-based fallback (broad coverage)
     * 
     * @param text The input text
     * @param domain The extracted domain (for fallback)
     * @return Company name if found, null otherwise
     */
    private String extractCompanyName(String text, String domain) {
        if (text == null || text.trim().isEmpty()) {
            log.debug("Text is empty, cannot extract company name");
            return null;
        }

        log.debug("🔍 Starting 3-step company name extraction...");

        // ═══════════════════════════════════════════════════════════
        // STEP 1: REGEX-BASED EXTRACTION (Primary)
        // ═══════════════════════════════════════════════════════════
        log.debug("   STEP 1: Attempting regex-based extraction...");
        String companyName = extractCompanyFromPatterns(text);
        
        if (companyName != null && !companyName.trim().isEmpty()) {
            log.info("   ✅ STEP 1 SUCCESS: Found via regex pattern: '{}'", companyName);
            return companyName;
        }
        log.debug("   ❌ STEP 1 FAILED: No regex pattern matched");

        // ═══════════════════════════════════════════════════════════
        // STEP 2: KNOWN COMPANY KEYWORD MATCHING (Fallback)
        // ═══════════════════════════════════════════════════════════
        log.debug("   STEP 2: Attempting known company keyword matching...");
        companyName = extractCompanyFromKeywords(text);
        
        if (companyName != null && !companyName.trim().isEmpty()) {
            log.info("   ✅ STEP 2 SUCCESS: Found via keyword matching: '{}'", companyName);
            return companyName;
        }
        log.debug("   ❌ STEP 2 FAILED: No known company keyword matched");

        // ═══════════════════════════════════════════════════════════
        // STEP 3: DOMAIN-BASED FALLBACK (Final Fallback)
        // ═══════════════════════════════════════════════════════════
        if (domain != null && !domain.trim().isEmpty()) {
            log.debug("   STEP 3: Attempting domain-based extraction...");
            companyName = extractCompanyFromDomain(domain);
            
            if (companyName != null && !companyName.trim().isEmpty()) {
                log.info("   ✅ STEP 3 SUCCESS: Found via domain: '{}' → '{}'", domain, companyName);
                return companyName;
            }
            log.debug("   ❌ STEP 3 FAILED: Could not extract from domain");
        } else {
            log.debug("   ⏭️  STEP 3 SKIPPED: No domain available");
        }

        // ═══════════════════════════════════════════════════════════
        // NO COMPANY FOUND
        // ═══════════════════════════════════════════════════════════
        log.warn("   ⚠️  All extraction attempts failed - returning null");
        return null;
    }

    /**
     * Extracts company name using regex patterns
     * 
     * Patterns:
     * 1. "Company: XYZ"
     * 2. "at XYZ"
     * 3. "join XYZ"
     * 4. "hiring at XYZ"
     * 5. "hiring for XYZ"
     * 6. "work at XYZ"
     * 
     * @param text The input text
     * @return Company name if found, null otherwise
     */
    private String extractCompanyFromPatterns(String text) {
        // Pattern 1: "Company: XYZ"
        String company = extractWithPattern(text, COMPANY_PATTERN_1, "pattern 'Company: XYZ'");
        if (company != null) {
            return company;
        }

        // Pattern 2: "at XYZ" (more specific)
        company = extractWithPattern(text, COMPANY_PATTERN_2, "pattern 'at XYZ'");
        if (company != null) {
            return company;
        }

        // Pattern 3: "join XYZ"
        company = extractWithPattern(text, COMPANY_PATTERN_3, "pattern 'join XYZ'");
        if (company != null) {
            return company;
        }

        // Pattern 4: "hiring at XYZ"
        company = extractWithPattern(text, COMPANY_PATTERN_4, "pattern 'hiring at XYZ'");
        if (company != null) {
            return company;
        }

        // Pattern 5: "hiring for XYZ"
        company = extractWithPattern(text, COMPANY_PATTERN_5, "pattern 'hiring for XYZ'");
        if (company != null) {
            return company;
        }

        // Pattern 6: "work at XYZ"
        company = extractWithPattern(text, COMPANY_PATTERN_6, "pattern 'work at XYZ'");
        if (company != null) {
            return company;
        }

        log.debug("❌ No company found in text patterns");
        return null;
    }

    /**
     * Extracts company name from predefined list of known companies
     * Handles noisy OCR text by doing case-insensitive matching
     * 
     * Use case: Works with messy text like "we r hiring @ wipro ltd", "apply now at INFOSYS"
     * 
     * @param text The input text (can be noisy from OCR/transcription)
     * @return Company name if found in known companies list, null otherwise
     */
    private String extractCompanyFromKeywords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String lowerText = text.toLowerCase();
        
        // Check each known company
        for (String company : KNOWN_COMPANIES) {
            String lowerCompany = company.toLowerCase();
            
            // Use word boundary matching to avoid partial matches
            // E.g., "Infosys" should match but not "Infosys" in "Infosytes"
            Pattern companyPattern = Pattern.compile("\\b" + Pattern.quote(lowerCompany) + "\\b");
            
            if (companyPattern.matcher(lowerText).find()) {
                log.debug("Found known company keyword: '{}' in text", company);
                return company; // Return with proper capitalization
            }
        }

        log.debug("❌ No known company found in text");
        return null;
    }

    /**
     * Helper method to extract company name with a specific pattern
     * 
     * @param text The input text
     * @param pattern The regex pattern
     * @param patternName The name of the pattern for logging
     * @return Extracted company name if found, null otherwise
     */
    private String extractWithPattern(String text, Pattern pattern, String patternName) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String company = matcher.group(1).trim();
            if (!company.isEmpty()) {
                log.debug("Found company name using {}: {}", patternName, company);
                return company;
            }
        }
        return null;
    }

    /**
     * Extracts company name from domain with advanced subdomain handling
     * 
     * Examples:
     * - infosys.com → Infosys
     * - careers.wipro.com → Wipro (removes career subdomain)
     * - jobs.company.co.uk → Company (handles multi-level TLD)
     * - www.google.com → Google (removes www)
     * 
     * Skips placeholder domains like example.com
     * 
     * @param domain The domain name (e.g., infosys.com, careers.wipro.com)
     * @return Capitalized company name derived from domain, or null for placeholder domains
     */
    private String extractCompanyFromDomain(String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            log.debug("Domain is null or empty");
            return null;
        }
        
        String lowerDomain = domain.toLowerCase().trim();
        
        // Skip placeholder/example domains
        if (lowerDomain.startsWith("example.") || lowerDomain.equals("example.com") ||
            lowerDomain.startsWith("test.") || lowerDomain.equals("localhost") ||
            lowerDomain.startsWith("sample.")) {
            log.debug("Skipping placeholder domain: {}", domain);
            return null;
        }

        // Split domain into parts
        String[] parts = domain.split("\\.");
        if (parts.length == 0) {
            log.debug("Could not split domain: {}", domain);
            return null;
        }

        // Extract company name by removing common subdomains
        String companyPart = null;
        
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].toLowerCase();
            
            // Skip common subdomains and TLDs
            if (!isCommonSubdomain(part) && !isTLD(part)) {
                companyPart = parts[i];
                break;
            }
        }

        // If no non-subdomain part found, use the first part (second level domain)
        if (companyPart == null && parts.length >= 2) {
            companyPart = parts[parts.length - 2];
        }

        if (companyPart == null || companyPart.isEmpty()) {
            log.debug("Could not extract company part from domain: {}", domain);
            return null;
        }

        // Capitalize first letter, lowercase rest
        String capitalizedName = companyPart.substring(0, 1).toUpperCase() + 
                                 companyPart.substring(1).toLowerCase();
        
        log.debug("Extracted company from domain '{}': {}", domain, capitalizedName);
        return capitalizedName;
    }

    /**
     * Checks if a string is a common subdomain
     * 
     * @param part The domain part to check
     * @return true if it's a common subdomain, false otherwise
     */
    private boolean isCommonSubdomain(String part) {
        String lowerPart = part.toLowerCase();
        for (String subdomain : COMMON_SUBDOMAINS) {
            if (lowerPart.equals(subdomain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a string is a top-level domain (TLD)
     * 
     * @param part The domain part to check
     * @return true if it's a TLD, false otherwise
     */
    private boolean isTLD(String part) {
        String lowerPart = part.toLowerCase();
        // Common TLDs and country codes
        return lowerPart.matches("(com|org|net|edu|gov|mil|int|io|co|uk|us|in|de|fr|jp|au|ca|br|ru|cn|etc)");
    }

    /**
     * Cleans company name by removing extra words and trimming spaces
     * 
     * Removed words: Ltd, Pvt, Inc, Corp, Corporation, LLC, Limited, Private, Public
     * 
     * @param companyName The company name to clean
     * @return Cleaned company name
     */
    private String cleanCompanyName(String companyName) {
        String cleaned = companyName.trim();

        // Remove cleanup words
        for (String word : CLEANUP_WORDS) {
            cleaned = cleaned.replaceAll(word, "").trim();
        }

        // Remove multiple spaces
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (!cleaned.equals(companyName)) {
            log.debug("Cleaned company name from '{}' to '{}'", companyName, cleaned);
        }

        return cleaned;
    }

    /**
     * Extracts the first email address from text using regex
     * 
     * Pattern: [a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}
     * 
     * Examples:
     * - hr@google.com
     * - careers@infosys.com
     * - support@example.co.uk
     * 
     * @param text The input text
     * @return First valid email found, or null if no email found
     */
    private String extractEmail(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        if (matcher.find()) {
            String email = matcher.group();
            log.debug("Found email: {}", email);
            return email;
        }
        
        log.debug("❌ No email found in text");
        return null;
    }

    /**
     * Extracts domain from email address
     * 
     * Examples:
     * - hr@google.com → google.com
     * - careers@infosys.co.in → infosys.co.in
     * - support@company.ac.uk → company.ac.uk
     * 
     * @param email The email address
     * @return Domain part of the email, or null if extraction fails
     */
    private String extractDomainFromEmail(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        
        try {
            String domain = email.substring(email.indexOf("@") + 1);
            if (!domain.isEmpty()) {
                log.debug("Extracted domain from email '{}': {}", email, domain);
                return domain;
            }
        } catch (Exception e) {
            log.warn("Failed to extract domain from email '{}': {}", email, e.getMessage());
        }
        
        return null;
    }

    /**
     * Checks if a company name is a placeholder (test/example company)
     * 
     * Placeholder companies to reject:
     * - Example, example, EXAMPLE
     * - Test, test, TEST
     * - Sample, sample, SAMPLE
     * - Demo, demo, DEMO
     * - Placeholder, placeholder, PLACEHOLDER
     * - Fake, fake, FAKE
     * - Temp, temp, TEMP
     * 
     * @param companyName The company name to check
     * @return true if the company is a placeholder, false otherwise
     */
    private boolean isPlaceholderCompany(String companyName) {
        if (companyName == null || companyName.trim().isEmpty()) {
            return false;
        }
        
        String lowerName = companyName.toLowerCase().trim();
        
        for (String placeholder : PLACEHOLDER_COMPANIES) {
            if (lowerName.equals(placeholder)) {
                log.debug("Detected placeholder company: '{}'", companyName);
                return true;
            }
        }
        
        return false;
    }
}
