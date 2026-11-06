import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import App from './App';

describe('App', () => {
  it('shows the brand heading', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})));
    render(<App />);
    expect(screen.getByRole('heading', { name: 'English9x' })).toBeInTheDocument();
  });

  it('reports backend status UP from /actuator/health', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ status: 'UP' }), { status: 200 })));
    render(<App />);
    expect(await screen.findByText('Backend: UP')).toBeInTheDocument();
  });

  it('reports backend unreachable when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('down'); }));
    render(<App />);
    expect(await screen.findByText('Backend: unreachable')).toBeInTheDocument();
  });
});
