package com.example.random_major.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Enhanced JobResult with company verification and domain validation
 * Includes post-processing adjustments to the model score
 */
public class EnhancedJobResult {
    
    @JsonProperty("prediction")
    private String prediction; // FAKE or REAL
    
    @JsonProperty("confidenceScore")
    private double confidenceScore; // Adjusted score (0-1)
    
    @JsonProperty("baseModelScore")
    private double baseModelScore; // Original model score before adjustment
    
    @JsonProperty("adjustmentFactor")
    private double adjustmentFactor; // How much the score was adjusted
    
    @JsonProperty("companyVerification")
    private CompanyVerificationResponse companyVerification;
    
    @JsonProperty("domainValidation")
    private DomainValidationResponse domainValidation;

    @JsonProperty("hybridVerification")
    private HybridVerificationResponse hybridVerification;
    
    @JsonProperty("lime_explanations")
    private List<LimeExplanation> limeExplanations;
    
    @JsonProperty("externalValidationInfluence")
    private String externalValidationInfluence;
    
    @JsonProperty("cache_status")
    private String cacheStatus;
    
    @JsonProperty("explanation_latency_ms")
    private long explanationLatencyMs;
    
    @JsonProperty("gcs_url")
    private String gcsUrl;

    @JsonProperty("redFlagScore")
    private double redFlagScore; // Score from red flag detection (0-1)

    @JsonProperty("redFlagsDetected")
    private List<RedFlag> redFlagsDetected; // List of detected red flags

    @JsonProperty("extractedCompanyName")
    private String extractedCompanyName; // Auto-extracted company name from text

    @JsonProperty("extractedUrl")
    private String extractedUrl; // Auto-extracted URL from text

    @JsonProperty("extractedDomain")
    private String extractedDomain; // Auto-extracted domain from text

    public EnhancedJobResult() {}

    public EnhancedJobResult(String prediction, double confidenceScore, double baseModelScore) {
        this.prediction = prediction;
        this.confidenceScore = confidenceScore;
        this.baseModelScore = baseModelScore;
        this.adjustmentFactor = 0.0;
    }

    // Getters and Setters
    public String getPrediction() {
        return prediction;
    }

    public void setPrediction(String prediction) {
        this.prediction = prediction;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public double getBaseModelScore() {
        return baseModelScore;
    }

    public void setBaseModelScore(double baseModelScore) {
        this.baseModelScore = baseModelScore;
    }

    public double getAdjustmentFactor() {
        return adjustmentFactor;
    }

    public void setAdjustmentFactor(double adjustmentFactor) {
        this.adjustmentFactor = adjustmentFactor;
    }

    public CompanyVerificationResponse getCompanyVerification() {
        return companyVerification;
    }

    public void setCompanyVerification(CompanyVerificationResponse companyVerification) {
        this.companyVerification = companyVerification;
    }

    public DomainValidationResponse getDomainValidation() {
        return domainValidation;
    }

    public void setDomainValidation(DomainValidationResponse domainValidation) {
        this.domainValidation = domainValidation;
    }

    public HybridVerificationResponse getHybridVerification() {
        return hybridVerification;
    }

    public void setHybridVerification(HybridVerificationResponse hybridVerification) {
        this.hybridVerification = hybridVerification;
    }

    public List<LimeExplanation> getLimeExplanations() {
        return limeExplanations;
    }

    public void setLimeExplanations(List<LimeExplanation> limeExplanations) {
        this.limeExplanations = limeExplanations;
    }

    public String getExternalValidationInfluence() {
        return externalValidationInfluence;
    }

    public void setExternalValidationInfluence(String externalValidationInfluence) {
        this.externalValidationInfluence = externalValidationInfluence;
    }

    public String getCacheStatus() {
        return cacheStatus;
    }

    public void setCacheStatus(String cacheStatus) {
        this.cacheStatus = cacheStatus;
    }

    public long getExplanationLatencyMs() {
        return explanationLatencyMs;
    }

    public void setExplanationLatencyMs(long explanationLatencyMs) {
        this.explanationLatencyMs = explanationLatencyMs;
    }

    public String getGcsUrl() {
        return gcsUrl;
    }

    public void setGcsUrl(String gcsUrl) {
        this.gcsUrl = gcsUrl;
    }

    public double getRedFlagScore() {
        return redFlagScore;
    }

    public void setRedFlagScore(double redFlagScore) {
        this.redFlagScore = redFlagScore;
    }

    public List<RedFlag> getRedFlagsDetected() {
        return redFlagsDetected;
    }

    public void setRedFlagsDetected(List<RedFlag> redFlagsDetected) {
        this.redFlagsDetected = redFlagsDetected;
    }

    public String getExtractedCompanyName() {
        return extractedCompanyName;
    }

    public void setExtractedCompanyName(String extractedCompanyName) {
        this.extractedCompanyName = extractedCompanyName;
    }

    public String getExtractedUrl() {
        return extractedUrl;
    }

    public void setExtractedUrl(String extractedUrl) {
        this.extractedUrl = extractedUrl;
    }

    public String getExtractedDomain() {
        return extractedDomain;
    }

    public void setExtractedDomain(String extractedDomain) {
        this.extractedDomain = extractedDomain;
    }
}

