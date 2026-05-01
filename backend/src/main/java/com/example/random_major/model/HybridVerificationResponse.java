package com.example.random_major.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HybridVerificationResponse: Real-time job verification combining website + search API methods
 * 
 * This DTO contains verification results from:
 * 1. Website-based verification (company careers page)
 * 2. Search API-based verification (SerpAPI/Google)
 * 
 * Both scores are combined into a final real-time score (0-1):
 * finalScore = (websiteScore * 0.5) + (searchScore * 0.5)
 */
public class HybridVerificationResponse {

    @JsonProperty("websiteExists")
    private boolean websiteExists;

    @JsonProperty("careersPageExists")
    private boolean careersPageExists;

    @JsonProperty("jobMatchWebsite")
    private boolean jobMatchWebsite;

    @JsonProperty("jobMatchSearch")
    private boolean jobMatchSearch;

    @JsonProperty("locationMatch")
    private boolean locationMatch;

    @JsonProperty("websiteScore")
    private double websiteScore;

    @JsonProperty("searchScore")
    private double searchScore;

    @JsonProperty("finalScore")
    private double finalScore;

    @JsonProperty("topSearchLinks")
    private List<String> topSearchLinks;

    @JsonProperty("message")
    private String message;

    @JsonProperty("verificationTimeMs")
    private long verificationTimeMs;

    @JsonProperty("apiStatus")
    private String apiStatus; // "SUCCESS", "PARTIAL", "FAILED"

    // ── Constructors ───────────────────────────────────────────
    public HybridVerificationResponse() {
    }

    public HybridVerificationResponse(boolean websiteExists, boolean careersPageExists,
            boolean jobMatchWebsite, boolean jobMatchSearch, boolean locationMatch,
            double websiteScore, double searchScore, double finalScore,
            List<String> topSearchLinks, String message) {
        this.websiteExists = websiteExists;
        this.careersPageExists = careersPageExists;
        this.jobMatchWebsite = jobMatchWebsite;
        this.jobMatchSearch = jobMatchSearch;
        this.locationMatch = locationMatch;
        this.websiteScore = websiteScore;
        this.searchScore = searchScore;
        this.finalScore = finalScore;
        this.topSearchLinks = topSearchLinks;
        this.message = message;
        this.apiStatus = "SUCCESS";
    }

    // ── Factory Methods for Fail-Safe ───────────────────────────
    public static HybridVerificationResponse neutral(String reason) {
        HybridVerificationResponse response = new HybridVerificationResponse();
        response.websiteExists = false;
        response.careersPageExists = false;
        response.jobMatchWebsite = false;
        response.jobMatchSearch = false;
        response.locationMatch = false;
        response.websiteScore = 0.5;
        response.searchScore = 0.5;
        response.finalScore = 0.5; // Neutral score
        response.message = reason;
        response.apiStatus = "FAILED";
        return response;
    }

    // ── Getters ──────────────────────────────────────────────────
    public boolean isWebsiteExists() { return websiteExists; }
    public boolean isCareersPageExists() { return careersPageExists; }
    public boolean isJobMatchWebsite() { return jobMatchWebsite; }
    public boolean isJobMatchSearch() { return jobMatchSearch; }
    public boolean isLocationMatch() { return locationMatch; }
    public double getWebsiteScore() { return websiteScore; }
    public double getSearchScore() { return searchScore; }
    public double getFinalScore() { return finalScore; }
    public List<String> getTopSearchLinks() { return topSearchLinks; }
    public String getMessage() { return message; }
    public long getVerificationTimeMs() { return verificationTimeMs; }
    public String getApiStatus() { return apiStatus; }

    // ── Setters ──────────────────────────────────────────────────
    public void setWebsiteExists(boolean websiteExists) { this.websiteExists = websiteExists; }
    public void setCareersPageExists(boolean careersPageExists) { this.careersPageExists = careersPageExists; }
    public void setJobMatchWebsite(boolean jobMatchWebsite) { this.jobMatchWebsite = jobMatchWebsite; }
    public void setJobMatchSearch(boolean jobMatchSearch) { this.jobMatchSearch = jobMatchSearch; }
    public void setLocationMatch(boolean locationMatch) { this.locationMatch = locationMatch; }
    public void setWebsiteScore(double websiteScore) { this.websiteScore = websiteScore; }
    public void setSearchScore(double searchScore) { this.searchScore = searchScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
    public void setTopSearchLinks(List<String> topSearchLinks) { this.topSearchLinks = topSearchLinks; }
    public void setMessage(String message) { this.message = message; }
    public void setVerificationTimeMs(long verificationTimeMs) { this.verificationTimeMs = verificationTimeMs; }
    public void setApiStatus(String apiStatus) { this.apiStatus = apiStatus; }

    @Override
    public String toString() {
        return "HybridVerificationResponse{" +
                "websiteExists=" + websiteExists +
                ", careersPageExists=" + careersPageExists +
                ", jobMatchWebsite=" + jobMatchWebsite +
                ", jobMatchSearch=" + jobMatchSearch +
                ", locationMatch=" + locationMatch +
                ", websiteScore=" + websiteScore +
                ", searchScore=" + searchScore +
                ", finalScore=" + finalScore +
                ", apiStatus='" + apiStatus + '\'' +
                ", verificationTimeMs=" + verificationTimeMs +
                ", message='" + message + '\'' +
                '}';
    }
}
