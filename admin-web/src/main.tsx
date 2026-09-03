import React from 'react';
import ReactDOM from 'react-dom/client';
import 'pretendard/dist/web/variable/pretendardvariable.css';
import '../app/globals.css';
import Home from '../app/page';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Home />
  </React.StrictMode>,
);
