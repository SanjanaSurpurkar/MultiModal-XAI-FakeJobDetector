# MultiModal-XAI-FakeJobDetector
An XAI-powered hybrid framework for detecting fake job postings using multimodal data, including text, images, and audio. Leveraging explainable AI techniques, this project provides accurate, transparent, and interpretable predictions to identify fraudulent job listings across multiple media types.

# Frontend

# Commands

* cd frontend
* npm install
* npm run dev

# LIME Microservice

A Python Flask microservice that generates **LIME (Local Interpretable Model-agnostic Explanations)** for the Fake Job Detection application.

## Commands

cd lime-service

# Create virtual environment
python -m venv venv
<br>
venv\Scripts\activate        # Windows
# source venv/bin/activate   # Linux/Mac

# Install dependencies
pip install -r requirements.txt

# Start the service
python app.py
```

The service starts on `http://localhost:5001` by default.

## Endpoints

### `POST /explain`
Generate a LIME explanation for job description text.

**Request:**
```json
{
  "text": "Work from home earn $5000 weekly no experience needed",
  "num_features": 10,
  "output_format": "json",
  "job_id": "optional-tracking-id"
}
```

**Response:**
```json
{
  "success": true,
  "job_id": "abc123",
  "explanation": [
    {"word": "earn", "weight": 0.142},
    {"word": "home", "weight": 0.098},
    {"word": "experience", "weight": -0.045}
  ],
  "num_features": 10,
  "cache_status": "MISS",
  "latency_ms": 850.2,
  "output_format": "json",
  "gcs_url": "gs://your-bucket/explanations/abc123.json"
}
```

### `GET /health`
Service health, cache statistics, and performance metrics.

### `POST /cache/clear`
Clear the in-memory LRU cache.

## Surrogate Model

Since no scikit-learn `.pkl` file is available, the service trains a **TF-IDF + Logistic Regression** surrogate model at startup on a curated dataset of real and fake job posting examples. The surrogate learns patterns typical of fake jobs (wage promises, upfront fees, vague requirements) vs. real ones. LIME then explains **this surrogate's decisions** on a per-prediction basis.

# audio-service

# commands

* cd audio-service
* pip install -r requirements.txt
* python audio_service.py

# Backend

# Commands

* Add model.pmml in path /backend/src/main/resources
* cd backend
* mvn clean install
* mvn spring-boot:run
