import { useState } from 'react';
import catImage from '../assets/images/cat.svg';
import '../styles/animations.css';

function DancingCat() {
  const [isAnimating, setIsAnimating] = useState(true);

  const toggleAnimation = () => {
    setIsAnimating(!isAnimating);
  };

  return (
    <div className="dancing-cat-container">
      <h1 className="title">Dancing Cat</h1>
      <div className={`cat-wrapper ${isAnimating ? 'dancing' : 'paused'}`}>
        <img src={catImage} alt="Dancing Cat" className="cat-image" />
      </div>
      <button
        className="control-button"
        onClick={toggleAnimation}
        aria-label={isAnimating ? 'Pause animation' : 'Play animation'}
      >
        {isAnimating ? 'Pause' : 'Play'}
      </button>
      <p className="instruction">Click the button to {isAnimating ? 'pause' : 'play'} the dance!</p>
    </div>
  );
}

export default DancingCat;
