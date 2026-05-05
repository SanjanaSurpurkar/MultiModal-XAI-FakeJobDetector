import React from 'react';
import { useNavigate } from 'react-router-dom';
import '../styles/LandingPage.css';

const LandingPage = () => {
  const navigate = useNavigate();

  const features = [
    {
      icon: '📝',
      type: 'text',
      title: 'Text Analysis',
      description: 'Paste any job description and our AI will analyze language patterns, suspicious phrasing, and red flags instantly.',
    },
    {
      icon: '🎙️',
      type: 'audio',
      title: 'Audio Detection',
      description: 'Upload audio recordings of job calls to detect scripted pitches, urgency tactics, and voice manipulation.',
    },
    {
      icon: '🖼️',
      type: 'image',
      title: 'Image Analysis',
      description: 'Submit images or screenshots for deep analysis of visual content, presentation patterns, and authenticity verification.',
    },
    {
      icon: '📄',
      type: 'document',
      title: 'Document Scanning',
      description: 'Upload screenshots, PDFs, or documents to extract and analyze job posting content with OCR technology.',
    },
  ];

  const stats = [
    { number: '97.84%', label: 'Detection Accuracy' },
    { number: '50+', label: 'Jobs Analyzed' },
    { number: '< 20s', label: 'Average Response' },
    { number: '10+', label: 'Fake Jobs Caught' },
  ];


  return (
    <div className="landing-page">
      {/* Hero Section */}
      <section className="landing-hero">
        <div className="container">
          <div className="landing-hero-badge">
            🛡️ AI-Powered Job Scams Detection
          </div>
          <h1>
            Detect <span className="text-gradient">Fake Jobs</span><br />
            Before You Apply
          </h1>
          <p>
            Shield yourself from fraudulent job postings using advanced multi-modal AI that analyzes text, audio, image, and documents in seconds.
          </p>
          <div className="landing-hero-actions">
            <button className="btn btn-primary btn-lg" onClick={() => navigate('/analyze')}>
              Start Analyzing →
            </button>
            <button className="btn btn-secondary btn-lg" onClick={() => navigate('/signup')}>
              Create Free Account
            </button>
          </div>
        </div>
      </section>

      {/* Corporate Jobs Notice */}
      <section className="container" style={{ marginTop: '3rem', marginBottom: '2rem' }}>
        <div style={{
          backgroundColor: '#EFF6FF',
          border: '2px solid #0EA5E9',
          borderRadius: '0.75rem',
          padding: '1.5rem',
          textAlign: 'center'
        }}>
          <div style={{ fontSize: '1.25rem', fontWeight: 'bold', color: '#0369A1', marginBottom: '0.5rem' }}>
            💼 Corporate Job Roles Only
          </div>
          <p style={{ color: '#0369A1', margin: 0 }}>
            This AI detection system is optimized for analyzing corporate job positions. For freelance, gig work, internships, or non-traditional roles, results may vary.
          </p>
        </div>
      </section>

      {/* Stats */}
      <section className="container">
        <div className="landing-stats stagger-children">
          {stats.map((stat, i) => (
            <div key={i} className="landing-stat">
              <div className="landing-stat-number">{stat.number}</div>
              <div className="landing-stat-label">{stat.label}</div>
            </div>
          ))}
        </div>
      </section>

      {/* Features */}
      <section className="landing-features">
        <div className="container">
          <div className="landing-features-header">
            <h2>Multi-Modal Detection</h2>
            <p>Analyze job postings across every medium with our comprehensive AI toolkit.</p>
          </div>
          <div className="landing-features-grid stagger-children">
            {features.map((feature, i) => (
              <div key={i} className="feature-card">
                <div className={`feature-icon ${feature.type}`}>{feature.icon}</div>
                <h3>{feature.title}</h3>
                <p>{feature.description}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="landing-cta">
        <div className="container">
          <div className="landing-cta-card">
            <h2>Ready to Stay Safe?</h2>
            <p>Start analyzing job postings for free. No credit card required.</p>
            <button className="btn btn-primary btn-lg" onClick={() => navigate('/analyze')}>
              Analyze a Job Posting →
            </button>
          </div>
        </div>
      </section>
    </div>
  );
};

export default LandingPage;
