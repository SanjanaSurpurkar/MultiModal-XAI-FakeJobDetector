import axios from 'axios';

// API Base URL
const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8081/api';

/**
 * Analyzes plain text job description.
 */
export const analyzeJob = async (jobText, domain = '', userId = '') => {
  let url = `${API_BASE}/analyze?`;
  if (domain) url += `domain=${encodeURIComponent(domain)}&`;
  if (userId) url += `userId=${encodeURIComponent(userId)}&`;
  const response = await axios.post(url, jobText, {
    headers: { 'Content-Type': 'text/plain' },
  });
  return response.data;
};

/**
 * Extracts entities (company name, URL, domain) from job text.
 * Used for auto-populating form fields before full analysis.
 */
export const extractEntities = async (jobText) => {
  try {
    const response = await axios.post(`${API_BASE}/extract-entities`, jobText, {
      headers: { 'Content-Type': 'text/plain' },
    });
    return response.data;
  } catch (error) {
    console.error('Entity Extraction Error:', error);
    // Return empty result on error to allow graceful fallback
    return {
      success: false,
      companyName: '',
      url: '',
      domain: '',
      error: error.message
    };
  }
};

/**
 * Uploads a file (PDF, image, audio) for job analysis.
 */
export const analyzeJobFile = async (file, fileType, domain = '', userId = '', companyName = '', contactEmail = '') => {
  try {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    if (domain) formData.append('jobPostingUrl', domain);
    if (userId) formData.append('userId', userId);
    if (companyName) formData.append('companyName', companyName);
    if (contactEmail) formData.append('contactEmail', contactEmail);

    const response = await axios.post(`${API_BASE}/analyze-file`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return response.data;
  } catch (error) {
    console.error('File Upload Error:', error);
    throw error;
  }
};

/**
 * Registers a new user.
 */
export const signupUser = async (name, email, password) => {
  const response = await axios.post(`${API_BASE}/users/signup`, { name, email, password });
  return response.data;
};

/**
 * Authenticates an existing user.
 */
export const signinUser = async (email, password) => {
  const response = await axios.post(`${API_BASE}/users/signin`, { email, password });
  return response.data;
};

/**
 * Fetches dashboard data for a user (DEPRECATED - use getUserDashboard2 instead).
 */
export const getUserDashboard = async (userId) => {
  const response = await axios.get(`${API_BASE}/dashboard/${userId}`);
  return response.data;
};

/**
 * Fetches user dashboard with statistics and recent results.
 * 
 * @param {string} userId - The user ID
 * @returns {Promise<{success, statistics, recentResults}>}
 */
export const getUserDashboard2 = async (userId) => {
  const response = await axios.get(`${API_BASE}/users/${userId}/dashboard`);
  return response.data;
};

/**
 * Fetches all job results for a user.
 * 
 * @param {string} userId - The user ID
 * @returns {Promise<{success, count, results}>}
 */
export const getUserJobResults = async (userId) => {
  const response = await axios.get(`${API_BASE}/users/${userId}/results`);
  return response.data;
};

/**
 * Fetches a specific job result by ID.
 * 
 * @param {string} userId - The user ID
 * @param {string} resultId - The result ID
 * @returns {Promise<{success, result}>}
 */
export const getJobResultDetail = async (userId, resultId) => {
  const response = await axios.get(`${API_BASE}/users/${userId}/results/${resultId}`);
  return response.data;
};

/**
 * Analyzes a text job posting with configurable LIME explanation depth & format.
 *
 * @param {string} jobText      - The job description text.
 * @param {string} domain       - Optional domain/email for verification.
 * @param {string} userId       - Optional user ID.
 * @param {number} numFeatures  - Number of LIME feature words (default 10).
 * @param {string} format       - Output format: 'json' | 'visual' (default 'json').
 */
export const analyzeJobWithExplanation = async (
  jobText,
  domain = '',
  userId = '',
  numFeatures = 10,
  format = 'json'
) => {
  let url = `${API_BASE}/analyze?numFeatures=${numFeatures}&format=${format}`;
  if (domain) url += `&domain=${encodeURIComponent(domain)}`;
  if (userId) url += `&userId=${encodeURIComponent(userId)}`;

  const response = await axios.post(url, jobText, {
    headers: { 'Content-Type': 'text/plain' },
  });
  return response.data;
};

/**
 * Fetches a fresh LIME explanation for a given depth (ResultPage depth slider).
 * Does NOT re-run PMML prediction.
 *
 * @param {string} text         - Job description text.
 * @param {number} numFeatures  - Number of LIME features to return.
 * @param {string} format       - 'json' | 'visual'.
 * @returns {Promise<{lime_explanations, cache_status, explanation_latency_ms}>}
 */
export const getExplanation = async (text, numFeatures = 10, format = 'json') => {
  const response = await axios.get(`${API_BASE}/explain`, {
    params: { text, numFeatures, format },
  });
  return response.data;
};

/**
 * Analyzes a job posting with company verification and domain validation.
 * ENHANCED ENDPOINT - performs company verification and post-processing.
 *
 * @param {string} jobText       - The job description text (required).
 * @param {string} companyName   - The company name (REQUIRED).
 * @param {string} jobPostingUrl - The job posting URL (optional).
 * @param {string} contactEmail  - Contact email from job posting (optional).
 * @param {string} userId        - Optional user ID.
 * @returns {Promise<EnhancedJobResult>}
 */
export const analyzeJobEnhanced = async (
  jobText,
  companyName,
  jobPostingUrl = '',
  contactEmail = '',
  userId = ''
) => {
  let url = `${API_BASE}/analyze-enhanced`;
  const params = [];
  
  // Only add parameters if they have values (enables auto-fill)
  if (companyName && companyName.trim()) params.push(`companyName=${encodeURIComponent(companyName)}`);
  if (jobPostingUrl && jobPostingUrl.trim()) params.push(`jobPostingUrl=${encodeURIComponent(jobPostingUrl)}`);
  if (contactEmail && contactEmail.trim()) params.push(`contactEmail=${encodeURIComponent(contactEmail)}`);
  if (userId && userId.trim()) params.push(`userId=${encodeURIComponent(userId)}`);
  
  if (params.length > 0) {
    url += '?' + params.join('&');
  }

  const response = await axios.post(url, jobText, {
    headers: { 'Content-Type': 'text/plain' },
  });
  return response.data;
};

/**
 * Saves an enhanced job result to the database.
 * Called after user completes analysis and wants to save for dashboard.
 * 
 * @param {string} jobText - The original job text
 * @param {string} companyName - The company name
 * @param {object} enhancedResult - The enhanced analysis result
 * @param {string} inputType - The input type (TEXT, IMAGE, AUDIO, DOCUMENT)
 * @param {string} userId - The user ID
 * @returns {Promise<{success, message, resultId, savedAt}>}
 */
export const saveEnhancedJobResult = async (
  jobText,
  companyName,
  enhancedResult,
  inputType = 'TEXT',
  userId
) => {
  const response = await axios.post(`${API_BASE}/save-result`, {
    jobText,
    companyName,
    enhancedResult,
    inputType,
    userId
  });
  return response.data;
};
