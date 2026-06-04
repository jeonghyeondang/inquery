/**
 * Data Catalog store - Svelte 5 Runes
 * Manages global AI collection progress state
 * so the floating progress panel persists across page navigation and refresh.
 */

import catalogService from '$lib/service/catalog';

export interface CollectionProgress {
  current: number;
  total: number;
  percent: number;
  currentTable: string;
  successCount: number;
  failCount: number;
  schemaName: string;
  databaseName: string;
}

export interface StoredCollectionJob {
  jobId: string;
  databaseName: string;
  schemaName: string;
  connectionId: number;
  startedAt: number;
}

const STORAGE_KEY = 'inquery-collection-jobs';

// ─── Collection state ───
let bulkCollecting = $state(false);
let showCollectionProgress = $state(false);
let collectionProgressList = $state<CollectionProgress[]>([]);
let collectionAbortController = $state<AbortController | null>(null);

// ─── Derived ───
// These are computed inline in components since $derived can't be exported directly as reactive in this pattern.

export function getCollectionStore() {
  return {
    get bulkCollecting() {
      return bulkCollecting;
    },
    get showCollectionProgress() {
      return showCollectionProgress;
    },
    get collectionProgressList() {
      return collectionProgressList;
    },
    get collectionAbortController() {
      return collectionAbortController;
    },
    get totalSuccess() {
      return collectionProgressList.reduce((s, p) => s + p.successCount, 0);
    },
    get totalFail() {
      return collectionProgressList.reduce((s, p) => s + p.failCount, 0);
    },
    get totalCount() {
      return collectionProgressList.reduce((s, p) => s + p.total, 0);
    },
    get completedSchemas() {
      return collectionProgressList.filter(
        (p) => p.total > 0 && p.current >= p.total,
      );
    },
  };
}

export function setBulkCollecting(value: boolean) {
  bulkCollecting = value;
}

export function setShowCollectionProgress(value: boolean) {
  showCollectionProgress = value;
}

export function setCollectionProgressList(value: CollectionProgress[]) {
  collectionProgressList = value;
}

export function updateCollectionProgress(
  index: number,
  data: CollectionProgress,
) {
  collectionProgressList[index] = data;
  collectionProgressList = [...collectionProgressList];
}

export function setCollectionAbortController(
  controller: AbortController | null,
) {
  collectionAbortController = controller;
}

export function cancelCollection() {
  collectionAbortController?.abort();
  bulkCollecting = false;
  collectionAbortController = null;
  clearActiveJobs();
}

export function resetCollectionProgress() {
  showCollectionProgress = false;
  collectionProgressList = [];
  clearActiveJobs();
}

// ─── localStorage persistence ───

export function saveActiveJobs(jobs: StoredCollectionJob[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(jobs));
  } catch { /* ignore quota errors */ }
}

export function loadActiveJobs(): StoredCollectionJob[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as StoredCollectionJob[];
  } catch {
    return [];
  }
}

export function clearActiveJobs() {
  localStorage.removeItem(STORAGE_KEY);
}

/**
 * Resume active collection jobs after page refresh.
 * Reads saved jobs from localStorage, polls backend for progress,
 * and restores the progress panel UI.
 */
export async function resumeActiveJobs() {
  const jobs = loadActiveJobs();
  if (jobs.length === 0) return;

  // Stale job guard: discard jobs older than 2 hours
  const twoHoursAgo = Date.now() - 2 * 60 * 60 * 1000;
  const recentJobs = jobs.filter(j => j.startedAt > twoHoursAgo);
  if (recentJobs.length === 0) {
    clearActiveJobs();
    return;
  }

  // Initialize progress list
  const initialProgress: CollectionProgress[] = recentJobs.map(j => ({
    current: 0, total: 0, percent: 0,
    currentTable: 'Resuming...',
    successCount: 0, failCount: 0,
    schemaName: j.schemaName, databaseName: j.databaseName,
  }));
  setCollectionProgressList(initialProgress);
  setBulkCollecting(true);
  setShowCollectionProgress(true);

  // Poll each job
  const stillRunning: StoredCollectionJob[] = [];

  for (let i = 0; i < recentJobs.length; i++) {
    const job = recentJobs[i];
    try {
      const progress = await catalogService.getCollectionProgress(job.jobId);
      updateCollectionProgress(i, {
        current: progress.processedTables,
        total: progress.totalTables,
        percent: progress.totalTables > 0 ? Math.round((progress.processedTables / progress.totalTables) * 100) : 0,
        currentTable: progress.currentTable || '',
        successCount: progress.successCount,
        failCount: progress.failCount,
        schemaName: job.schemaName,
        databaseName: job.databaseName,
      });

      if (progress.status === 'RUNNING') {
        stillRunning.push(job);
      }
    } catch {
      // Job no longer exists on backend (server restarted or expired)
      updateCollectionProgress(i, {
        current: 0, total: 0, percent: 0,
        currentTable: 'Expired',
        successCount: 0, failCount: 0,
        schemaName: job.schemaName, databaseName: job.databaseName,
      });
    }
  }

  // If no jobs still running, we're done
  if (stillRunning.length === 0) {
    setBulkCollecting(false);
    clearActiveJobs();
    return;
  }

  // Continue polling still-running jobs
  saveActiveJobs(stillRunning);

  for (const job of stillRunning) {
    const jobIndex = recentJobs.findIndex(j => j.jobId === job.jobId);
    if (jobIndex === -1) continue;

    // Poll loop in background
    (async () => {
      let done = false;
      while (!done) {
        await new Promise(r => setTimeout(r, 2000));
        try {
          const progress = await catalogService.getCollectionProgress(job.jobId);
          updateCollectionProgress(jobIndex, {
            current: progress.processedTables,
            total: progress.totalTables,
            percent: progress.totalTables > 0 ? Math.round((progress.processedTables / progress.totalTables) * 100) : 0,
            currentTable: progress.currentTable || '',
            successCount: progress.successCount,
            failCount: progress.failCount,
            schemaName: job.schemaName,
            databaseName: job.databaseName,
          });
          if (progress.status === 'COMPLETED' || progress.status === 'FAILED') {
            done = true;
          }
        } catch {
          done = true;
        }
      }

      // Check if all jobs completed
      const remaining = loadActiveJobs().filter(j => j.jobId !== job.jobId);
      saveActiveJobs(remaining);
      if (remaining.length === 0) {
        setBulkCollecting(false);
        clearActiveJobs();
      }
    })();
  }
}
