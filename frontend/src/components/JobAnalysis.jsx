import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { analyzeJobEnhanced, analyzeJobFile, extractEntities } from '../services/api';
import { useToast } from './shared/Toast';
import { useAuth } from '../context/AuthContext';
import { LoadingSpinner } from './shared/LoadingSpinner';
import FileUploadZone from './shared/FileUploadZone';
import '../styles/JobAnalysis.css';

/**
 * JobAnalysis Component
 * Provides the main interface for users to analyze job postings.
 * Supports switching between two modes: "Text Input" and "File Upload".
 * 
 * ENHANCED: Now requires company name for verification and post-processing
 */
const JobAnalysis = () => {
  // State for active tab ('text' or 'file')
  const [activeTab, setActiveTab] = useState('text');
  
  // ✅ NEW: Company name (REQUIRED)
  const [companyName, setCompanyName] = useState('');
  
  // State for Domain Verification
  const [jobPostingUrl, setJobPostingUrl] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  
  // State for text input mode
  const [jobText, setJobText] = useState('');
  
  // State for file upload mode
  const [file, setFile] = useState(null);
  const [fileType, setFileType] = useState('image'); // default type
  
  // Global loading state during API calls
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  
  // App context
  const { addToast } = useToast();
  const { user } = useAuth();

  /**
   * Auto-extract entities (company name, URL, domain) when text changes
   * This breaks the circular dependency: user enters text → auto-extract → form fields populated → analyze enabled
   */
  useEffect(() => {
    if (!jobText.trim() || jobText.length < 10) {
      // Don't extract from very short text
      return;
    }

    // Debounce extraction to avoid calling API on every character
    const timer = setTimeout(async () => {
      setExtracting(true);
      try {
        const extracted = await extractEntities(jobText);
        
        if (extracted.success) {
          // Only update company name if user hasn't manually entered one
          if (!companyName.trim() && extracted.companyName) {
            setCompanyName(extracted.companyName);
            addToast(`Auto-detected company: ${extracted.companyName}`, 'info');
          }
          
          // Auto-populate URL and email if found
          if (!jobPostingUrl.trim() && extracted.url) {
            setJobPostingUrl(extracted.url);
          }
          if (!contactEmail.trim() && extracted.domain) {
            setContactEmail(extracted.domain);
          }
        }
      } catch (err) {
        console.error('Entity extraction failed:', err);
        // Don't show error toast for extraction - it's optional/background
      } finally {
        setExtracting(false);
      }
    }, 800); // 800ms debounce

    return () => clearTimeout(timer); // Cleanup timer on dependency change
  }, [jobText]); // Only re-run when jobText changes

  /**
   * Handles the submission of the text-based job analysis form.
   * Validates input (including REQUIRED company name), shows loading state, calls API, and redirects to result page.
   */
  const handleTextSubmit = async (e) => {
    e.preventDefault();
    
    // ✅ NEW: Validate company name is provided
    if (!companyName.trim()) {
      addToast('Company name is required for verification', 'warning');
      return;
    }
    
    if (!jobText.trim()) {
      addToast('Please enter a job description to analyze.', 'warning');
      return;
    }

    // ✅ NEW: Validate minimum word count
    const wordCount = jobText.trim().split(/\s+/).length;
    if (wordCount < 30) {
      addToast('Minimum 30 words required', 'warning');
      return;
    }
    
    setLoading(true);
    try {
      const actualUserId = user ? (user.userId || user.id || user._id) : '';
      console.log('--- DEBUG: Submitting Enhanced Text Analysis ---');
      console.log('Company Name:', companyName);
      console.log('User ID:', actualUserId);
      
      // Call ENHANCED analysis endpoint with company verification
      const result = await analyzeJobEnhanced(
        jobText,
        companyName,
        jobPostingUrl,
        contactEmail,
        actualUserId
      );
      navigate('/result', { state: { result } });
    } catch (err) {
      console.error(err);
      addToast('Error analyzing job posting. Please try again.', 'error');
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handles the submission of the file-based job analysis form.
   * Makes sure a file and company name are selected, sets loading state, calls API, and redirects to result page.
   */
  const handleFileSubmit = async (e) => {
    e.preventDefault();
    
    // ✅ NEW: Validate company name is provided
    if (!companyName.trim()) {
      addToast('Company name is required for verification', 'warning');
      return;
    }
    
    if (!file) {
      addToast('Please select a file to upload.', 'warning');
      return;
    }
    
    setLoading(true);
    try {
      const actualUserId = user ? (user.userId || user.id || user._id) : '';
      console.log('--- DEBUG: Submitting Enhanced File Analysis ---');
      console.log('Company Name:', companyName);
      console.log('User ID:', actualUserId);
      
      const result = await analyzeJobFile(file, fileType, jobPostingUrl, actualUserId, companyName, contactEmail);
      
      // ✅ NEW: Check if backend validation failed due to invalid input
      if (result.externalValidationInfluence && 
          result.externalValidationInfluence.includes('Invalid job post input')) {
        addToast('Minimum 30 words required', 'warning');
      }
      
      navigate('/result', { state: { result } });
    } catch (err) {
      console.error(err);
      addToast('Error analyzing file. Please try again.', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="analysis-page">
      <div className="container">
        <div className="analysis-header">
          <h1>Analyze a <span className="text-gradient">Job Posting</span></h1>
          <p>Paste text or upload a file to check if a job posting is legitimate</p>
        </div>

        {/* Corporate Jobs Notice */}
        <div style={{
          backgroundColor: '#EFF6FF',
          border: '2px solid #0EA5E9',
          borderRadius: '0.75rem',
          padding: '1rem 1.5rem',
          marginBottom: '1.5rem',
          textAlign: 'center'
        }}>
          <div style={{ fontSize: '1rem', fontWeight: 'bold', color: '#0369A1', marginBottom: '0.25rem' }}>
            💼 Optimized for Corporate Job Roles
          </div>
          <p style={{ color: '#0369A1', margin: 0, fontSize: '0.875rem' }}>
            This detector is trained on corporate positions. For freelance, gig work, internships, or non-corporate roles, accuracy may differ.
          </p>
        </div>

        <div className="analysis-card card-elevated">
          {/* Tabs */}
          <div className="analysis-tabs">
            <div className="tabs">
              <button
                className={`tab ${activeTab === 'text' ? 'active' : ''}`}
                onClick={() => setActiveTab('text')}
              >
                📝 Text Input
              </button>
              <button
                className={`tab ${activeTab === 'file' ? 'active' : ''}`}
                onClick={() => setActiveTab('file')}
              >
                📁 File Upload
              </button>
            </div>
          </div>

          {/* Text Tab */}
          {activeTab === 'text' && (
            <form onSubmit={handleTextSubmit}>
              {/* ✅ NEW: Company Name (REQUIRED) */}
              <div className="analysis-input-group" style={{ marginBottom: 'var(--space-4)' }}>
                <label style={{ display: 'block', marginBottom: 'var(--space-2)', fontWeight: 'var(--font-weight-medium)', color: 'var(--text-secondary)' }}>
                  Company Name * <span style={{ color: 'var(--error-color)' }}>Required</span>
                </label>
                <input
                  type="text"
                  className="input-field"
                  placeholder="e.g., Google, Microsoft, Tesla"
                  value={companyName}
                  onChange={(e) => setCompanyName(e.target.value)}
                  required
                />
                <small style={{ color: 'var(--text-tertiary)', marginTop: 'var(--space-1)' }}>
                  The company name will be verified against corporate databases
                </small>
              </div>

              {/* Optional URL */}
              <div className="analysis-input-group" style={{ marginBottom: 'var(--space-4)' }}>
                <label style={{ display: 'block', marginBottom: 'var(--space-2)', fontWeight: 'var(--font-weight-medium)', color: 'var(--text-secondary)' }}>
                  Job Posting URL (Optional)
                </label>
                <input
                  type="url"
                  className="input-field"
                  placeholder="https://example.com/job-posting"
                  value={jobPostingUrl}
                  onChange={(e) => setJobPostingUrl(e.target.value)}
                />
              </div>

              {/* Optional Email */}
              <div className="analysis-input-group" style={{ marginBottom: 'var(--space-4)' }}>
                <label style={{ display: 'block', marginBottom: 'var(--space-2)', fontWeight: 'var(--font-weight-medium)', color: 'var(--text-secondary)' }}>
                  Contact Email (Optional)
                </label>
                <input
                  type="email"
                  className="input-field"
                  placeholder="recruiter@company.com"
                  value={contactEmail}
                  onChange={(e) => setContactEmail(e.target.value)}
                />
              </div>

              <div className="analysis-textarea-wrapper">
                <textarea
                  className="analysis-textarea"
                  placeholder="Paste the job description text here...&#10;&#10;Include details like job title, company name, requirements, salary, location, etc."
                  value={jobText}
                  onChange={(e) => setJobText(e.target.value)}
                />
                <div className="analysis-char-count">
                  {jobText.length.toLocaleString()} characters
                </div>
              </div>
              <div className="analysis-submit">
                <span className="analysis-submit-hint">
                  Tip: Include as much detail as possible for better accuracy
                </span>
                <button
                  type="submit"
                  className="btn btn-primary btn-lg"
                  disabled={loading || !jobText.trim() || !companyName.trim()}
                >
                  🔍 Analyze Text
                </button>
              </div>
            </form>
          )}

          {/* File Tab */}
          {activeTab === 'file' && (
            <form onSubmit={handleFileSubmit}>
              {/* ✅ NEW: Company Name (REQUIRED) */}
              <div className="analysis-input-group" style={{ marginBottom: 'var(--space-4)' }}>
                <label style={{ display: 'block', marginBottom: 'var(--space-2)', fontWeight: 'var(--font-weight-medium)', color: 'var(--text-secondary)' }}>
                  Company Name * <span style={{ color: 'var(--error-color)' }}>Required</span>
                </label>
                <input
                  type="text"
                  className="input-field"
                  placeholder="e.g., Google, Microsoft, Tesla"
                  value={companyName}
                  onChange={(e) => setCompanyName(e.target.value)}
                  required
                />
                <small style={{ color: 'var(--text-tertiary)', marginTop: 'var(--space-1)' }}>
                  The company name will be verified against corporate databases
                </small>
              </div>

              {/* Optional URL */}
              <div className="analysis-input-group" style={{ marginBottom: 'var(--space-4)' }}>
                <label style={{ display: 'block', marginBottom: 'var(--space-2)', fontWeight: 'var(--font-weight-medium)', color: 'var(--text-secondary)' }}>
                  Job Posting URL (Optional)
                </label>
                <input
                  type="url"
                  className="input-field"
                  placeholder="https://example.com/job-posting"
                  value={jobPostingUrl}
                  onChange={(e) => setJobPostingUrl(e.target.value)}
                />
              </div>

              {/* Optional Email */}
              <div className="analysis-input-group" style={{ marginBottom: 'var(--space-4)' }}>
                <label style={{ display: 'block', marginBottom: 'var(--space-2)', fontWeight: 'var(--font-weight-medium)', color: 'var(--text-secondary)' }}>
                  Contact Email (Optional)
                </label>
                <input
                  type="email"
                  className="input-field"
                  placeholder="recruiter@company.com"
                  value={contactEmail}
                  onChange={(e) => setContactEmail(e.target.value)}
                />
              </div>

              <FileUploadZone
                file={file}
                setFile={setFile}
                fileType={fileType}
                setFileType={setFileType}
              />
              <div className="analysis-submit" style={{ marginTop: 'var(--space-6)' }}>
                <span className="analysis-submit-hint">
                  Supported: Images, Audio, Documents
                </span>
                <button
                  type="submit"
                  className="btn btn-primary btn-lg"
                  disabled={loading || !file || !companyName.trim()}
                >
                  🔍 Analyze File
                </button>
              </div>
            </form>
          )}
        </div>
      </div>

      {/* Loading Overlay */}
      {loading && (
        <div className="analysis-loading-overlay">
          <LoadingSpinner size={56} text="Analyzing your job posting with AI..." />
        </div>
      )}
    </div>
  );
};

export default JobAnalysis;
