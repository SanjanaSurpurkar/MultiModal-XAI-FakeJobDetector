import React, { useState, useMemo } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useToast } from './shared/Toast';
import { useAuth } from '../context/AuthContext';
import { signupUser } from '../services/api';
import '../styles/Auth.css';

const SignUp = () => {
  const navigate = useNavigate();
  const { addToast } = useToast();
  const [form, setForm] = useState({
    name: '',
    email: '',
    password: '',
    confirmPassword: '',
  });
  const [showPassword, setShowPassword] = useState(false);
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();

  const handleChange = (e) => {
    setForm({ ...form, [e.target.name]: e.target.value });
    if (errors[e.target.name]) {
      setErrors({ ...errors, [e.target.name]: '' });
    }
  };

  const passwordStrength = useMemo(() => {
    const p = form.password;
    if (!p) return { level: 0, text: '', className: '' };
    let score = 0;
    if (p.length >= 6) score++;
    if (p.length >= 10) score++;
    if (/[A-Z]/.test(p) && /[a-z]/.test(p)) score++;
    if (/\d/.test(p)) score++;
    if (/[^A-Za-z0-9]/.test(p)) score++;

    if (score <= 2) return { level: 1, text: 'Weak', className: 'weak' };
    if (score <= 3) return { level: 2, text: 'Medium', className: 'medium' };
    return { level: 3, text: 'Strong', className: 'strong' };
  }, [form.password]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    const newErrors = {};

    if (!form.name.trim()) newErrors.name = 'Name is required';
    if (!form.email.trim()) newErrors.email = 'Email is required';
    if (!form.password) newErrors.password = 'Password is required';
    else if (form.password.length < 6) newErrors.password = 'Must be at least 6 characters';
    if (form.password !== form.confirmPassword) newErrors.confirmPassword = 'Passwords do not match';
    if (!agreedToTerms) newErrors.terms = 'You must agree to the terms';

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      addToast('Please fix the errors below', 'error');
      return;
    }

    setLoading(true);
    try {
      const user = await signupUser(form.name, form.email, form.password);
      login(user);
      addToast('Account created successfully!', 'success');
      navigate('/dashboard');
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to create account.';
      addToast(msg, 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-container">
        {/* Left Panel */}
        <div className="auth-panel">
          <div className="auth-panel-content">
            <h2>Join JobSatark Today</h2>
            <p>Create your free account and start protecting yourself from fake job postings.</p>
            <div className="auth-panel-features">
              <div className="auth-panel-feature">
                <span>✨</span>
                <span>Free unlimited job analysis</span>
              </div>
              <div className="auth-panel-feature">
                <span>📈</span>
                <span>Personal analytics dashboard</span>
              </div>
              <div className="auth-panel-feature">
                <span>🔔</span>
                <span>Real-time detection</span>
              </div>
            </div>
          </div>
        </div>

        {/* Form Panel */}
        <div className="auth-form-panel">
          <div className="auth-form-header">
            <h2>Create Account</h2>
            <p>Fill in your details to get started</p>
          </div>

          <form className="auth-form" onSubmit={handleSubmit}>
            <div className="input-group">
              <label className="input-label" htmlFor="signup-name">Full Name</label>
              <input
                id="signup-name"
                className="input-field"
                type="text"
                name="name"
                placeholder="John Doe"
                value={form.name}
                onChange={handleChange}
              />
              {errors.name && <span className="auth-error">{errors.name}</span>}
            </div>

            <div className="input-group">
              <label className="input-label" htmlFor="signup-email">Email</label>
              <input
                id="signup-email"
                className="input-field"
                type="email"
                name="email"
                placeholder="you@example.com"
                value={form.email}
                onChange={handleChange}
              />
              {errors.email && <span className="auth-error">{errors.email}</span>}
            </div>

            <div className="input-group">
              <label className="input-label" htmlFor="signup-password">Password</label>
              <div className="password-wrapper">
                <input
                  id="signup-password"
                  className="input-field"
                  type={showPassword ? 'text' : 'password'}
                  name="password"
                  placeholder="Create a password"
                  value={form.password}
                  onChange={handleChange}
                />
                <button
                  type="button"
                  className="password-toggle"
                  onClick={() => setShowPassword(!showPassword)}
                >
                  {showPassword ? 'Hide' : 'Show'}
                </button>
              </div>
              {form.password && (
                <>
                  <div className="password-strength">
                    {[1, 2, 3].map(i => (
                      <div
                        key={i}
                        className={`password-strength-bar ${i <= passwordStrength.level ? `filled ${passwordStrength.className}` : ''}`}
                      />
                    ))}
                  </div>
                  <div className={`password-strength-text ${passwordStrength.className}`}>
                    {passwordStrength.text}
                  </div>
                </>
              )}
              {errors.password && <span className="auth-error">{errors.password}</span>}
            </div>

            <div className="input-group">
              <label className="input-label" htmlFor="signup-confirm">Confirm Password</label>
              <input
                id="signup-confirm"
                className="input-field"
                type="password"
                name="confirmPassword"
                placeholder="Confirm your password"
                value={form.confirmPassword}
                onChange={handleChange}
              />
              {errors.confirmPassword && <span className="auth-error">{errors.confirmPassword}</span>}
            </div>

            <div className="auth-terms">
              <input
                type="checkbox"
                checked={agreedToTerms}
                onChange={(e) => setAgreedToTerms(e.target.checked)}
                id="terms-check"
              />
              <label htmlFor="terms-check">
                I agree to the <a href="#terms">Terms of Service</a> and <a href="#privacy">Privacy Policy</a>
              </label>
            </div>
            {errors.terms && <span className="auth-error">{errors.terms}</span>}

            <button
              type="submit"
              className="btn btn-primary btn-full"
              disabled={loading}
            >
              {loading ? 'Creating Account...' : 'Create Account'}
            </button>
          </form>

          <div className="auth-footer">
            Already have an account? <Link to="/signin">Sign In</Link>
          </div>
        </div>
      </div>
    </div>
  );
};

export default SignUp;
