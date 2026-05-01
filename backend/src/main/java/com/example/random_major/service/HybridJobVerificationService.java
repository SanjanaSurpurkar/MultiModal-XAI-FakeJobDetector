package com.example.random_major.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.example.random_major.model.HybridVerificationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HybridJobVerificationService: Real-time job verification combining two methods
 * 
 * METHOD 1: Website-based verification
 * - Resolve company domain (from provided domain or https://{company}.com)
 * - Verify domain exists via DNS
 * - Check careers/jobs pages
 * - Extract HTML and search for job title and location keywords
 * 
 * METHOD 2: Search API verification
 * - Construct search query: "{companyName} {jobTitle} jobs"
 * - Call SerpAPI to get top 5 results
 * - Parse results and check:
 *   - Company name match
 *   - Job title keyword match
 *   - Domain match (using DomainValidationService)
 *   - Location match
 * 
 * SCORING:
 * Website Score (0-1):
 *   +0.2 if domain exists
 *   +0.3 if careers page exists
 *   +0.3 if job title match
 *   +0.2 if location match
 * 
 * Search Score (0-1):
 *   +0.3 if company appears in results
 *   +0.3 if job title keywords match
 *   +0.3 if domain matches official
 *   +0.1 if location matches
 * 
 * Final Score = (websiteScore * 0.5) + (searchScore * 0.5)
 * 
 * FAIL-SAFE:
 * If API fails or website unreachable → return neutral score (0.5)
 */
@Service
public class HybridJobVerificationService {

    private static final Logger log = LoggerFactory.getLogger(HybridJobVerificationService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${serpapi.api-key:}")
    private String serpApiKey;

    @Value("${serpapi.enabled:true}")
    private boolean serpApiEnabled;

    /**
     * Main verification method
     * 
     * @param companyName   The company name to verify
     * @param jobTitle      The job title (extract keywords)
     * @param location      Optional job location (can be null)
     * @param extractedText The full extracted text from job posting
     * @param companyDomain The company domain (can be null, will be resolved)
     * @return HybridVerificationResponse with combined scores
     */
    public HybridVerificationResponse verify(String companyName, String jobTitle, String location,
            String extractedText, String companyDomain) {

        long startTime = System.currentTimeMillis();
        log.info("🔍 Starting hybrid verification for: companyName={}, jobTitle={}, location={}",
                companyName, jobTitle, location);

        try {
            // STEP 1: Website verification
            HybridVerificationResponse websiteResult = verifyWebsite(companyName, jobTitle, location,
                    extractedText, companyDomain);

            // STEP 2: Search API verification
            HybridVerificationResponse searchResult = null;
            if (serpApiEnabled && (serpApiKey != null && !serpApiKey.isEmpty())) {
                searchResult = verifyViaSearchAPI(companyName, jobTitle, location);
            } else {
                log.warn("⚠️  SerpAPI disabled or key missing, skipping search verification");
                searchResult = new HybridVerificationResponse();
                searchResult.setSearchScore(0.5); // Neutral
                searchResult.setMessage("SerpAPI verification skipped");
            }

            // STEP 3: Combine scores
            HybridVerificationResponse finalResult = combineResults(websiteResult, searchResult);
            finalResult.setVerificationTimeMs(System.currentTimeMillis() - startTime);

            log.info(
                    "✅ Hybrid verification completed in {}ms: websiteScore={}, searchScore={}, finalScore={}",
                    finalResult.getVerificationTimeMs(), finalResult.getWebsiteScore(),
                    finalResult.getSearchScore(), finalResult.getFinalScore());

            return finalResult;

        } catch (Exception e) {
            log.error("❌ Hybrid verification failed: {}", e.getMessage(), e);
            HybridVerificationResponse neutral = HybridVerificationResponse
                    .neutral("Verification failed: " + e.getMessage());
            neutral.setVerificationTimeMs(System.currentTimeMillis() - startTime);
            return neutral;
        }
    }

    // ===================================
    // METHOD 1: WEBSITE VERIFICATION
    // ===================================

    private HybridVerificationResponse verifyWebsite(String companyName, String jobTitle,
            String location, String extractedText, String companyDomain) {

        log.info("📍 STEP 1: Website verification starting...");

        HybridVerificationResponse result = new HybridVerificationResponse();
        double websiteScore = 0.0;

        try {
            // STEP 1.1: Resolve domain
            String domain = resolveDomain(companyName, companyDomain);

            if (domain == null) {
                log.warn("❌ Cannot resolve domain for company: {}", companyName);
                result.setWebsiteExists(false);
                result.setWebsiteScore(0.0);
                result.setMessage("Domain resolution failed");
                return result;
            }

            log.info("   ✓ Using domain: {}", domain);

            // STEP 1.2: Verify domain exists via DNS
            boolean domainExists = verifyDomainViaDNS(domain);
            result.setWebsiteExists(domainExists);

            if (domainExists) {
                websiteScore += 0.2;
                log.info("   ✓ Domain exists (+0.2)");
            } else {
                log.warn("   ✗ Domain does not exist via DNS");
            }

            // STEP 1.3: Check careers page
            boolean careersPageFound = false;
            if (domainExists) {
                careersPageFound = checkCareersPage(domain);
                result.setCareersPageExists(careersPageFound);

                if (careersPageFound) {
                    websiteScore += 0.3;
                    log.info("   ✓ Careers page found (+0.3)");
                } else {
                    log.info("   ℹ Careers page not found");
                }
            }

            // STEP 1.4: Check job title match
            if (careersPageFound && jobTitle != null && !jobTitle.trim().isEmpty()) {
                String htmlContent = fetchPageContent(domain, "/careers");
                if (htmlContent == null) {
                    htmlContent = fetchPageContent(domain, "/jobs");
                }

                if (htmlContent != null) {
                    boolean titleMatches = matchesJobTitle(htmlContent, jobTitle);
                    result.setJobMatchWebsite(titleMatches);

                    if (titleMatches) {
                        websiteScore += 0.3;
                        log.info("   ✓ Job title match found (+0.3)");
                    } else {
                        log.info("   ℹ Job title not found on careers page");
                    }
                }
            }

            // STEP 1.5: Check location match
            if (location != null && !location.trim().isEmpty()) {
                if (careersPageFound) {
                    String htmlContent = fetchPageContent(domain, "/careers");
                    if (htmlContent == null) {
                        htmlContent = fetchPageContent(domain, "/jobs");
                    }

                    if (htmlContent != null) {
                        boolean locationMatches = matchesLocation(htmlContent, location);
                        result.setLocationMatch(locationMatches);

                        if (locationMatches) {
                            websiteScore += 0.2;
                            log.info("   ✓ Location match found (+0.2)");
                        } else {
                            log.info("   ℹ Location not found on careers page");
                        }
                    }
                }
            }

            result.setWebsiteScore(websiteScore);
            log.info("   📊 Website score: {}", websiteScore);

        } catch (Exception e) {
            log.error("   ❌ Website verification error: {}", e.getMessage());
            result.setWebsiteScore(0.0);
        }

        return result;
    }

    private String resolveDomain(String companyName, String companyDomain) {
        // Priority 1: Use provided domain
        if (companyDomain != null && !companyDomain.trim().isEmpty()) {
            return companyDomain.trim();
        }

        // Priority 2: Try https://{company}.com
        if (companyName != null && !companyName.trim().isEmpty()) {
            String domainName = companyName.toLowerCase().replaceAll("\\s+", "-").replaceAll("[^a-z0-9-]", "");
            return domainName + ".com";
        }

        return null;
    }

    private boolean verifyDomainViaDNS(String domain) {
        try {
            String domainName = domain;
            if (domain.startsWith("http://") || domain.startsWith("https://")) {
                domainName = new URI(domain).getHost();
            }

            if (domainName == null || domainName.isEmpty()) {
                return false;
            }

            InetAddress.getByName(domainName);
            return true;
        } catch (Exception e) {
            log.debug("DNS verification failed for {}: {}", domain, e.getMessage());
            return false;
        }
    }

    private boolean checkCareersPage(String domain) {
        try {
            // Ensure domain is just the domain name
            String domainName = domain;
            if (domain.startsWith("http://") || domain.startsWith("https://")) {
                domainName = new URI(domain).getHost();
            }

            // Try /careers endpoint
            if (checkUrlExists("https://" + domainName + "/careers")) {
                return true;
            }

            // Try /jobs endpoint
            if (checkUrlExists("https://" + domainName + "/jobs")) {
                return true;
            }

            return false;
        } catch (Exception e) {
            log.debug("Career page check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkUrlExists(String url) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            int status = response.getStatusCode().value();
            return status == 200;
        } catch (Exception e) {
            log.debug("URL check failed for {}: {}", url, e.getMessage());
            return false;
        }
    }

    private String fetchPageContent(String domain, String path) {
        try {
            String domainName = domain;
            if (domain.startsWith("http://") || domain.startsWith("https://")) {
                domainName = new URI(domain).getHost();
            }

            String url = "https://" + domainName + path;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.debug("Failed to fetch content from {}{}: {}", domain, path, e.getMessage());
        }

        return null;
    }

    private boolean matchesJobTitle(String htmlContent, String jobTitle) {
        if (htmlContent == null || jobTitle == null) {
            return false;
        }

        String contentLower = htmlContent.toLowerCase();
        String[] keywords = jobTitle.toLowerCase().split("\\s+");

        // Check if at least 60% of keywords match
        int matchCount = 0;
        for (String keyword : keywords) {
            if (keyword.length() > 2 && contentLower.contains(keyword)) {
                matchCount++;
            }
        }

        return (double) matchCount / keywords.length >= 0.6;
    }

    private boolean matchesLocation(String htmlContent, String location) {
        if (htmlContent == null || location == null) {
            return false;
        }

        String contentLower = htmlContent.toLowerCase();
        String locationLower = location.toLowerCase();

        // Check if location or its major keywords appear
        String[] locationParts = locationLower.split("\\s|,");
        int matches = 0;

        for (String part : locationParts) {
            if (part.length() > 2 && contentLower.contains(part)) {
                matches++;
            }
        }

        return matches > 0;
    }

    // ===================================
    // METHOD 2: SEARCH API VERIFICATION
    // ===================================

    private HybridVerificationResponse verifyViaSearchAPI(String companyName, String jobTitle,
            String location) {

        log.info("🔍 STEP 2: Search API verification starting...");

        HybridVerificationResponse result = new HybridVerificationResponse();
        double searchScore = 0.0;

        try {
            // STEP 2.1: Construct search query
            String query = constructSearchQuery(companyName, jobTitle, location);
            log.info("   Query: {}", query);

            // STEP 2.2: Call SerpAPI
            String apiUrl = "https://serpapi.com/search.json?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&api_key=" + serpApiKey + "&num=5";

            ResponseEntity<String> response = restTemplate.getForEntity(apiUrl, String.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                log.warn("   ❌ SerpAPI returned status: {}", response.getStatusCode());
                result.setSearchScore(0.5);
                result.setApiStatus("PARTIAL");
                return result;
            }

            // STEP 2.3: Parse results
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            JsonNode resultsNode = rootNode.get("organic_results");

            if (resultsNode == null || resultsNode.isEmpty()) {
                log.warn("   ℹ No results from SerpAPI");
                result.setSearchScore(0.0);
                result.setMessage("No search results found");
                return result;
            }

            // STEP 2.4: Extract top 5 links and analyze
            List<String> topLinks = new ArrayList<>();
            boolean companyFound = false;
            boolean jobTitleMatched = false;
            boolean domainMatched = false;

            for (int i = 0; i < Math.min(5, resultsNode.size()); i++) {
                JsonNode result_item = resultsNode.get(i);

                String title = result_item.has("title") ? result_item.get("title").asText() : "";
                String link = result_item.has("link") ? result_item.get("link").asText() : "";
                String snippet = result_item.has("snippet") ? result_item.get("snippet").asText() : "";

                if (!link.isEmpty()) {
                    topLinks.add(link);
                }

                String fullContent = (title + " " + snippet).toLowerCase();

                // Check for company name
                if (!companyFound && fullContent.contains(companyName.toLowerCase())) {
                    companyFound = true;
                    searchScore += 0.3;
                    log.info("   ✓ Company found in results (+0.3)");
                }

                // Check for job title keywords
                if (!jobTitleMatched && jobTitle != null && !jobTitleMatched) {
                    if (matchesJobTitle(fullContent, jobTitle)) {
                        jobTitleMatched = true;
                        searchScore += 0.3;
                        log.info("   ✓ Job title match in results (+0.3)");
                    }
                }

                // Check for domain match
                if (!domainMatched && link != null && !link.isEmpty()) {
                    try {
                        String resultDomain = new URI(link).getHost();
                        if (resultDomain != null && resultDomain.contains(companyName.toLowerCase().replaceAll("\\s+", ""))) {
                            domainMatched = true;
                            searchScore += 0.3;
                            log.info("   ✓ Official domain found (+0.3)");
                        }
                    } catch (Exception e) {
                        log.debug("Failed to extract domain from link: {}", link);
                    }
                }
            }

            // Check for location match
            if (location != null && !location.trim().isEmpty()) {
                for (int i = 0; i < Math.min(5, resultsNode.size()); i++) {
                    JsonNode result_item = resultsNode.get(i);
                    String snippet = result_item.has("snippet") ? result_item.get("snippet").asText() : "";

                    if (matchesLocation(snippet, location)) {
                        searchScore += 0.1;
                        result.setLocationMatch(true);
                        log.info("   ✓ Location match in results (+0.1)");
                        break;
                    }
                }
            }

            result.setTopSearchLinks(topLinks);
            result.setSearchScore(Math.min(1.0, searchScore)); // Cap at 1.0
            result.setJobMatchSearch(jobTitleMatched);
            result.setApiStatus("SUCCESS");
            log.info("   📊 Search score: {}", result.getSearchScore());

        } catch (Exception e) {
            log.error("   ❌ Search API verification error: {}", e.getMessage());
            result.setSearchScore(0.5);
            result.setApiStatus("FAILED");
            result.setMessage("Search verification failed: " + e.getMessage());
        }

        return result;
    }

    private String constructSearchQuery(String companyName, String jobTitle, String location) {
        StringBuilder query = new StringBuilder();

        if (companyName != null && !companyName.trim().isEmpty()) {
            query.append(companyName).append(" ");
        }

        if (jobTitle != null && !jobTitle.trim().isEmpty()) {
            query.append(jobTitle).append(" ");
        }

        query.append("jobs");

        if (location != null && !location.trim().isEmpty()) {
            query.append(" ").append(location);
        }

        return query.toString();
    }

    // ===================================
    // COMBINE RESULTS
    // ===================================

    private HybridVerificationResponse combineResults(HybridVerificationResponse websiteResult,
            HybridVerificationResponse searchResult) {

        HybridVerificationResponse combined = new HybridVerificationResponse();

        // Copy website results
        combined.setWebsiteExists(websiteResult.isWebsiteExists());
        combined.setCareersPageExists(websiteResult.isCareersPageExists());
        combined.setJobMatchWebsite(websiteResult.isJobMatchWebsite());
        combined.setWebsiteScore(websiteResult.getWebsiteScore());

        // Copy search results
        combined.setSearchScore(searchResult.getSearchScore());
        combined.setJobMatchSearch(searchResult.isJobMatchSearch());
        combined.setLocationMatch(websiteResult.isLocationMatch() || searchResult.isLocationMatch());
        combined.setTopSearchLinks(searchResult.getTopSearchLinks());

        // Calculate final score: average of both methods
        double finalScore = (websiteResult.getWebsiteScore() * 0.5) + (searchResult.getSearchScore() * 0.5);
        combined.setFinalScore(finalScore);

        // Determine overall status
        if (websiteResult.isWebsiteExists() || (searchResult.getTopSearchLinks() != null
                && !searchResult.getTopSearchLinks().isEmpty())) {
            combined.setApiStatus("SUCCESS");
            combined.setMessage("Hybrid verification completed");
        } else {
            combined.setApiStatus("PARTIAL");
            combined.setMessage("Limited verification data available");
        }

        return combined;
    }
}
