import React, { useState, useRef } from 'react';
import '../../styles/FileUploadZone.css';

const fileTypeConfig = {
  image: { icon: '🖼️', accept: '.png,.jpg,.jpeg,.gif,.webp', label: 'Image' },
  audio: { icon: '🎵', accept: '.wav,.mp3,.m4a,.aac,.ogg', label: 'Audio' },
  document: { icon: '📄', accept: '.txt,.pdf,.doc,.docx', label: 'Document' },
};

/**
 * Format bytes into a human-readable file size (e.g., 2 MB).
 */
const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
};

/**
 * FileUploadZone Component
 * Renders a drag-and-drop file upload area and handles file selection via click.
 * Automatically tries to detect the file type based on extension.
 * 
 * @param {Object} props
 * @param {File} props.file - The currently selected file
 * @param {Function} props.setFile - State setter for the file
 * @param {string} props.fileType - The currently selected or detected file type
 * @param {Function} props.setFileType - State setter for the file type
 */
const FileUploadZone = ({ file, setFile, fileType, setFileType }) => {
  const [dragOver, setDragOver] = useState(false);
  // Ref to the actual hidden file input element for triggering click programmatically
  const inputRef = useRef(null);

  const handleDragOver = (e) => {
    e.preventDefault();
    setDragOver(true);
  };

  const handleDragLeave = () => setDragOver(false);

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    const droppedFile = e.dataTransfer.files[0];
    if (droppedFile) {
      setFile(droppedFile);
      autoDetectType(droppedFile);
    }
  };

  const handleFileChange = (e) => {
    const selected = e.target.files[0];
    if (selected) {
      setFile(selected);
      autoDetectType(selected);
    }
  };

  /**
   * Automatically detects the file type (image, audio, video, document)
   * by checking the file extension against known lists and updates state.
   */
  const autoDetectType = (f) => {
    const ext = f.name.split('.').pop().toLowerCase();
    if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) setFileType('image');
    else if (['wav', 'mp3', 'm4a', 'aac', 'ogg'].includes(ext)) setFileType('audio');
    else if (['txt', 'pdf', 'doc', 'docx'].includes(ext)) setFileType('document');
  };

  const allAccept = Object.values(fileTypeConfig).map(c => c.accept).join(',');

  return (
    <div>
      <div
        className={`upload-zone ${dragOver ? 'drag-over' : ''}`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
      >
        <input
          ref={inputRef}
          type="file"
          accept={allAccept}
          onChange={handleFileChange}
          style={{ display: 'none' }}
        />
        <div className="upload-zone-icon">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
          </svg>
        </div>
        <h4>Drop your file here, or <span className="browse-link">browse</span></h4>
        <p>Supports images, audio, and documents</p>
      </div>

      {file && (
        <div className="upload-file-preview">
          <div className={`upload-file-icon ${fileType}`}>
            {fileTypeConfig[fileType]?.icon || '📎'}
          </div>
          <div className="upload-file-info">
            <div className="upload-file-name">{file.name}</div>
            <div className="upload-file-size">{formatFileSize(file.size)}</div>
          </div>
          <button
            className="upload-file-remove"
            onClick={(e) => { e.stopPropagation(); setFile(null); }}
            aria-label="Remove file"
          >
            ✕
          </button>
        </div>
      )}

      <div className="upload-type-selector">
        {Object.entries(fileTypeConfig).map(([key, config]) => (
          <button
            key={key}
            className={`upload-type-btn ${fileType === key ? 'active' : ''}`}
            onClick={() => setFileType(key)}
            type="button"
          >
            {config.icon} {config.label}
          </button>
        ))}
      </div>
    </div>
  );
};

export default FileUploadZone;
