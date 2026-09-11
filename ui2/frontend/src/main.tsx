import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";

// Build-time entry point; the compiled output is a static bundle that
// `service` serves. No runtime Node process is started from here.
const container = document.getElementById("root");
if (container) {
  ReactDOM.createRoot(container).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
}
