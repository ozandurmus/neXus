import Button from "@mui/material/Button";

/**
 * Skeleton root component. B1-1 seeds the frontend module only; the
 * real screens are a later slice. Uses one MUI component to prove the
 * theme/component wiring compiles and builds.
 */
export function App() {
  return (
    <main>
      <h1>UI 2.0</h1>
      <Button variant="contained">Ready</Button>
    </main>
  );
}
