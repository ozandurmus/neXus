import { useEffect, useRef } from "react";

// Decorative topology background for the sign-in page: nodes drift, pulses
// travel along links and a node glows when a pulse arrives. About 30 fps, paused
// while the tab is hidden, and a single static frame under prefers-reduced-motion.
// It is an illustration only; it never reflects real estate state.

interface Node { x: number; y: number; vx: number; vy: number; r: number; glow: number; deg: number }
interface Pulse { a: number; b: number; t: number; speed: number; teal: boolean }

const NODE_COUNT = 64;
const LINK_RADIUS = 190;
const MAX_DEGREE = 4;
const FRAME_MS = 33;
const TWO_PI = Math.PI * 2;

function rand(a: number, b: number) {
  return a + Math.random() * (b - a);
}

function buildGraph(width: number, height: number) {
  const nodes: Node[] = [];
  const edges: Array<[number, number]> = [];
  for (let i = 0; i < NODE_COUNT; i++) {
    const c = Math.random() < 0.5 ? [0.28, 0.42] : [0.52, 0.62];
    nodes.push({
      x: (c[0] + rand(-0.24, 0.24)) * width,
      y: (c[1] + rand(-0.36, 0.36)) * height,
      vx: rand(-0.05, 0.05),
      vy: rand(-0.05, 0.05),
      r: Math.random() < 0.12 ? 3.2 : 1.8,
      glow: 0,
      deg: 0,
    });
  }
  for (let a = 0; a < NODE_COUNT; a++) {
    const cand: Array<[number, number]> = [];
    for (let b = 0; b < NODE_COUNT; b++) {
      if (a === b) continue;
      const d = Math.hypot(nodes[a].x - nodes[b].x, nodes[a].y - nodes[b].y);
      if (d < LINK_RADIUS) cand.push([d, b]);
    }
    cand.sort((p, q) => p[0] - q[0]);
    for (let k = 0; k < cand.length && nodes[a].deg < MAX_DEGREE; k++) {
      const b = cand[k][1];
      if (b > a && nodes[b].deg < MAX_DEGREE) {
        edges.push([a, b]);
        nodes[a].deg++;
        nodes[b].deg++;
      }
    }
  }
  return { nodes, edges };
}

export function ConstellationCanvas() {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;

    const reduceQuery = typeof window.matchMedia === "function" ? window.matchMedia("(prefers-reduced-motion: reduce)") : null;
    let width = 0;
    let height = 0;
    let dpr = 1;
    let nodes: Node[] = [];
    let edges: Array<[number, number]> = [];
    let pulses: Pulse[] = [];
    let last = 0;
    let raf = 0;
    let resizeTimer: ReturnType<typeof setTimeout> | undefined;

    const reduced = () => reduceQuery?.matches === true;

    function build() {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      width = window.innerWidth;
      height = window.innerHeight;
      canvas!.width = Math.floor(width * dpr);
      canvas!.height = Math.floor(height * dpr);
      ({ nodes, edges } = buildGraph(width, height));
      pulses = [];
    }

    function spawn() {
      if (!edges.length) return;
      const e = edges[Math.floor(Math.random() * edges.length)];
      const dir = Math.random() < 0.5;
      pulses.push({ a: dir ? e[0] : e[1], b: dir ? e[1] : e[0], t: 0, speed: rand(0.35, 0.7), teal: Math.random() < 0.5 });
    }

    function step(ts: number) {
      raf = requestAnimationFrame(step);
      if (ts - last < FRAME_MS) return;
      const dt = Math.min(0.1, (ts - last) / 1000);
      last = ts;
      const c = ctx!;
      c.setTransform(dpr, 0, 0, dpr, 0, 0);
      c.clearRect(0, 0, width, height);

      const xMax = width < 900 ? width - 20 : width * 0.78;
      for (const n of nodes) {
        n.x += n.vx;
        n.y += n.vy;
        if (n.x < 20 || n.x > xMax) n.vx *= -1;
        if (n.y < 20 || n.y > height - 20) n.vy *= -1;
        n.glow = Math.max(0, n.glow - dt * 1.4);
      }
      c.lineWidth = 1;
      c.strokeStyle = "rgba(123,156,255,0.11)";
      for (const [ia, ib] of edges) {
        c.beginPath();
        c.moveTo(nodes[ia].x, nodes[ia].y);
        c.lineTo(nodes[ib].x, nodes[ib].y);
        c.stroke();
      }
      if (Math.random() < dt * 9) spawn();
      for (let k = pulses.length - 1; k >= 0; k--) {
        const u = pulses[k];
        u.t += dt * u.speed;
        const A = nodes[u.a];
        const B = nodes[u.b];
        if (u.t >= 1) {
          B.glow = 1;
          pulses.splice(k, 1);
          continue;
        }
        const x = A.x + (B.x - A.x) * u.t;
        const y = A.y + (B.y - A.y) * u.t;
        const col = u.teal ? "56,210,230" : "123,156,255";
        const t0 = Math.max(0, u.t - 0.18);
        const x0 = A.x + (B.x - A.x) * t0;
        const y0 = A.y + (B.y - A.y) * t0;
        const g = c.createLinearGradient(x0, y0, x, y);
        g.addColorStop(0, `rgba(${col},0)`);
        g.addColorStop(1, `rgba(${col},.8)`);
        c.strokeStyle = g;
        c.lineWidth = 1.6;
        c.beginPath();
        c.moveTo(x0, y0);
        c.lineTo(x, y);
        c.stroke();
        c.fillStyle = `rgba(${col},1)`;
        c.beginPath();
        c.arc(x, y, 1.8, 0, TWO_PI);
        c.fill();
      }
      for (const nd of nodes) {
        if (nd.glow > 0) {
          const rg = c.createRadialGradient(nd.x, nd.y, 0, nd.x, nd.y, 18);
          rg.addColorStop(0, `rgba(56,210,230,${0.35 * nd.glow})`);
          rg.addColorStop(1, "rgba(56,210,230,0)");
          c.fillStyle = rg;
          c.beginPath();
          c.arc(nd.x, nd.y, 18, 0, TWO_PI);
          c.fill();
        }
        c.fillStyle = `rgba(238,241,245,${0.45 + 0.55 * nd.glow})`;
        c.beginPath();
        c.arc(nd.x, nd.y, nd.r, 0, TWO_PI);
        c.fill();
      }
    }

    function staticFrame() {
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx!.clearRect(0, 0, width, height);
      ctx!.lineWidth = 1;
      ctx!.strokeStyle = "rgba(123,156,255,0.11)";
      for (const [ia, ib] of edges) {
        ctx!.beginPath();
        ctx!.moveTo(nodes[ia].x, nodes[ia].y);
        ctx!.lineTo(nodes[ib].x, nodes[ib].y);
        ctx!.stroke();
      }
      ctx!.fillStyle = "rgba(238,241,245,.55)";
      for (const n of nodes) {
        ctx!.beginPath();
        ctx!.arc(n.x, n.y, n.r, 0, TWO_PI);
        ctx!.fill();
      }
    }

    function stop() {
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
    }

    function play() {
      stop();
      if (reduced() || document.hidden) return;
      last = 0;
      raf = requestAnimationFrame(step);
    }

    function start() {
      stop();
      build();
      if (reduced()) staticFrame();
      else play();
    }

    const onResize = () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(start, 150);
    };
    const onVisibility = () => {
      if (document.hidden) stop();
      else play();
    };

    start();
    window.addEventListener("resize", onResize);
    document.addEventListener("visibilitychange", onVisibility);
    reduceQuery?.addEventListener?.("change", start);
    return () => {
      stop();
      clearTimeout(resizeTimer);
      window.removeEventListener("resize", onResize);
      document.removeEventListener("visibilitychange", onVisibility);
      reduceQuery?.removeEventListener?.("change", start);
    };
  }, []);

  return <canvas ref={ref} className="lx-sky" aria-hidden="true" data-testid="constellation-canvas" />;
}
