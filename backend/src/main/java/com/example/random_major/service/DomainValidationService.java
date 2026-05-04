package com.example.random_major.service;

import java.net.InetAddress;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.random_major.model.DomainValidationResponse;

@Service
public class DomainValidationService {

    private static final Logger log = LoggerFactory.getLogger(DomainValidationService.class);

    public DomainValidationResponse validateDomain(String companyWebsite, String jobPostingUrl, String contactEmail) {
        try {

            String companyDomain = extractDomain(companyWebsite);
            String extractedDomain = extractDomain(jobPostingUrl);

            // Fallback to email domain
            if (extractedDomain == null && contactEmail != null) {
                extractedDomain = extractEmailDomain(contactEmail);
            }

            // ✅ FIX: Better logging for debugging validation failures
            log.debug("🔍 Domain Validation Input:");
            log.debug("   companyWebsite input: {}", companyWebsite);
            log.debug("   jobPostingUrl input: {}", jobPostingUrl);
            log.debug("   contactEmail input: {}", contactEmail);
            log.debug("   → Extracted companyDomain: {}", companyDomain);
            log.debug("   → Extracted jobPostingDomain: {}", extractedDomain);

            // ✅ FIX: More flexible validation - at least one domain required
            if (extractedDomain == null) {
                log.warn("❌ Cannot extract any domain from jobPostingUrl, email, or both");
                return new DomainValidationResponse(false, 0.0, extractedDomain, companyDomain,
                        "No extractable domain found in job posting URL or email");
            }

            if (companyDomain == null) {
                log.warn("⚠️  Cannot extract company domain - will check if extracted domain is valid");
                // Still verify extracted domain exists
                boolean domainExists = verifyDomainViaDNS(extractedDomain);
                String message = domainExists ? "Domain exists but no company domain to compare" 
                    : "Domain does not exist";
                double score = domainExists ? 0.5 : 0.0;
                return new DomainValidationResponse(false, score, extractedDomain, null, message);
            }

            // 🚨 Free email detection (HIGH RISK)
            if (contactEmail != null) {
                String emailDomain = extractEmailDomain(contactEmail);
                if (isFreeEmail(emailDomain)) {
                    return new DomainValidationResponse(false, 0.2, emailDomain, companyDomain,
                            "Free email domain used (gmail/yahoo) - HIGH RISK");
                }
            }

            boolean extractedDNS = verifyDomainViaDNS(extractedDomain);

            ComparisonResult result = compareDomains(companyDomain, extractedDomain);

            // 🚨 DNS-based adjustments
            if (!extractedDNS) {
                result.riskScore *= 0.5;
                result.message += " (Domain does not exist)";
            }

            if (extractedDNS && !result.isMatch) {
                result.riskScore *= 0.5;
                result.message += " (Domain exists but mismatch)";
            }

            log.info("✓ Domain validation complete: company={}, extracted={}, match={}, score={}",
                    companyDomain, extractedDomain, result.isMatch, result.riskScore);

            return new DomainValidationResponse(
                    result.isMatch,
                    result.riskScore,
                    extractedDomain,
                    companyDomain,
                    result.message
            );

        } catch (Exception e) {
            log.error("Domain validation error: {}", e.getMessage(), e);
            return new DomainValidationResponse(false, 0.5, null, null, "Validation failed: " + e.getMessage());
        }
    }

    // =========================
    // 🌐 DNS CHECK
    // =========================
    public boolean verifyDomainViaDNS(String domain) {
        try {
            InetAddress.getByName(domain);
            return true;
        } catch (Exception e) {
            log.debug("DNS verification failed for domain: {}", domain);
            return false;
        }
    }

    // =========================
    // 🔗 DOMAIN EXTRACTION
    // =========================
    public String extractDomain(String url) {
        try {
            if (url == null || url.isEmpty()) return null;

            // ✅ FIX: Properly detect URL scheme (http, https, ftp, etc.)
            // Only prepend https:// if NO scheme is present at all
            if (!url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
                url = "https://" + url;
            }

            URI uri = new URI(url);
            String host = uri.getHost();

            if (host == null) return null;

            // Remove 'www.' prefix if present
            String cleaned = host.replaceFirst("^www\\.", "");

            // ✅ FIX: Extract base domain (main domain + TLD) like EntityExtractionService does
            // This ensures consistency: jobs.company.com → company.com, company.com → company.com
            String[] parts = cleaned.split("\\.");
            if (parts.length >= 2) {
                // Return last two parts (domain + TLD)
                String baseDomain = parts[parts.length - 2] + "." + parts[parts.length - 1];
                log.debug("Extracted base domain from URL '{}': {}", url, baseDomain);
                return baseDomain.toLowerCase();
            } else {
                // Single-part domain (unlikely but handle it)
                log.debug("Extracted domain from URL '{}': {}", url, cleaned);
                return cleaned.toLowerCase();
            }

        } catch (Exception e) {
            log.debug("Domain extraction failed for URL: {}", url);
            return null;
        }
    }

    // =========================
    // 📧 EMAIL DOMAIN
    // =========================
    public String extractEmailDomain(String email) {
        try {
            if (email == null || !email.contains("@")) return null;
            return email.substring(email.lastIndexOf("@") + 1).toLowerCase();
        } catch (Exception e) {
            log.debug("Email domain extraction failed for email: {}", email);
            return null;
        }
    }

    // =========================
    // 🚨 FREE EMAIL CHECK
    // =========================
    private boolean isFreeEmail(String domain) {
        String[] free = {
                "gmail.com", "yahoo.com", "outlook.com", "hotmail.com"
        };

        for (String f : free) {
            if (f.equals(domain)) return true;
        }
        return false;
    }

    // =========================
    // ✅ URL VALIDATION CHECK
    // =========================
    /**
     * ✅ NEW: Validates URL format and structure
     * Checks if a URL is properly formatted and not obviously invalid
     * 
     * @param url The URL to validate
     * @return true if URL appears valid, false if malformed
     */
    public boolean isValidUrl(String url) {
        try {
            if (url == null || url.trim().isEmpty()) return false;

            // Normalize URL
            String normalizedUrl = url.trim();
            if (!normalizedUrl.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
                normalizedUrl = "https://" + normalizedUrl;
            }

            // Try to parse as URI
            URI uri = new URI(normalizedUrl);
            
            // Check required components
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                log.debug("Invalid URL - no host: {}", url);
                return false;
            }

            // Host should have at least one dot (e.g., example.com)
            String host = uri.getHost();
            if (!host.contains(".") && !host.equals("localhost")) {
                log.debug("Invalid URL - invalid host format: {}", host);
                return false;
            }

            log.debug("✅ URL validation passed: {}", url);
            return true;

        } catch (Exception e) {
            log.debug("URL validation failed for '{}': {}", url, e.getMessage());
            return false;
        }
    }

    // =========================
    // 🧠 DOMAIN COMPARISON
    // =========================
    private ComparisonResult compareDomains(String companyDomain, String extractedDomain) {
        if (companyDomain == null || extractedDomain == null) {
            return new ComparisonResult(false, 0.0, "Missing domain for comparison");
        }

        // Normalize domains for comparison
        companyDomain = companyDomain.toLowerCase().trim();
        extractedDomain = extractedDomain.toLowerCase().trim();

        // Exact match
        if (companyDomain.equals(extractedDomain)) {
            log.info("✅ DOMAIN MATCH: Perfect match - {} == {}", companyDomain, extractedDomain);
            return new ComparisonResult(true, 1.0, "Perfect match");
        }

        // ✅ FIX: Improved subdomain matching
        // Extract base domains consistently for comparison
        String companyBase = getBaseDomain(companyDomain);
        String extractedBase = getBaseDomain(extractedDomain);

        log.debug("  Base domain comparison: {} vs {}", companyBase, extractedBase);

        // Subdomain match (e.g., jobs.company.com matches company.com)
        if (extractedBase.equals(companyBase)) {
            log.info("✅ DOMAIN MATCH: Base domain match - {} == {}", companyBase, extractedBase);
            return new ComparisonResult(true, 0.85, "Base domain match (subdomains match)");
        }

        // 🚨 Domain spoofing detection - extracted contains company but not as subdomain
        // e.g., fake-company.com contains "company" but isn't a subdomain of company.com
        if (extractedDomain.contains(companyDomain) && !extractedBase.equals(companyBase)) {
            log.warn("🚨 DOMAIN SPOOFING DETECTED: {} contains {} but domains don't match", 
                extractedDomain, companyDomain);
            return new ComparisonResult(false, 0.2, "Domain spoofing detected - name similarity without legitimate subdomain");
        }

        // No match
        log.warn("❌ DOMAIN MISMATCH: {} does not match {} - HIGH RISK", companyDomain, extractedDomain);
        return new ComparisonResult(false, 0.0, "Domain mismatch - HIGH RISK");
    }

    // =========================
    // 🌍 BASE DOMAIN
    // =========================
    private String getBaseDomain(String domain) {
        String[] parts = domain.split("\\.");

        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }

        return domain;
    }

    // =========================
    // INNER CLASS
    // =========================
    private static class ComparisonResult {
        boolean isMatch;
        double riskScore;
        String message;

        ComparisonResult(boolean isMatch, double riskScore, String message) {
            this.isMatch = isMatch;
            this.riskScore = riskScore;
            this.message = message;
        }
    }
}