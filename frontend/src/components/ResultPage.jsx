import React, { useState, useCallback, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useToast } from './shared/Toast';
import '../styles/ResultPage.css';
import LimeChart from './LimeChart';
import { getExplanation, saveEnhancedJobResult } from '../services/api';

const DEPTH_OPTIONS = [10];

const ResultPage = () => {
  const { state } = useLocation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { addToast } = useToast();

  // ── Explanation state (depth/format controls + re-fetch) ──────
  const [numFeatures, setNumFeatures] = useState(10);
  const [outputFormat, setOutputFormat] = useState('json');
  const [limeExplanations, setLimeExplanations] = useState(null); // null = use initial
  const [limeLoading, setLimeLoading] = useState(false);
  const [limeError, setLimeError] = useState(null);
  const [cacheStatus, setCacheStatus] = useState(null);
  const [latencyMs, setLatencyMs] = useState(null);

  const jobTextRef = useRef(null); // store original text for depth re-fetch

  if (!state || !state.result) {
    return (
      <div className="result-page">
        <div className="container">
          <div className="result-empty">
            <div className="result-empty-icon">🔍</div>
            <h2>No Analysis Results</h2>
            <p>Submit a job posting to see the analysis results here.</p>
            <button className="btn btn-primary btn-lg" onClick={() => navigate('/analyze')}>
              Analyze a Job Posting
            </button>
          </div>
        </div>
      </div>
    );
  }

  const {
    label,
    prediction,               // ✅ NEW: from EnhancedJobResult
    probability_fake,         // Legacy format
    confidenceScore,          // ✅ NEW: from EnhancedJobResult (adjusted)
    baseModelScore,           // ✅ NEW: original model score
    adjustmentFactor,         // ✅ NEW: how much was adjusted
    explanation,              // legacy JSON string
    lime_explanations,
    cache_status,
    explanation_latency_ms,
    gcs_url,
    companyVerification,      // ✅ NEW: company verification data
    domainValidation,         // ✅ NEW: domain validation data
    externalValidationInfluence,  // ✅ NEW: explanation note
    redFlagScore,             // ✅ NEW: red flag detection score
    redFlagsDetected,         // ✅ NEW: list of detected red flags
    domainDetails,            // Legacy domain verification
    jobText,                  // may not be present in all flows
  } = state.result;

  // Store text for re-fetch
  if (jobText && !jobTextRef.current) jobTextRef.current = jobText;

  // Resolve current explanation list:
  //  1. limeExplanations from re-fetch (if user changed depth)
  //  2. lime_explanations from initial result (if populated)
  //  3. Parse legacy JSON string 'explanation'
  let resolvedExplanations = limeExplanations;
  if (resolvedExplanations === null) {
    if (lime_explanations && Array.isArray(lime_explanations) && lime_explanations.length > 0) {
      resolvedExplanations = lime_explanations;
    } else if (explanation && typeof explanation === 'string') {
      try { 
        const parsed = JSON.parse(explanation); 
        resolvedExplanations = Array.isArray(parsed) ? parsed : [];
      } catch (e) { 
        console.warn("Failed to parse explanation string", e);
        resolvedExplanations = []; 
      }
    } else {
      resolvedExplanations = [];
    }
  }
  const displayCacheStatus  = cacheStatus  ?? cache_status;
  const displayLatencyMs    = latencyMs    ?? explanation_latency_ms;

  // ── Probability / risk ────────────────────────────────────────
  // ✅ NEW: Check if this is enhanced result (has confidenceScore)
  let fakeProb;
  let finalPrediction = prediction || label;  // Use prediction if available
  
  if (confidenceScore !== undefined) {
    // ✅ NEW: Enhanced result format
    fakeProb = confidenceScore;
    if (fakeProb > 1) fakeProb /= 100;  // normalize if percentage
  } else {
    // Legacy format
    fakeProb = probability_fake;
    if (fakeProb > 1) fakeProb /= 100;
  }
  
  fakeProb = Math.min(Math.max(fakeProb, 0), 1);
  const realProb = 1 - fakeProb;
  const isFake = finalPrediction === 'FAKE' || fakeProb >= 0.5;
  const fakePercent = (fakeProb * 100).toFixed(1);
  const realPercent = (realProb * 100).toFixed(1);

  let riskLevel, riskClass;
  if (fakeProb < 0.25)      { riskLevel = 'Low Risk';      riskClass = 'low'; }
  else if (fakeProb < 0.5)  { riskLevel = 'Medium Risk';   riskClass = 'medium'; }
  else if (fakeProb < 0.75) { riskLevel = 'High Risk';     riskClass = 'high'; }
  else                       { riskLevel = 'Critical Risk'; riskClass = 'critical'; }

  // ── SVG Gauge ─────────────────────────────────────────────────
  const gaugeRadius       = 80;
  const gaugeCircumference = 2 * Math.PI * gaugeRadius;
  const gaugeOffset       = gaugeCircumference - (fakeProb * gaugeCircumference);

  // ── Depth slider handler ──────────────────────────────────────
  const handleDepthChange = useCallback(async (newDepth) => {
    setNumFeatures(newDepth);
    const text = jobTextRef.current || (typeof jobText === 'string' ? jobText : null);
    if (!text) return;

    setLimeLoading(true);
    setLimeError(null);
    try {
      const data = await getExplanation(text, newDepth, outputFormat);
      setLimeExplanations(data.lime_explanations ?? []);
      setCacheStatus(data.cache_status);
      setLatencyMs(data.explanation_latency_ms);
    } catch (err) {
      console.error('LIME re-fetch error:', err);
      setLimeError('Could not refresh explanation. LIME service may be offline.');
    } finally {
      setLimeLoading(false);
    }
  }, [outputFormat, jobText]);

  // ── Format toggle handler ─────────────────────────────────────
  const handleFormatChange = (fmt) => setOutputFormat(fmt);

  // ── Export handler ────────────────────────────────────────────
  const handleExport = () => {
    const payload = {
      label, probability_fake: fakeProb,
      explanations: resolvedExplanations,
      num_features: numFeatures,
      cache_status: displayCacheStatus,
      latency_ms: displayLatencyMs,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'lime_explanation.json';
    a.click(); URL.revokeObjectURL(url);
  };


  return (
    <div className="result-page">
      <div className="container">
        <div className="result-header">
          <h1>Analysis <span className="text-gradient">Results</span></h1>
        </div>

        {/* ── Verdict Card ────────────────────────────────── */}
        <div className="result-verdict card-elevated">
          <div className="result-gauge-container">
            <svg width="200" height="200" viewBox="0 0 200 200">
              <circle cx="100" cy="100" r={gaugeRadius} fill="none"
                stroke="var(--bg-secondary)" strokeWidth="12" />
              <circle cx="100" cy="100" r={gaugeRadius} fill="none"
                stroke={isFake ? 'var(--color-danger)' : 'var(--color-success)'}
                strokeWidth="12" strokeLinecap="round"
                strokeDasharray={gaugeCircumference} strokeDashoffset={gaugeOffset}
                transform="rotate(-90 100 100)"
                style={{ transition: 'stroke-dashoffset 1.2s cubic-bezier(0.34,1.56,0.64,1)' }} />
              <text x="100" y="92" textAnchor="middle" fill="var(--text-primary)"
                fontSize="32" fontWeight="800">{fakePercent}%</text>
              <text x="100" y="115" textAnchor="middle" fill="var(--text-muted)" fontSize="12">
                Fake Probability
              </text>
            </svg>
          </div>

          <div className={`result-prediction ${isFake ? 'fake' : 'real'}`}>
            {isFake ? '⚠️ FAKE JOB DETECTED' : '✅ LEGITIMATE JOB'}
          </div>

          {/* Risk bar */}
          <div className="risk-gauge">
            <div className="risk-gauge-label"><span>Safe</span><span>Critical</span></div>
            <div className="risk-gauge-bar">
              <div className={`risk-gauge-fill ${riskClass}`} style={{ width: `${fakeProb * 100}%` }} />
            </div>
            <div className="risk-level-badge">
              <span className={`badge badge-${riskClass === 'low' ? 'success' : riskClass === 'medium' ? 'warning' : 'danger'}`}>
                {riskLevel}
              </span>
            </div>
          </div>
        </div>

        {/* ── Confidence Breakdown (Simplified) ──────────────────────── */}
        <div className="result-breakdown card-elevated">
          <h3>📊 Confidence</h3>
          
          {confidenceScore !== undefined && (
            <div style={{ 
              backgroundColor: 'var(--bg-secondary)', 
              padding: 'var(--space-4)', 
              borderRadius: 'var(--radius-md)',
              marginBottom: 'var(--space-4)',
              fontSize: 'var(--font-size-sm)',
              textAlign: 'center'
            }}>
              <div style={{ fontWeight: 'var(--font-weight-bold)', fontSize: 'var(--font-size-lg)' }}>
                {(confidenceScore * 100).toFixed(1)}% Fake
              </div>
            </div>
          )}
          
          <div className="breakdown-bars">
            {[
              { label: 'Fake', pct: fakePercent, color: 'var(--gradient-danger)' },
              { label: 'Real', pct: realPercent, color: 'var(--gradient-success)' },
            ].map(({ label: l, pct, color }) => (
              <div className="breakdown-item" key={l}>
                <span className="breakdown-label">{l}</span>
                <div className="breakdown-bar">
                  <div className="progress-bar-track">
                    <div className="progress-bar-fill" style={{ width: `${pct}%`, background: color }} />
                  </div>
                </div>
                <span className="breakdown-value"
                  style={{ color: l === 'Fake' ? 'var(--color-danger)' : 'var(--color-success)' }}>
                  {pct}%
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* ✅ NEW: COMPANY VERIFICATION CARD ────────────────────────────────── */}
        {companyVerification && (
          <div className="result-company-verification card-elevated" style={{
            borderLeft: `4px solid ${companyVerification.exists ? 
              (companyVerification.status === 'ACTIVE' ? 'var(--color-success)' : 'var(--color-warning)') :
              'var(--color-danger)'}`
          }}>
            <h3>🏢 Company Verification</h3>
            
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-4)', marginTop: 'var(--space-4)' }}>
              {/* Exists Status */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
                <div style={{ fontSize: '24px' }}>
                  {companyVerification.exists ? '✅' : '❌'}
                </div>
                <div>
                  <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)' }}>Company Found</div>
                  <div style={{ fontWeight: 'var(--font-weight-bold)', color: 'var(--text-primary)' }}>
                    {companyVerification.exists ? 'Yes' : 'Not Found'}
                  </div>
                </div>
              </div>

              {/* Status */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
                <div style={{ fontSize: '24px' }}>
                  {companyVerification.status === 'ACTIVE' ? '🟢' : companyVerification.status === 'INACTIVE' ? '🟡' : '⚪'}
                </div>
                <div>
                  <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)' }}>Status</div>
                  <div style={{ fontWeight: 'var(--font-weight-bold)', color: 'var(--text-primary)' }}>
                    {companyVerification.status}
                  </div>
                </div>
              </div>
            </div>

            {companyVerification.website && (
              <div style={{ marginTop: 'var(--space-4)', padding: 'var(--space-3)', backgroundColor: 'var(--bg-secondary)', borderRadius: 'var(--radius-md)' }}>
                <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)', marginBottom: 'var(--space-1)' }}>Company Website</div>
                <a href={companyVerification.website} target="_blank" rel="noopener noreferrer" 
                   style={{ color: 'var(--color-primary)', textDecoration: 'none', wordBreak: 'break-all' }}>
                  {companyVerification.website}
                </a>
              </div>
            )}

            {companyVerification.message && (
              <div style={{ marginTop: 'var(--space-3)', fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)' }}>
                ℹ️ {companyVerification.message}
              </div>
            )}
          </div>
        )}

        {/* ✅ NEW: DOMAIN VALIDATION CARD ────────────────────────────────── */}
        {domainValidation && (
          <div className="result-domain-validation card-elevated" style={{
            borderLeft: `4px solid ${domainValidation.match ? 'var(--color-success)' : 'var(--color-danger)'}`
          }}>
            <h3>🔗 Domain Validation</h3>
            
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-4)', marginTop: 'var(--space-4)' }}>
              {/* Domain Match */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
                <div style={{ fontSize: '24px' }}>
                  {domainValidation.match ? '✅' : '⚠️'}
                </div>
                <div>
                  <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)' }}>Domain Match</div>
                  <div style={{ fontWeight: 'var(--font-weight-bold)', color: 'var(--text-primary)' }}>
                    {domainValidation.match ? 'Match' : 'Mismatch'}
                  </div>
                </div>
              </div>

              {/* Risk Score */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
                <div style={{ fontSize: '24px' }}>
                  {domainValidation.riskScore >= 0.8 ? '🟢' : domainValidation.riskScore >= 0.5 ? '🟡' : '🔴'}
                </div>
                <div>
                  <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)' }}>Risk Score</div>
                  <div style={{ fontWeight: 'var(--font-weight-bold)', color: 'var(--text-primary)' }}>
                    {(domainValidation.riskScore * 100).toFixed(0)}%
                  </div>
                </div>
              </div>
            </div>

            {/* Domain Details */}
            <div style={{ marginTop: 'var(--space-4)', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 'var(--space-3)' }}>
              {domainValidation.companyDomain && (
                <div style={{ padding: 'var(--space-3)', backgroundColor: 'var(--bg-secondary)', borderRadius: 'var(--radius-md)' }}>
                  <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)', marginBottom: 'var(--space-1)' }}>Company Domain</div>
                  <code style={{ color: 'var(--color-primary)', fontSize: 'var(--font-size-sm)' }}>{domainValidation.companyDomain}</code>
                </div>
              )}
              {domainValidation.extractedDomain && (
                <div style={{ padding: 'var(--space-3)', backgroundColor: 'var(--bg-secondary)', borderRadius: 'var(--radius-md)' }}>
                  <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)', marginBottom: 'var(--space-1)' }}>Posting Domain</div>
                  <code style={{ color: 'var(--color-primary)', fontSize: 'var(--font-size-sm)' }}>{domainValidation.extractedDomain}</code>
                </div>
              )}
            </div>

            {domainValidation.message && (
              <div style={{ marginTop: 'var(--space-3)', fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)' }}>
                ℹ️ {domainValidation.message}
              </div>
            )}
          </div>
        )}


        {/* ✅ NEW: RED FLAGS CARD ────────────────────────────────── */}
        {redFlagsDetected && redFlagsDetected.length > 0 && (
          <div className="result-red-flags card-elevated" style={{
            borderLeft: `4px solid var(--color-danger)`,
            backgroundColor: 'rgba(239, 68, 68, 0.05)'
          }}>
            <h3>🚩 Red Flags Detected</h3>
            
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)', margin: 'var(--space-4) 0', padding: 'var(--space-3)', backgroundColor: 'var(--bg-secondary)', borderRadius: 'var(--radius-md)' }}>
              <div style={{ fontSize: '32px' }}>🚩</div>
              <div>
                <div style={{ fontSize: 'var(--font-size-sm)', color: 'var(--text-secondary)' }}>Red Flag Score</div>
                <div style={{ fontWeight: 'var(--font-weight-bold)', color: 'var(--text-primary)', fontSize: 'var(--font-size-lg)' }}>
                  {(redFlagScore * 100).toFixed(1)}%
                </div>
              </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-3)' }}>
              {redFlagsDetected.map((flag, idx) => (
                <div key={idx} style={{
                  padding: 'var(--space-3)',
                  backgroundColor: 'var(--bg-secondary)',
                  borderRadius: 'var(--radius-md)',
                  borderLeft: '3px solid var(--color-danger)',
                  fontWeight: 'var(--font-weight-bold)',
                  color: 'var(--text-primary)'
                }}>
                  🚩 {flag.type}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* ══ LIME EXPLANATION PANEL ════════════════════════════ */}
        <div className="result-explanation card-elevated lime-panel">
          {/* ── Header row ─────────────────────────────── */}
          <div className="lime-panel-header">
            <h3>🔍 AI Explanation <span className="lime-panel-subtitle">(LIME)</span></h3>

            {/* Performance badge */}
            {(displayCacheStatus || displayLatencyMs != null) && (
              <div className="lime-perf-badge">
                {displayCacheStatus && (
                  <span className={`lime-cache-chip ${displayCacheStatus === 'HIT' ? 'hit' : displayCacheStatus === 'MISS' ? 'miss' : 'error'}`}>
                    {displayCacheStatus === 'HIT' ? '⚡ Cached' : displayCacheStatus === 'MISS' ? '🔄 Fresh' : '⚠️ Error'}
                  </span>
                )}
                {displayLatencyMs != null && (
                  <span className="lime-latency-chip">
                    🕐 {Math.round(displayLatencyMs)}ms
                  </span>
                )}
                {gcs_url && (
                  <span className="lime-gcs-chip" title={gcs_url}>☁️ GCS</span>
                )}
              </div>
            )}
          </div>

          {/* ── Controls row ────────────────────────────── */}
          <div className="lime-controls">
            {/* Depth selector */}
            <div className="lime-control-group">
              <label className="lime-control-label">Explanation Depth</label>
              <div className="lime-depth-pills">
                {DEPTH_OPTIONS.map(d => (
                  <button
                    key={d}
                    className={`lime-depth-pill ${numFeatures === d ? 'active' : ''}`}
                    onClick={() => handleDepthChange(d)}
                    disabled={limeLoading}
                  >
                    {d}
                  </button>
                ))}
              </div>
            </div>

            {/* Format toggle */}
            <div className="lime-control-group">
              <label className="lime-control-label">Output Format</label>
              <div className="lime-format-toggle">
                {['json', 'visual'].map(fmt => (
                  <button
                    key={fmt}
                    className={`lime-format-btn ${outputFormat === fmt ? 'active' : ''}`}
                    onClick={() => handleFormatChange(fmt)}
                  >
                    {fmt === 'json' ? '📊 Visual' : '📋 JSON'}
                  </button>
                ))}
              </div>
            </div>

            {/* Export button */}
            <button className="btn btn-ghost lime-export-btn" onClick={handleExport}>
              ⬇ Export JSON
            </button>
          </div>

          {/* ── Error message ────────────────────────────── */}
          {limeError && (
            <div className="lime-error-banner">⚠️ {limeError}</div>
          )}

          {/* ── Chart OR JSON view ──────────────────────── */}
          {outputFormat === 'json' ? (
            /* Visual Recharts bar chart */
            <LimeChart explanations={resolvedExplanations} isLoading={limeLoading} />
          ) : (
            /* Raw JSON output */
            <pre className="lime-json-output">
              {JSON.stringify(resolvedExplanations, null, 2)}
            </pre>
          )}

          {/* ── Feature count hint ──────────────────────── */}
          {resolvedExplanations?.length > 0 && !limeLoading && (
            <p className="lime-feature-hint">
              Showing top {resolvedExplanations.length} features out of {numFeatures} requested.
              Red bars indicate words that push the model toward <strong>FAKE</strong>,
              green toward <strong>REAL</strong>.
            </p>
          )}
        </div>

        {/* ── Domain Verification (existing) ───────────── */}
        {domainDetails && (
          <div className="result-domain card-elevated" style={{
            borderLeft: `4px solid ${domainDetails.riskLevel === 'Safe'
              ? 'var(--color-success)'
              : domainDetails.riskLevel === 'Suspicious'
              ? 'var(--color-warning)'
              : 'var(--color-danger)'}`
          }}>
            <h3>🌐 Domain Verification</h3>
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)', margin: 'var(--space-4) 0' }}>
              <div style={{ fontSize: 'var(--font-size-lg)', fontWeight: 'var(--font-weight-semibold)', color: 'var(--text-primary)' }}>
                {domainDetails.domainName}
              </div>
              <span className={`badge badge-${domainDetails.riskLevel === 'Safe' ? 'success' : domainDetails.riskLevel === 'Suspicious' ? 'warning' : 'danger'}`}>
                {domainDetails.riskLevel === 'Safe' ? '✅ ' : domainDetails.riskLevel === 'Suspicious' ? '⚠️ ' : '🚨 '}
                {domainDetails.riskLevel}
              </span>
            </div>
            <ul style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-2)' }}>
              {domainDetails.explanations.map((txt, idx) => (
                <li key={idx} style={{ display: 'flex', gap: 'var(--space-2)', color: 'var(--text-muted)' }}>
                  <span style={{ color: domainDetails.riskLevel === 'Safe' ? 'var(--color-success)' : 'var(--color-warning)', marginTop: '2px' }}>
                    {domainDetails.riskLevel === 'Safe' ? '✓' : '•'}
                  </span>
                  <span>{txt}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* ── Actions ──────────────────────────────────── */}
        <div className="result-actions" style={{ marginTop: 'var(--space-8)' }}>
          <button className="btn btn-primary btn-lg" onClick={() => navigate('/analyze')}>
            Analyze Another Job
          </button>
          <button className="btn btn-secondary btn-lg" onClick={() => navigate('/report')}>
            Report This Job
          </button>
          <button className="btn btn-ghost btn-lg" onClick={() => navigate('/dashboard')}>
            View Dashboard
          </button>
        </div>
      </div>
    </div>
  );
};

export default ResultPage;