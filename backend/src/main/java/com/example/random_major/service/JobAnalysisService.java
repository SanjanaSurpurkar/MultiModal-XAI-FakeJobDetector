package com.example.random_major.service;

import java.io.File;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.random_major.entity.ExtractedData;
import com.example.random_major.entity.JobRecord;
import com.example.random_major.model.CompanyVerificationResponse;
import com.example.random_major.model.DomainValidationResponse;
import com.example.random_major.model.EnhancedJobResult;
import com.example.random_major.model.HybridVerificationResponse;
import com.example.random_major.model.JobResult;
import com.example.random_major.repository.JobRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class JobAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(JobAnalysisService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private ModelEvaluatorService modelEvaluatorService;
    @Autowired private JobRecordRepository jobRecordRepository;
    @Autowired private JobResultService jobResultService;
    @Autowired private OcrService ocrService;
    @Autowired private TextExtractService textExtractService;
    @Autowired private AudioService audioService;
    @Autowired private LimeService limeService;
    @Autowired private CompanyVerificationService companyVerificationService;
    @Autowired private DomainValidationService domainValidationService;
    @Autowired private PredictionService predictionService;
    @Autowired private RedFlagDetectionService redFlagDetectionService;
    @Autowired private EntityExtractionService entityExtractionService;
    @Autowired private HybridJobVerificationService hybridJobVerificationService;

    @Value("${lime.num-features:10}")
    private int defaultNumFeatures;

    @Value("${lime.output-format:json}")
    private String defaultOutputFormat;

    // ---------------------------------------------------
    // ✅ TEXT ANALYSIS (PMML + LIME)
    // ---------------------------------------------------
    public JobResult analyzePlainText(String jobText) {
        return analyzePlainText(jobText, defaultNumFeatures, defaultOutputFormat, null);
    }

    public JobResult analyzePlainText(String jobText, int numFeatures, String outputFormat, String userId) {
        try {
            // ── Step 0: VALIDATE JOB POST INPUT ───────────────────────
            if (!JobPostValidator.isValidJobPost(jobText)) {
                log.warn("❌ Invalid job post input detected - returning FAKE with 100% confidence");
                return new JobResult("FAKE", 1.0, "Invalid job post input");
            }

            // ── Step 1: PMML Prediction ──────────────────────────
            Map<String, Object> result = modelEvaluatorService.predict(jobText);

            double probabilityFake =
                    ((Number) result.getOrDefault("probability_fake", 0.0)).doubleValue();
            double confidence = probabilityFake * 100;
            String finalLabel = probabilityFake >= 0.5 ? "FAKE" : "REAL";

            // ── Step 2: LIME Explanation ─────────────────────────
            LimeService.LimeResult limeResult;
            try {
                limeResult = limeService.explain(jobText, numFeatures, outputFormat, userId);
                log.info("LIME returned {} features, status={}, latency={}ms",
                        limeResult.explanations.size(), limeResult.cacheStatus, limeResult.latencyMs);
            } catch (Exception e) {
                log.error("LIME call failed unexpectedly: {}", e.getMessage());
                limeResult = LimeService.LimeResult.error(0);
            }

            // ── Step 3: Serialize explanation for MongoDB ─────────
            String explanationJson = "[]";
            try {
                explanationJson = objectMapper.writeValueAsString(limeResult.explanations);
            } catch (JsonProcessingException ignored) {}

            // ── Step 4: Save to MongoDB ───────────────────────────
            JobRecord record = new JobRecord(jobText, finalLabel, confidence, explanationJson, userId);
            jobRecordRepository.save(record);

            // ── Step 5: Build enriched JobResult ──────────────────
            JobResult jobResult = new JobResult(finalLabel, probabilityFake, explanationJson);
            jobResult.setLimeExplanations(limeResult.explanations);
            jobResult.setCacheStatus(limeResult.cacheStatus);
            jobResult.setExplanationLatencyMs(limeResult.latencyMs);
            jobResult.setGcsUrl(limeResult.gcsUrl);
            jobResult.setJobText(jobText); // ✅ Pass text back for UI depth re-fetch

            return jobResult;

        } catch (Exception e) {
            log.error("Analysis failed: {}", e.getMessage(), e);
            return new JobResult("error", 0.0, "Model evaluation failed");
        }
    }

    // ---------------------------------------------------
    // ✅ FILE ANALYSIS (legacy - now delegates to unified pipeline)
    // ---------------------------------------------------
    /**
     * DEPRECATED: This method is maintained for backward compatibility only.
     * Please use analyzeFileWithUnifiedPipeline() instead.
     * 
     * This method extracts text from a file and delegates to the unified pipeline,
     * ensuring consistent processing regardless of input type.
     * 
     * @param file The uploaded file
     * @param fileType File type: audio, image, or document
     * @param userId User ID (optional)
     * @return EnhancedJobResult with all validations performed
     * @deprecated Use {@link #analyzeFileWithUnifiedPipeline(File, String, String, String, String, String)} instead
     */
    @Deprecated
    public EnhancedJobResult analyzeFromFile(File file, String fileType, String userId) {
        log.warn("⚠️  analyzeFromFile() is DEPRECATED - consider using analyzeFileWithUnifiedPipeline() instead");
        return analyzeFileWithUnifiedPipeline(file, fileType, null, null, null, userId);
    }

    // ---------------------------------------------------
    // ✅ ENTITY EXTRACTION (Company Name, URL, Domain)
    // ---------------------------------------------------
    /**
     * Extracts structured information from job text using EntityExtractionService
     * 
     * This method should be called after text extraction (OCR/transcription)
     * to auto-fill company name, URL, and domain fields
     * 
     * @param jobText The clean extracted text
     * @return ExtractedData with companyName, url, domain
     */
    public ExtractedData extractEntities(String jobText) {
        try {
            log.info("🔍 Extracting entities from job text...");
            ExtractedData extractedData = entityExtractionService.extractFromText(jobText);
            
            log.info("✅ Entity extraction completed - Company: '{}', URL: '{}', Domain: '{}'",
                    extractedData.getCompanyName(), 
                    extractedData.getUrl(), 
                    extractedData.getDomain());
            
            return extractedData;
        } catch (Exception e) {
            log.error("Entity extraction failed: {}", e.getMessage(), e);
            return new ExtractedData(null, null, null);
        }
    }

    // ---------------------------------------------------
    // ✅ ENHANCED TEXT ANALYSIS (with company verification & domain validation)
    // ---------------------------------------------------
    /**
     * Enhanced analysis with company verification and post-processing
     * Delegates to unified pipeline with user-provided company info
     * 
     * @param jobText The job posting text
     * @param companyName The company name (optional - will be auto-detected if empty)
     * @param jobPostingUrl The job posting URL (optional)
     * @param contactEmail The contact email (optional)
     * @param userId The user ID (optional)
     * @return EnhancedJobResult with verification and adjusted prediction
     */
    public EnhancedJobResult analyzeWithCompanyVerification(
            String jobText,
            String companyName,
            String jobPostingUrl,
            String contactEmail,
            String userId
    ) {
        log.info("🔍 Starting enhanced analysis with company verification (TEXT input)...");
        return analyzeWithUnifiedPipeline(
            jobText, 
            companyName, 
            jobPostingUrl, 
            contactEmail, 
            userId, 
            "TEXT"  // Explicitly TEXT input type
        );
    }

    // ---------------------------------------------------
    // ✅ AUTO-EXTRACTION HELPERS
    // ---------------------------------------------------

    /**
     * ALWAYS extract company name from text with fallback logic
     * Priority: regex patterns → first capitalized entity
     */
    private String autoExtractCompanyName(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String lowerText = text.toLowerCase();

        // Priority 1: Look for explicit patterns (using same patterns as EntityExtractionService)
        java.util.regex.Pattern[] patterns = {
            java.util.regex.Pattern.compile("(?i)(?:company|at)\\s*:\\s*([A-Za-z][A-Za-z0-9\\s&.-]+)"),
            java.util.regex.Pattern.compile("(?i)([A-Za-z][A-Za-z0-9\\s&.-]+)\\s+is\\s+hiring"),
            java.util.regex.Pattern.compile("(?i)join\\s+([A-Za-z0-9&.-]+(?:\\s+[A-Za-z0-9&.-]+)?)(?:\\s+(?:is|are|has|have|pvt|ltd|inc|corp|llc|limited|for|and)|[.,]|$)"),
            java.util.regex.Pattern.compile("(?i)work\\s+(?:at|for)\\s+([A-Za-z][A-Za-z0-9\\s&.-]+)")
        };

        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String extracted = matcher.group(1).trim();
                if (extracted.length() >= 2 && extracted.length() <= 50) {
                    log.info("✅ Company extracted via pattern: '{}'", extracted);
                    return extracted;
                }
            }
        }

        // No regex patterns matched - return null (don't use unreliable capitalized fallback)
        log.warn("⚠️  No company name could be extracted from text using patterns");
        return null;
    }

    /**
     * Auto-generate domain from company name if URL is missing
     */
    private String autoGenerateDomain(String companyName) {
        if (companyName == null || companyName.trim().isEmpty()) {
            return null;
        }

        String domain = companyName.toLowerCase()
                .replaceAll("\\s+", "")  // Remove spaces
                .replaceAll("[^a-z0-9]", "")  // Remove special chars
                + ".com";

        log.info("🔧 Auto-generated domain: '{}' from company '{}'", domain, companyName);
        return domain;
    }
    /**
     * UNIFIED PIPELINE that processes all input types through a consistent flow:
     * 1. Extract entities from text (MANDATORY for all)
     * 2. Auto-fill company name if user didn't provide one
     * 3. Run company verification
     * 4. Run domain validation
     * 5. Get ML prediction with LIME explanation
     * 6. Apply red flag detection and post-processing
     * 
     * @param jobText The extracted/input job text
     * @param userCompanyName Company name entered by user (optional)
     * @param userJobPostingUrl URL entered by user (optional)
     * @param userContactEmail Contact email entered by user (optional)
     * @param userId User ID (optional)
     * @param inputType Type of input (TEXT, IMAGE, AUDIO, DOCUMENT)
     * @return EnhancedJobResult with all validations and extracted data
     */
    public EnhancedJobResult analyzeWithUnifiedPipeline(
            String jobText,
            String userCompanyName,
            String userJobPostingUrl,
            String userContactEmail,
            String userId,
            String inputType
    ) {
        return processJob(
            jobText,
            userCompanyName,
            null,
            userJobPostingUrl,
            userContactEmail,
            null,
            inputType,
            userId
        );
    }

    /**
     * Main unified processing method that uses the same flow for text, file, image, and audio.
     *
     * The method always executes all verification stages, regardless of input type.
     *
     * @param finalText The normalized extracted or manual text
     * @param companyName Optional company name provided by user
     * @param jobTitle Optional job title provided by user
     * @param url Optional job posting URL provided by user
     * @param email Optional contact email provided by user
     * @param location Optional job location provided by user
     * @return EnhancedJobResult with full hybrid verification and final scoring
     */
    public EnhancedJobResult processJob(
            String finalText,
            String companyName,
            String jobTitle,
            String url,
            String email,
            String location
    ) {
        return processJob(finalText, companyName, jobTitle, url, email, location, "TEXT", null);
    }

    public EnhancedJobResult processJob(
            String finalText,
            String companyName,
            String jobTitle,
            String url,
            String email,
            String location,
            String inputType,
            String userId
    ) {
        try {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🔄 UNIFIED PROCESS JOB: Starting analysis");
            log.info("═══════════════════════════════════════════════════════════");

            String normalizedInputType = inputType != null ? inputType.toUpperCase().trim() : "TEXT";
            String text = finalText != null ? finalText.trim() : "";

            if (text.isEmpty()) {
                log.error("❌ finalText is null or empty - cannot proceed");
                return new EnhancedJobResult("error", 0.0, 0.0);
            }

            log.info("📋 Input Type: {}", normalizedInputType);
            log.info("📥 Raw input length: {} chars", text.length());
            log.info("   User provided companyName: {}", companyName != null ? companyName : "NONE");
            log.info("   User provided jobTitle: {}", jobTitle != null ? jobTitle : "NONE");
            log.info("   User provided url: {}", url != null ? url : "NONE");
            log.info("   User provided email: {}", email != null ? email : "NONE");
            log.info("   User provided location: {}", location != null ? location : "NONE");

            if (text.length() < 20) {
                log.error("❌ finalText is too short ({} chars) - minimum 20 chars required", text.length());
                return new EnhancedJobResult("error", 0.0, 0.0);
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 1: ALWAYS EXTRACT ENTITIES FROM TEXT
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 1: Extracting entities from text (MANDATORY)...");
            ExtractedData extractedData = extractEntities(text);
            if (extractedData == null) {
                extractedData = new ExtractedData(null, null, null, null);
            }

            log.info("✅ Entity extraction COMPLETED");
            log.info("   - Company: '{}' (from service)", extractedData.getCompanyName() != null ? extractedData.getCompanyName() : "NULL");
            log.info("   - URL: '{}' (from service)", extractedData.getUrl() != null ? extractedData.getUrl() : "NULL");
            log.info("   - Domain: '{}' (from service)", extractedData.getDomain() != null ? extractedData.getDomain() : "NULL");
            log.info("   - Email: '{}' (from service)", extractedData.getEmail() != null ? extractedData.getEmail() : "NULL");

            // ═══════════════════════════════════════════════════════════
            // STEP 2: AUTO-EXTRACT COMPANY NAME (MANDATORY)
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 2: Auto-extracting company name from text...");
            String extractedCompany = autoExtractCompanyName(text);
            log.info("✅ Company auto-extraction: '{}'", extractedCompany != null ? extractedCompany : "FAILED");

            // ═══════════════════════════════════════════════════════════
            // STEP 3: DETERMINE FINAL COMPANY NAME FOR VALIDATION
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 3: Determining company name for validation...");
            String companyNameForValidation = null;
            String companySource = "SOURCE_UNKNOWN";

            // Priority 1: User-provided company name
            if (companyName != null && !companyName.trim().isEmpty()) {
                companyNameForValidation = companyName.trim();
                companySource = "SOURCE_USER";
                log.info("✅ Using USER-PROVIDED company: '{}'", companyNameForValidation);
            }
            // Priority 2: Auto-extracted company name
            else if (extractedCompany != null && !extractedCompany.trim().isEmpty()) {
                companyNameForValidation = extractedCompany.trim();
                companySource = "SOURCE_AUTO_EXTRACTED";
                log.info("✅ Using AUTO-EXTRACTED company: '{}'", companyNameForValidation);
            }
            // Priority 3: Extracted from service (fallback)
            else if (extractedData.getCompanyName() != null && !extractedData.getCompanyName().trim().isEmpty()) {
                companyNameForValidation = extractedData.getCompanyName().trim();
                companySource = "SOURCE_SERVICE_EXTRACTED";
                log.info("✅ Using SERVICE-EXTRACTED company: '{}'", companyNameForValidation);
            }
            // Priority 4: None available - but we still continue
            else {
                log.warn("⚠️  No company name available - will run verification with null (may fail)");
                companyNameForValidation = null;
                companySource = "SOURCE_NONE";
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 4: DETERMINE URL AND EMAIL FOR VALIDATION
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 4: Determining URL and email for validation...");
            String urlForValidation = url != null && !url.trim().isEmpty()
                    ? url.trim()
                    : extractedData.getUrl();
            String urlSource = url != null ? "SOURCE_USER" : "SOURCE_EXTRACTED";

            String emailForValidation = email != null && !email.trim().isEmpty()
                    ? email.trim()
                    : extractedData.getEmail();
            String emailSource = email != null ? "SOURCE_USER" : "SOURCE_EXTRACTED";

            log.info("   Company: {} [{}]", companyNameForValidation, companySource);
            log.info("   URL: {} [{}]", urlForValidation, urlSource);
            log.info("   Email: {} [{}]", emailForValidation, emailSource);

            // ═══════════════════════════════════════════════════════════
            // STEP 5: AUTO-GENERATE DOMAIN IF URL MISSING
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 5: Auto-generating domain if needed...");
            String generatedDomain = null;
            String finalDomainUsed = null;
            String domainSource = "SOURCE_UNKNOWN";

            if (urlForValidation == null || urlForValidation.trim().isEmpty()) {
                if (companyNameForValidation != null) {
                    generatedDomain = autoGenerateDomain(companyNameForValidation);
                    finalDomainUsed = generatedDomain;
                    domainSource = "SOURCE_GENERATED";
                    log.info("✅ Generated domain: '{}' (no URL provided)", generatedDomain);
                } else {
                    log.warn("⚠️  Cannot generate domain - no company name available");
                }
            } else {
                // Extract domain from URL
                String extractedFromUrl = extractDomainFromUrl(urlForValidation);
                if (extractedFromUrl != null) {
                    finalDomainUsed = extractedFromUrl;
                    domainSource = "SOURCE_URL";
                    log.info("✅ Using domain from URL: '{}'", finalDomainUsed);
                } else if (companyNameForValidation != null) {
                    generatedDomain = autoGenerateDomain(companyNameForValidation);
                    finalDomainUsed = generatedDomain;
                    domainSource = "SOURCE_GENERATED_FALLBACK";
                    log.info("✅ Generated domain (URL extraction failed): '{}'", generatedDomain);
                }
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 6: JOB POST VALIDATION
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 6: Validating job post content...");
            if (!JobPostValidator.isValidJobPost(text)) {
                log.warn("❌ Invalid job post input detected - returning FAKE with 100% confidence");
                EnhancedJobResult invalidResult = new EnhancedJobResult("FAKE", 1.0, 1.0);
                invalidResult.setExternalValidationInfluence("Invalid job post input - does not meet validation criteria");
                return invalidResult;
            }
            log.info("✅ Job post validation passed");

            // ═══════════════════════════════════════════════════════════
            // STEP 7: ML PREDICTION
            // ═══════════════════════════════════════════════════════════
            log.info("📊 STEP 7: Running ML model prediction...");
            Map<String, Object> result = modelEvaluatorService.predict(text);
            double mlScore = ((Number) result.getOrDefault("probability_fake", 0.0)).doubleValue();
            log.info("✅ ML prediction completed - probability_fake={}", mlScore);

            // ═══════════════════════════════════════════════════════════
            // STEP 8: RED FLAG DETECTION
            // ═══════════════════════════════════════════════════════════
            log.info("🚩 STEP 8: Running red flag detection...");
            java.util.List<com.example.random_major.model.RedFlag> redFlags =
                    redFlagDetectionService.detectRedFlags(text);
            double redFlagScore = redFlagDetectionService.calculateRedFlagScore(redFlags);
            log.info("✅ Red flag detection completed - redFlagScore={}", redFlagScore);

            // ═══════════════════════════════════════════════════════════
            // STEP 9: ALWAYS RUN COMPANY VERIFICATION
            // ═══════════════════════════════════════════════════════════
            log.info("🏢 STEP 9: Running company verification (ALWAYS)...");
            CompanyVerificationResponse companyVerification = null;

            if (companyNameForValidation != null && !companyNameForValidation.isEmpty()) {
                log.info("   Verifying company: '{}'", companyNameForValidation);
                companyVerification = companyVerificationService.verifyCompany(companyNameForValidation);
            } else {
                log.warn("   ⚠️  No company name to verify - using neutral response");
                companyVerification = new CompanyVerificationResponse(false, "UNKNOWN", null,
                        "No company name available for verification");
            }

            log.info("✅ Company verification completed - status={}, exists={}, website={}",
                    companyVerification.getStatus(), companyVerification.isExists(), companyVerification.getWebsite());

            // ═══════════════════════════════════════════════════════════
            // STEP 10: DETERMINE DOMAIN FOR VALIDATION
            // ═══════════════════════════════════════════════════════════
            log.info("🔗 STEP 10: Determining domain for validation...");
            String domainForValidation = null;

            // Priority 1: Verified company website
            if (companyVerification.isExists() && companyVerification.getWebsite() != null) {
                domainForValidation = companyVerification.getWebsite();
                log.info("   Using verified company domain: '{}'", domainForValidation);
            }
            // Priority 2: Generated/auto domain
            else if (finalDomainUsed != null) {
                domainForValidation = finalDomainUsed;
                log.info("   Using generated/extracted domain: '{}' [{}]", domainForValidation, domainSource);
            }
            // Priority 3: Extracted domain from email
            else if (emailForValidation != null) {
                domainForValidation = extractDomainFromEmail(emailForValidation);
                log.info("   Using domain from email: '{}'", domainForValidation);
            }
            else {
                log.warn("   ⚠️  No domain available for validation");
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 11: ALWAYS RUN DOMAIN VALIDATION
            // ═══════════════════════════════════════════════════════════
            log.info("🔗 STEP 11: Running domain validation (ALWAYS)...");
            DomainValidationResponse domainValidation = null;

            if (domainForValidation != null || urlForValidation != null || emailForValidation != null) {
                log.info("   Validation inputs - Domain: {}, URL: {}, Email: {}",
                    domainForValidation, urlForValidation, emailForValidation);
                domainValidation = domainValidationService.validateDomain(
                    domainForValidation,
                    urlForValidation,
                    emailForValidation
                );
                log.info("✅ Domain validation completed - match={}, riskScore={}",
                    domainValidation.isMatch(), domainValidation.getRiskScore());
            } else {
                log.warn("   ⚠️  No validation inputs available - using neutral response");
                domainValidation = new DomainValidationResponse(false, 0.5, null, domainForValidation,
                        "No domain, URL, or email available for validation");
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 12: FORCE REAL-TIME HYBRID VERIFICATION (ALWAYS)
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 12: Running hybrid real-time verification (ALWAYS)...");
            HybridVerificationResponse hybridVerification = null;

            String jobTitleForVerification = jobTitle != null && !jobTitle.trim().isEmpty()
                    ? jobTitle.trim() : extractJobTitle(text);
            String locationForVerification = location != null && !location.trim().isEmpty()
                    ? location.trim() : null;

            if (companyNameForValidation != null && !companyNameForValidation.isEmpty()) {
                log.info("   Running hybrid verification for company: '{}'", companyNameForValidation);
                hybridVerification = hybridJobVerificationService.verify(
                        companyNameForValidation,
                        jobTitleForVerification,
                        locationForVerification,
                        text,
                        finalDomainUsed
                );
            } else {
                log.warn("   ⚠️  No company name available - using neutral hybrid response");
                hybridVerification = HybridVerificationResponse.neutral("No company name available for hybrid verification");
            }

            double realTimeScore = hybridVerification != null ? hybridVerification.getFinalScore() : 0.5;
            log.info("✅ Hybrid verification completed - realTimeScore={}", realTimeScore);

            // ═══════════════════════════════════════════════════════════
            // DEBUG LOGGING (MANDATORY)
            // ═══════════════════════════════════════════════════════════
            log.info("📊 DEBUG LOGGING:");
            log.info("   extractedCompany: {}", extractedCompany);
            log.info("   generatedDomain: {}", generatedDomain);
            log.info("   inputUrl: {}", url);
            log.info("   finalDomainUsed: {}", finalDomainUsed);
            log.info("   realTimeScore: {}", realTimeScore);

            double domainRisk = calculateDomainRisk(domainValidation);
            double companyRisk = calculateCompanyRisk(companyVerification);

            double finalScore = Math.max(0.0, Math.min(1.0,
                    (mlScore * 0.35) +
                    (redFlagScore * 0.25) +
                    (domainRisk * 0.15) +
                    (companyRisk * 0.10) +
                    (realTimeScore * 0.15)
            ));

            boolean hasNonCorporateChannel = containsNonCorporateChannel(text);
            boolean hasEquipmentCheckScam = redFlags.stream()
                    .anyMatch(flag -> "EQUIPMENT_CHECK_SCAM".equalsIgnoreCase(flag.getType()));
            boolean hasDomainMismatchKnownCompany = companyVerification.isExists()
                    && domainValidation != null
                    && !domainValidation.isMatch();

            StringBuilder overrideNotes = new StringBuilder();
            if (hasDomainMismatchKnownCompany) {
                finalScore = Math.max(finalScore, 0.95);
                overrideNotes.append("Domain mismatch for known company detected. ");
            }
            if (hasNonCorporateChannel) {
                finalScore = Math.max(finalScore, 0.95);
                overrideNotes.append("Non-corporate channel mention detected (Telegram/WhatsApp). ");
            }
            if (hasEquipmentCheckScam) {
                finalScore = Math.max(finalScore, 0.95);
                overrideNotes.append("Equipment check scam red flag detected. ");
            }

            String externalValidationInfluence = "Hybrid and validation score combined. ";
            if (overrideNotes.length() > 0) {
                externalValidationInfluence += overrideNotes.toString();
            }

            String finalPrediction = finalScore >= 0.5 ? "FAKE" : "REAL";

            LimeService.LimeResult limeResult;
            try {
                limeResult = limeService.explain(text, defaultNumFeatures, defaultOutputFormat, userId);
            } catch (Exception e) {
                log.error("⚠️ LIME explanation failed: {}", e.getMessage());
                limeResult = LimeService.LimeResult.error(0);
            }

            EnhancedJobResult enhancedResult = new EnhancedJobResult(finalPrediction, finalScore, mlScore);
            enhancedResult.setAdjustmentFactor(finalScore - mlScore);
            enhancedResult.setCompanyVerification(companyVerification);
            enhancedResult.setDomainValidation(domainValidation);
            enhancedResult.setHybridVerification(hybridVerification);
            enhancedResult.setLimeExplanations(limeResult.explanations);
            enhancedResult.setRedFlagScore(redFlagScore);
            enhancedResult.setRedFlagsDetected(redFlags);
            enhancedResult.setExternalValidationInfluence(externalValidationInfluence);
            enhancedResult.setCacheStatus(limeResult.cacheStatus);
            enhancedResult.setExplanationLatencyMs(limeResult.latencyMs);
            enhancedResult.setGcsUrl(limeResult.gcsUrl);
            enhancedResult.setExtractedCompanyName(extractedData.getCompanyName());
            enhancedResult.setExtractedUrl(extractedData.getUrl());
            enhancedResult.setExtractedDomain(extractedData.getDomain());

            log.info("📌 processJob summary: inputType={}, finalTextLen={}, extractedCompany={}, generatedDomain={}, inputUrl={}, finalDomainUsed={}, realTimeScore={}, finalScore={}",
                    normalizedInputType,
                    text.length(),
                    extractedCompany != null ? extractedCompany : "NONE",
                    generatedDomain != null ? generatedDomain : "NONE",
                    url != null ? url : "NONE",
                    finalDomainUsed != null ? finalDomainUsed : "NONE",
                    realTimeScore,
                    finalScore
            );

            try {
                jobResultService.saveEnhancedJobResult(text,
                        companyNameForValidation,
                        enhancedResult,
                        normalizedInputType,
                        userId);
            } catch (Exception e) {
                log.warn("⚠️ Could not save result to database: {}", e.getMessage());
            }

            return enhancedResult;

        } catch (Exception e) {
            log.error("❌ processJob failed: {}", e.getMessage(), e);
            return new EnhancedJobResult("error", 0.0, 0.0);
        }
    }

    private double calculateDomainRisk(DomainValidationResponse domainValidation) {
        if (domainValidation == null) {
            return 0.5;
        }
        if (!domainValidation.isMatch()) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - domainValidation.getRiskScore()));
    }

    private double calculateCompanyRisk(CompanyVerificationResponse companyVerification) {
        if (companyVerification == null) {
            return 0.5;
        }
        if (!companyVerification.isExists()) {
            return 1.0;
        }
        String status = companyVerification.getStatus();
        if ("ACTIVE".equalsIgnoreCase(status)) {
            return 0.0;
        }
        if ("INACTIVE".equalsIgnoreCase(status)) {
            return 0.8;
        }
        return 0.5;
    }

    private String extractDomainFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            host = host.toLowerCase().replaceFirst("^www\\.", "");
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
            return host;
        } catch (Exception e) {
            log.debug("Could not derive domain from URL {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String extractDomainFromEmail(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        return email.substring(email.indexOf("@") + 1).toLowerCase();
    }

    private boolean containsNonCorporateChannel(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        if (lower.contains("telegram") || lower.contains("whatsapp")) {
            return !hasNegationNear(lower, "telegram") && !hasNegationNear(lower, "whatsapp");
        }
        return false;
    }

    private boolean hasNegationNear(String text, String keyword) {
        int index = text.indexOf(keyword.toLowerCase());
        if (index < 0) {
            return false;
        }
        int windowStart = Math.max(0, index - 40);
        int windowEnd = Math.min(text.length(), index + keyword.length() + 40);
        String window = text.substring(windowStart, windowEnd);
        return window.matches("(?i).*\\b(no|not|never|don't|do not|dont)\\b.*");
    }
            
    // ✅ FILE ANALYSIS WITH UNIFIED PIPELINE
    // ---------------------------------------------------
    /**
     * Combined method that extracts file text and runs the unified pipeline
     * This ensures entity extraction and validation for IMAGE/AUDIO/DOCUMENT inputs
     * 
     * @param file The uploaded file
     * @param fileType File type: audio, image, or document
     * @param userCompanyName User-provided company name (optional)
     * @param userJobPostingUrl User-provided job posting URL (optional)
     * @param userContactEmail User-provided contact email (optional)
     * @param userId User ID (optional)
     * @return EnhancedJobResult with all validations performed
     */
    public EnhancedJobResult analyzeFileWithUnifiedPipeline(
            File file,
            String fileType,
            String userCompanyName,
            String userJobPostingUrl,
            String userContactEmail,
            String userId
    ) {
        try {
            log.info("╔════════════════════════════════════════════════════════╗");
            log.info("║ FILE ANALYSIS WITH UNIFIED PIPELINE                   ║");
            log.info("╚════════════════════════════════════════════════════════╝");
            
            // ──────────────────────────────────────────────────────────
            // STEP 1: Validate file and file type
            // ──────────────────────────────────────────────────────────
            log.info("📋 PRE-PROCESSING: Validating file and input type...");
            
            if (file == null) {
                log.error("❌ File is null");
                return new EnhancedJobResult("error", 0.0, 0.0);
            }
            
            if (!file.exists()) {
                log.error("❌ File does not exist: {}", file.getAbsolutePath());
                return new EnhancedJobResult("error", 0.0, 0.0);
            }
            
            // Normalize file type
            String normalizedFileType = fileType != null ? fileType.toLowerCase().trim() : "text";
            log.info("   File Type: {} (normalized)", normalizedFileType);
            log.info("   File Size: {} bytes", file.length());
            
            if (file.length() == 0) {
                log.error("❌ File is empty (0 bytes)");
                return new EnhancedJobResult("error", 0.0, 0.0);
            }

            // ──────────────────────────────────────────────────────────
            // STEP 2: Extract text from file (input normalization)
            // ──────────────────────────────────────────────────────────
            log.info("📄 STEP 1: Extracting text from {} file...", normalizedFileType);
            String extractedText = null;

            if (normalizedFileType.equalsIgnoreCase("audio")) {
                log.info("   Using audio transcription service...");
                extractedText = audioService.transcribeAudio(file);
            } else if (normalizedFileType.equalsIgnoreCase("image")) {
                log.info("   Using OCR service...");
                extractedText = ocrService.extractTextFromImage(file);
            } else if (normalizedFileType.equalsIgnoreCase("document")) {
                log.info("   Using document text extraction service...");
                extractedText = textExtractService.extractText(file);
            } else {
                log.error("❌ Unsupported file type: {}", normalizedFileType);
                return new EnhancedJobResult("error", 0.0, 0.0);
            }

            // Validate extracted text
            if (extractedText == null || extractedText.trim().isEmpty()) {
                log.error("❌ Unable to extract readable text from {} file", normalizedFileType);
                return new EnhancedJobResult("error", 0.0, 0.0);
            }

            extractedText = extractedText.trim();
            
            if (extractedText.length() < 20) {
                log.error("❌ Extracted text is too short ({} chars) - minimum 20 chars required", extractedText.length());
                return new EnhancedJobResult("error", 0.0, 0.0);
            }

            log.info("✅ Text extraction successful ({} characters)", extractedText.length());

            // ──────────────────────────────────────────────────────────
            // STEP 3: Call unified pipeline with extracted text
            // ──────────────────────────────────────────────────────────
            log.info("🔄 STEP 2: Running UNIFIED PIPELINE with extracted text from {} file...", 
                normalizedFileType.toUpperCase());
            
            EnhancedJobResult result = analyzeWithUnifiedPipeline(
                extractedText,
                userCompanyName,
                userJobPostingUrl,
                userContactEmail,
                userId,
                normalizedFileType.toUpperCase()
            );

            log.info("✅ File analysis completed successfully");
            return result;

        } catch (Exception e) {
            log.error("❌ FILE ANALYSIS FAILED: {}", e.getMessage(), e);
            EnhancedJobResult errorResult = new EnhancedJobResult("error", 0.0, 0.0);
            return errorResult;
        }
    }

    // ---------------------------------------------------
    // ✅ HELPER METHODS
    // ---------------------------------------------------

    /**
     * Extracts job title keywords from text for hybrid verification
     * 
     * Looks for common job title patterns or uses first few words
     * 
     * @param jobText The job posting text
     * @return Extracted job title or first line of text
     */
    private String extractJobTitle(String jobText) {
        if (jobText == null || jobText.trim().isEmpty()) {
            return null;
        }

        // Try to extract from "Position:" or "Job Title:" or similar
        java.util.regex.Pattern titlePattern = java.util.regex.Pattern
                .compile("(?i)(position|job title|role|opening)\\s*:?\\s*([^\\n]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = titlePattern.matcher(jobText);

        if (matcher.find()) {
            String title = matcher.group(2).trim();
            if (title.length() > 200) {
                title = title.substring(0, 200);
            }
            return title;
        }

        // Fallback: Use first line (up to first newline)
        String[] lines = jobText.split("\\n");
        if (lines.length > 0 && lines[0].length() > 3) {
            String firstLine = lines[0].trim();
            if (firstLine.length() > 200) {
                firstLine = firstLine.substring(0, 200);
            }
            return firstLine;
        }

        // Last resort: use first 50 chars
        String result = jobText.substring(0, Math.min(50, jobText.length()));
        return result.replaceAll("\\n", " ").trim();
    }
}
