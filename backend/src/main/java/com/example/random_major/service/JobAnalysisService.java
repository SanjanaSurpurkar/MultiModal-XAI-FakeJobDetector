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
    // ✅ UNIFIED PROCESSING PIPELINE (for ALL input types)
    // ---------------------------------------------------
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
        try {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🔄 UNIFIED PIPELINE: Starting analysis");
            log.info("═══════════════════════════════════════════════════════════");
            
            // ═══════════════════════════════════════════════════════════
            // STEP 0: INPUT VALIDATION & NORMALIZATION
            // ═══════════════════════════════════════════════════════════
            log.info("📋 STEP 0: Validating and normalizing input...");
            
            // Validate and normalize job text
            if (jobText == null || jobText.trim().isEmpty()) {
                log.error("❌ Job text is null or empty - cannot proceed");
                return new EnhancedJobResult("error", 0.0, 0.0);
            }
            
            // Trim whitespace and normalize text
            jobText = jobText.trim();
            log.info("   Text length: {} characters", jobText.length());
            
            // Validate text has sufficient content
            if (jobText.length() < 20) {
                log.error("❌ Text is too short ({} chars) - minimum 20 chars required", jobText.length());
                return new EnhancedJobResult("error", 0.0, 0.0);
            }
            
            // Normalize input type
            String normalizedInputType = inputType != null ? inputType.toUpperCase().trim() : "TEXT";
            log.info("   Input Type: {}", normalizedInputType);
            log.info("   User Company: {}", userCompanyName != null ? userCompanyName : "NOT PROVIDED");
            log.info("   User URL: {}", userJobPostingUrl != null ? userJobPostingUrl : "NOT PROVIDED");
            log.info("   User Email: {}", userContactEmail != null ? userContactEmail : "NOT PROVIDED");
            log.info("✅ Input validation passed");

            // ═══════════════════════════════════════════════════════════
            // STEP 1: ENTITY EXTRACTION (MANDATORY for all input types)
            // ═══════════════════════════════════════════════════════════
            log.info("� STEP 1: Extracting entities from text...");
            log.info("   Processing as: {} input", normalizedInputType);
            ExtractedData extractedData = entityExtractionService.extractFromText(jobText);
            
            // Validate extraction
            if (extractedData == null) {
                log.error("⚠️  Entity extraction returned null, using empty ExtractedData");
                extractedData = new ExtractedData(null, null, null);
            }
            
            log.info("✅ Entity extraction COMPLETED (ALWAYS applied for {})", normalizedInputType);
            log.info("   - Company: '{}' ({})", 
                extractedData.getCompanyName() != null ? extractedData.getCompanyName() : "NULL",
                extractedData.getCompanyName() != null ? "EXTRACTED" : "NOT FOUND");
            log.info("   - URL: '{}' ({})", 
                extractedData.getUrl() != null ? extractedData.getUrl() : "NULL",
                extractedData.getUrl() != null ? "EXTRACTED" : "NOT FOUND");
            log.info("   - Domain: '{}' ({})", 
                extractedData.getDomain() != null ? extractedData.getDomain() : "NULL",
                extractedData.getDomain() != null ? "EXTRACTED" : "NOT FOUND");

            // ═══════════════════════════════════════════════════════════
            // STEP 2: AUTO-FILL company name (use extracted if user didn't provide)
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 2: Determining company name to use for validation...");
            String companyNameForValidation = null;
            String companySource = "SOURCE_UNKNOWN";
            
            // Priority 1: User-provided company name
            if (userCompanyName != null && !userCompanyName.trim().isEmpty()) {
                companyNameForValidation = userCompanyName.trim();
                companySource = "SOURCE_USER";
                log.info("✅ Using USER-PROVIDED company name: '{}'", companyNameForValidation);
            }
            // Priority 2: Extracted company name
            else if (extractedData.getCompanyName() != null && !extractedData.getCompanyName().trim().isEmpty()) {
                companyNameForValidation = extractedData.getCompanyName().trim();
                companySource = "SOURCE_EXTRACTED";
                log.info("✅ Using EXTRACTED company name: '{}'", companyNameForValidation);
            }
            // Priority 3: None available
            else {
                log.warn("⚠️  No company name available (not provided by user, not extracted from text)");
                companyNameForValidation = null;
                companySource = "SOURCE_NONE";
            }

            // Determine URL and email to use
            String urlForValidation = userJobPostingUrl != null && !userJobPostingUrl.trim().isEmpty() 
                ? userJobPostingUrl.trim() 
                : extractedData.getUrl();
            String urlSource = userJobPostingUrl != null ? "SOURCE_USER" : "SOURCE_EXTRACTED";
            
            String emailForValidation = userContactEmail != null && !userContactEmail.trim().isEmpty()
                ? userContactEmail.trim()
                : extractedData.getDomain();
            String emailSource = userContactEmail != null ? "SOURCE_USER" : "SOURCE_EXTRACTED";
            
            log.info("   Company: {} [{}]", companyNameForValidation, companySource);
            log.info("   URL: {} [{}]", urlForValidation, urlSource);
            log.info("   Email/Domain: {} [{}]", emailForValidation, emailSource);

            // ═══════════════════════════════════════════════════════════
            // STEP 2.5: JOB POST VALIDATION (reject invalid inputs)
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 2.5: Validating job post content...");
            if (!JobPostValidator.isValidJobPost(jobText)) {
                log.warn("❌ Invalid job post input detected - returning FAKE with 100% confidence");
                EnhancedJobResult invalidResult = new EnhancedJobResult("FAKE", 1.0, 1.0);
                invalidResult.setExternalValidationInfluence("Invalid job post input - does not meet validation criteria");
                return invalidResult;
            }
            log.info("✅ Job post validation passed");

            // ═══════════════════════════════════════════════════════════
            // STEP 3: ML PREDICTION (base score from PMML model)
            // ═══════════════════════════════════════════════════════════
            log.info("📊 STEP 3: Running ML model prediction on {} input text ({} chars)...", 
                normalizedInputType, jobText.length());
            Map<String, Object> result = modelEvaluatorService.predict(jobText);
            double baseModelScore = 
                    ((Number) result.getOrDefault("probability_fake", 0.0)).doubleValue();
            log.info("✅ ML prediction completed");
            log.info("   Base model score: {} ({}%)", baseModelScore, (int)(baseModelScore * 100));
            log.info("   Prediction: {}", baseModelScore >= 0.5 ? "FAKE" : "REAL");

            // ═══════════════════════════════════════════════════════════
            // STEP 4: RED FLAG DETECTION (same logic for all input types)
            // ═══════════════════════════════════════════════════════════
            log.info("🚩 STEP 4: Running red flag detection on {} input...", normalizedInputType);
            java.util.List<com.example.random_major.model.RedFlag> redFlags = 
                    redFlagDetectionService.detectRedFlags(jobText);
            double redFlagScore = redFlagDetectionService.calculateRedFlagScore(redFlags);
            log.info("✅ Red flag detection COMPLETED (applied to {} input)", normalizedInputType);
            log.info("   Red flags detected: {}", redFlags.size());
            log.info("   Red flag score: {} (severity)", redFlagScore);

            // ═══════════════════════════════════════════════════════════
            // STEP 5: COMPANY VERIFICATION (using extracted/provided company name)
            // ═══════════════════════════════════════════════════════════
            log.info("🏢 STEP 5: Verifying company (source: {})...", companySource);
            CompanyVerificationResponse companyVerification = null;
            
            if (companyNameForValidation != null && !companyNameForValidation.isEmpty()) {
                log.info("   Verifying: '{}' [from {}]", companyNameForValidation, companySource);
                companyVerification = companyVerificationService.verifyCompany(companyNameForValidation);
            } else {
                log.warn("   ⚠️  No company name to verify (not provided and not extracted)");
            }
            
            if (companyVerification == null) {
                log.warn("   ⚠️  Company verification failed or returned null");
                companyVerification = new CompanyVerificationResponse(
                    false, "UNKNOWN", null, "Company verification unavailable or not performed"
                );
            }
            log.info("✅ Company verification COMPLETED - Status: {}", companyVerification.getStatus());

            // ═══════════════════════════════════════════════════════════
            // STEP 5.5: HYBRID REAL-TIME VERIFICATION (website + search API)
            // ═══════════════════════════════════════════════════════════
            log.info("🔍 STEP 5.5: Running hybrid real-time verification...");
            HybridVerificationResponse hybridVerification = null;
            
            if (companyNameForValidation != null && !companyNameForValidation.isEmpty()) {
                log.info("   Starting hybrid verification for: '{}'", companyNameForValidation);
                
                // Extract job title from text (first line or job title pattern)
                String jobTitleForVerification = extractJobTitle(jobText);
                
                hybridVerification = hybridJobVerificationService.verify(
                    companyNameForValidation,
                    jobTitleForVerification,
                    null, // location (optional)
                    jobText,
                    extractedData.getDomain()
                );
                
                log.info("✅ Hybrid verification COMPLETED");
                log.info("   Website Score: {} (Domain exists: {}, Careers page: {})",
                    hybridVerification.getWebsiteScore(),
                    hybridVerification.isWebsiteExists(),
                    hybridVerification.isCareersPageExists());
                log.info("   Search Score: {} (Job matches: {})",
                    hybridVerification.getSearchScore(),
                    hybridVerification.isJobMatchSearch());
                log.info("   Final Hybrid Score: {}", hybridVerification.getFinalScore());
                log.info("   Status: {}", hybridVerification.getApiStatus());
            } else {
                log.warn("   ⚠️  No company name to verify via hybrid method");
                hybridVerification = HybridVerificationResponse.neutral("No company name available");
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 6: DOMAIN VALIDATION (using extracted URLs/emails and verified company)
            // ═══════════════════════════════════════════════════════════
            log.info("🔗 STEP 6: Validating domain (for {} input)...", normalizedInputType);
            DomainValidationResponse domainValidation = null;
            
            String companyDomainForValidation = null;
            if (companyVerification.isExists() && companyVerification.getWebsite() != null) {
                companyDomainForValidation = companyVerification.getWebsite();
                log.info("   Using verified company domain: {} (from company verification)", companyDomainForValidation);
            } else {
                log.warn("   Company not verified, will validate against posting domains only");
            }
            
            // Determine if validation inputs are available
            boolean hasUrlToValidate = urlForValidation != null && !urlForValidation.isEmpty();
            boolean hasEmailToValidate = emailForValidation != null && !emailForValidation.isEmpty();
            
            // Always attempt domain validation if URL or email is provided
            if (hasUrlToValidate || hasEmailToValidate) {
                log.info("   Validation inputs available - URL: {} [{}], Email: {} [{}]",
                    hasUrlToValidate ? "YES" : "NO", urlSource,
                    hasEmailToValidate ? "YES" : "NO", emailSource);
                domainValidation = domainValidationService.validateDomain(
                    companyDomainForValidation,
                    urlForValidation,
                    emailForValidation
                );
                log.info("✅ Domain validation COMPLETED (for {} input)", normalizedInputType);
                if (domainValidation != null) {
                    log.info("   Match: {}, Risk Score: {}", domainValidation.isMatch(), domainValidation.getRiskScore());
                }
            } else {
                log.warn("   ⚠️  No URL or email available to validate (not provided and not extracted)");
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 7: POST-PROCESSING (adjust score based on validation)
            // ═══════════════════════════════════════════════════════════
            log.info("⚙️  STEP 7: Applying post-processing adjustments (for {} input)...", normalizedInputType);
            PredictionService.PostProcessingResult postProcessing = 
                    predictionService.applyPostProcessing(
                        baseModelScore,
                        companyVerification,
                        domainValidation
                    );
            
            double postProcessedScore = postProcessing.getAdjustedScore();
            double adjustmentFactor = postProcessing.getAdjustmentFactor();
            
            if (redFlagScore > 0) {
                postProcessedScore = redFlagDetectionService.applyRedFlagScaling(postProcessedScore, redFlagScore);
                log.info("   Red flag scaling applied: {} → {} (severity: {})", 
                    postProcessing.getAdjustedScore(), postProcessedScore, redFlagScore);
            }
            
            // ─────────────────────────────────────────────────────────
            // Apply hybrid verification score adjustment
            // ─────────────────────────────────────────────────────────
            if (hybridVerification != null && hybridVerification.getFinalScore() < 0.4) {
                // Low hybrid score → strongly increase FAKE probability
                double hybridAdjustment = 1.0 - (hybridVerification.getFinalScore() * 0.75);
                postProcessedScore = Math.min(1.0, postProcessedScore * hybridAdjustment);
                log.info("   Hybrid verification: LOW score ({}) → FAKE boost: {} → {}%",
                    hybridVerification.getFinalScore(), (int)(postProcessing.getAdjustedScore() * 100),
                    (int)(postProcessedScore * 100));
            } else if (hybridVerification != null && hybridVerification.getFinalScore() > 0.7) {
                // High hybrid score → increase REAL confidence
                double hybridAdjustment = 0.5 + (hybridVerification.getFinalScore() * 0.25);
                postProcessedScore = postProcessedScore * hybridAdjustment;
                log.info("   Hybrid verification: HIGH score ({}) → REAL boost: {} → {}%",
                    hybridVerification.getFinalScore(), (int)(postProcessing.getAdjustedScore() * 100),
                    (int)(postProcessedScore * 100));
            } else {
                log.info("   Hybrid verification: NEUTRAL score ({}) → no additional adjustment",
                    hybridVerification != null ? hybridVerification.getFinalScore() : 0.5);
            }
            
            log.info("✅ Post-processing COMPLETED");
            log.info("   Base → Adjusted: {}% → {}% (factor: {})", 
                (int)(baseModelScore * 100), (int)(postProcessedScore * 100), adjustmentFactor);

            // ═══════════════════════════════════════════════════════════
            // STEP 8: LIME EXPLANATION (interpretability - same for all inputs)
            // ═══════════════════════════════════════════════════════════
            log.info("💡 STEP 8: Generating LIME explanations for {} input...", normalizedInputType);
            LimeService.LimeResult limeResult;
            try {
                limeResult = limeService.explain(jobText, defaultNumFeatures, defaultOutputFormat, userId);
                log.info("✅ LIME explanation COMPLETED (for {} input)", normalizedInputType);
                log.info("   Features: {}, Status: {}, Latency: {}ms", 
                    limeResult.explanations.size(), limeResult.cacheStatus, limeResult.latencyMs);
            } catch (Exception e) {
                log.error("⚠️  LIME explanation failed for {} input: {}", normalizedInputType, e.getMessage());
                limeResult = LimeService.LimeResult.error(0);
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 9: BUILD RESPONSE
            // ═══════════════════════════════════════════════════════════
            log.info("📦 STEP 9: Building response object...");
            double finalScore = postProcessedScore;
            String finalPrediction = finalScore >= 0.5 ? "FAKE" : "REAL";

            EnhancedJobResult enhancedResult = new EnhancedJobResult(
                finalPrediction,
                finalScore,
                baseModelScore
            );
            
            // Set all response fields
            enhancedResult.setAdjustmentFactor(adjustmentFactor);
            enhancedResult.setCompanyVerification(companyVerification);
            enhancedResult.setDomainValidation(domainValidation);
            enhancedResult.setHybridVerification(hybridVerification);
            enhancedResult.setLimeExplanations(limeResult.explanations);
            enhancedResult.setRedFlagScore(redFlagScore);
            enhancedResult.setRedFlagsDetected(redFlags);
            enhancedResult.setExternalValidationInfluence(
                postProcessing.getExternalValidationNote() + "\n" +
                redFlagDetectionService.formatRedFlagsForNote(redFlags, redFlagScore)
            );
            enhancedResult.setCacheStatus(limeResult.cacheStatus);
            enhancedResult.setExplanationLatencyMs(limeResult.latencyMs);
            enhancedResult.setGcsUrl(limeResult.gcsUrl);
            
            // ✅ SET EXTRACTED DATA IN RESPONSE
            enhancedResult.setExtractedCompanyName(extractedData.getCompanyName());
            enhancedResult.setExtractedUrl(extractedData.getUrl());
            enhancedResult.setExtractedDomain(extractedData.getDomain());
            
            log.info("✅ Response object built");

            // ═══════════════════════════════════════════════════════════
            // STEP 10: SAVE TO DATABASE
            // ═══════════════════════════════════════════════════════════
            log.info("💾 STEP 10: Saving to database...");
            try {
                jobResultService.saveEnhancedJobResult(
                    jobText,
                    companyNameForValidation,
                    enhancedResult,
                    inputType != null ? inputType : "TEXT",
                    userId
                );
                log.info("✅ Saved to MongoDB");
            } catch (Exception e) {
                log.warn("⚠️  Could not save to database: {}", e.getMessage());
            }

            // ═══════════════════════════════════════════════════════════
            // SUMMARY: Pipeline execution complete
            // ═══════════════════════════════════════════════════════════
            log.info("═══════════════════════════════════════════════════════════");
            log.info("✅ UNIFIED PIPELINE COMPLETE");
            log.info("═══════════════════════════════════════════════════════════");
            log.info("🔄 PIPELINE EXECUTION SUMMARY:");
            log.info("   Input Type: {} (unified processing)", normalizedInputType);
            log.info("   Text Length: {} characters", jobText.length());
            log.info("   Prediction: {} (confidence: {}%)", finalPrediction, (int)(finalScore * 100));
            log.info("   Company Name: {} [{}]", companyNameForValidation, companySource);
            log.info("   Company Status: {}", companyVerification.getStatus());
            log.info("   Red Flags Detected: {}", redFlags.size());
            log.info("   Score Adjustment: {}% → {}% (factor: {})", 
                (int)(baseModelScore * 100), (int)(finalScore * 100), adjustmentFactor);
            log.info("   Steps Applied: EXTRACTION → PREDICTION → RED_FLAGS → VERIFICATION → VALIDATION → POSTPROCESSING → LIME");
            log.info("═══════════════════════════════════════════════════════════");

            return enhancedResult;

        } catch (Exception e) {
            log.error("❌ PIPELINE FAILED: {}", e.getMessage(), e);
            EnhancedJobResult errorResult = new EnhancedJobResult("error", 0.0, 0.0);
            return errorResult;
        }
    }

    // ---------------------------------------------------
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
