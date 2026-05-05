import React, { useState, useEffect } from 'react';
import { useNavigate, Navigate } from 'react-router-dom';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useAuth } from '../context/AuthContext';
import { getUserDashboard2 } from '../services/api';
import '../styles/Dashboard.css';

const Dashboard = () => {
  const navigate = useNavigate();
  const { user } = useAuth();
  
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('all');
  
  const [stats, setStats] = useState({ total: 0, fake: 0, real: 0, avgConfidence: 0 });
  const [history, setHistory] = useState([]);
  const [loading, setLoading] = useState(true);

  // Re-map the raw history data into a chart-friendly format
  const [chartData, setChartData] = useState([]);

  useEffect(() => {
    const fetchDashboard = async () => {
      if (!user) return;
      try {
        const actualUserId = user.userId || user.id || user._id;
        const data = await getUserDashboard2(actualUserId);
        setStats(data.statistics);
        setHistory(data.recentResults);
        
        // Very basic aggregation for chart (group by month string derived from ISO localdatetime)
        const grouped = data.recentResults.reduce((acc, curr) => {
          const date = new Date(curr.createdAt);
          const month = date.toLocaleString('default', { month: 'short' });
          if (!acc[month]) acc[month] = { fake: 0, real: 0 };
          
          if (curr.prediction?.toUpperCase() === 'FAKE') {
            acc[month].fake += 1;
          } else {
            acc[month].real += 1;
          }
          return acc;
        }, {});
        
        const cData = Object.keys(grouped).map(key => ({
          month: key,
          fake: grouped[key].fake,
          real: grouped[key].real
        }));
        cData.sort((a,b) => {
           const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
           return months.indexOf(a.month) - months.indexOf(b.month);
        });
        setChartData(cData);

      } catch (err) {
        console.error("Failed to load dashboard data", err);
      } finally {
        setLoading(false);
      }
    };
    
    fetchDashboard();
  }, [user]);

  // Protect route
  if (!user) {
    return <Navigate to="/signin" replace />;
  }

  const filteredHistory = history.filter(item => {
    // Search on originalInputPreview
    const snippet = item.originalInputPreview ? item.originalInputPreview.toLowerCase() : 'Job Analysis';
    const matchesSearch = snippet.includes(search.toLowerCase());
    const filterNorm = filter.toUpperCase();
    const matchesFilter = filter === 'all' || item.prediction?.toUpperCase() === filterNorm;
    return matchesSearch && matchesFilter;
  });

  if (loading) {
    return (
      <div className="dashboard-page" style={{display: 'flex', justifyContent: 'center', alignItems: 'center'}}>
        <h2>Loading Dashboard...</h2>
      </div>
    );
  }

  return (
    <div className="dashboard-page">
      <div className="container">
        <div className="dashboard-header">
          <h1>Dashboard</h1>
          <button className="btn btn-primary" onClick={() => navigate('/analyze')}>
            + New Analysis
          </button>
        </div>

        {/* Chart */}
        <div className="dashboard-chart card-elevated">
          <h3>📈 Detection History</h3>
          <div className="dashboard-chart-container">
            {chartData.length > 0 ? (
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={chartData} barCategoryGap="20%">
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.06)" />
                <XAxis dataKey="month" stroke="var(--text-muted)" fontSize={12} />
                <YAxis stroke="var(--text-muted)" fontSize={12} />
                <Tooltip
                  contentStyle={{
                    background: 'var(--bg-elevated)',
                    border: '1px solid var(--border-subtle)',
                    borderRadius: 'var(--radius-md)',
                    color: 'var(--text-primary)',
                  }}
                />
                <Bar dataKey="fake" fill="#ef4444" radius={[4, 4, 0, 0]} name="Fake" />
                <Bar dataKey="real" fill="#22c55e" radius={[4, 4, 0, 0]} name="Real" />
              </BarChart>
            </ResponsiveContainer>
            ) : (
               <div style={{color: 'var(--text-muted)', textAlign: 'center', paddingTop: '4rem'}}>Not enough data yet to visualize.</div>
            )}
          </div>
        </div>

        {/* History */}
        <div className="dashboard-history card-elevated">
          <div className="dashboard-history-header">
            <h3>📋 Recent Analyses</h3>
            <input
              className="input-field dashboard-search"
              type="text"
              placeholder="Search analyses..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>

          <div className="dashboard-filters">
            {['all', 'fake', 'real'].map(f => (
              <button
                key={f}
                className={`filter-btn ${filter === f ? 'active' : ''}`}
                onClick={() => setFilter(f)}
              >
                {f === 'all' ? 'All' : f === 'fake' ? '⚠️ Fake' : '✅ Real'}
              </button>
            ))}
          </div>

          <div className="table-container">
            <table className="table">
              <thead>
                <tr>
                  <th>Job Input</th>
                  <th>Date</th>
                  <th>Result</th>
                  <th>Confidence</th>
                  <th>Red Flags</th>
                </tr>
              </thead>
              <tbody>
                {filteredHistory.map(item => (
                  <tr key={item.id}>
                    <td style={{ color: 'var(--text-primary)', fontWeight: 500 }}>
                      {item.originalInputPreview || 'Job Analysis'}
                    </td>
                    <td>{new Date(item.createdAt).toLocaleDateString()}</td>
                    <td>
                      <span className={`badge ${item.prediction?.toUpperCase() === 'FAKE' ? 'badge-danger' : 'badge-success'}`}>
                        {item.prediction?.toUpperCase() === 'FAKE' ? '⚠️ Fake' : '✅ Real'}
                      </span>
                    </td>
                    <td>
                      <span style={{ fontWeight: 600, color: (item.confidenceScore * 100) > 50 ? 'var(--color-danger)' : 'var(--color-success)' }}>
                        {(item.confidenceScore * 100).toFixed(1)}%
                      </span>
                    </td>
                    <td>
                      {item.redFlagsDetected && item.redFlagsDetected.length > 0 ? (
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                          <span style={{ fontSize: '18px' }}>🚩</span>
                          <span style={{ fontWeight: 600, color: 'var(--color-danger)' }}>
                            {item.redFlagsDetected.length} flag{item.redFlagsDetected.length > 1 ? 's' : ''}
                          </span>
                        </div>
                      ) : (
                        <span style={{ color: 'var(--text-muted)' }}>No flags</span>
                      )}
                    </td>
                  </tr>
                ))}
                {filteredHistory.length === 0 && (
                  <tr>
                    <td colSpan="6" style={{ textAlign: 'center', padding: 'var(--space-8)', color: 'var(--text-muted)' }}>
                      No analyses found matching your search.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Dashboard;
