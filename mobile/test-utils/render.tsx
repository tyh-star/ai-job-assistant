import type { ReactElement, ReactNode } from 'react'
import { render } from '@testing-library/react-native'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

/** A fresh cache per test: no retries, and no garbage-collection timers left running. */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      // Finished mutations are otherwise kept for 5 minutes on a timer that holds the worker open.
      mutations: { retry: false, gcTime: Infinity },
    },
  })
}

export async function renderWithQuery(ui: ReactElement, client = createTestQueryClient()) {
  function QueryProvider({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  await render(ui, { wrapper: QueryProvider })
  return client
}
