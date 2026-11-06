import { useEffect, useState } from 'react';

type Backend = 'checking' | 'UP' | 'unreachable';

// Minimal skeleton: design system, router and i18n arrive in P1.10.
export default function App() {
  const [backend, setBackend] = useState<Backend>('checking');

  useEffect(() => {
    let live = true;
    fetch('/actuator/health')
      .then((r) => r.json() as Promise<{ status?: string }>)
      .then((b) => live && setBackend(b.status === 'UP' ? 'UP' : 'unreachable'))
      .catch(() => live && setBackend('unreachable'));
    return () => {
      live = false;
    };
  }, []);

  return (
    <main>
      <h1>English9x</h1>
      <p>{backend === 'checking' ? 'Checking backend…' : `Backend: ${backend}`}</p>
    </main>
  );
}
