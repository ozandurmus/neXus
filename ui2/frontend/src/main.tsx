import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
import { AuthGate } from "./auth/AuthGate";
import { installTokenStyles } from "./theme/m3Theme";

// Build-time entry point; the compiled output is a static bundle that
// `service` serves. No runtime Node process is started from here.
//
// AuthGate wraps every product screen this build ships: the application
// cannot be used without logging in, even on localhost (this movement's own
// stated goal). App.tsx and its own tests are deliberately unaware of this --
// they render the already-authenticated shell, which AuthGate is what
// decides to mount.
installTokenStyles();
const container = document.getElementById("root");
if (container) {
  ReactDOM.createRoot(container).render(
    <React.StrictMode>
      <AuthGate>
        <App />
      </AuthGate>
    </React.StrictMode>,
  );
}
