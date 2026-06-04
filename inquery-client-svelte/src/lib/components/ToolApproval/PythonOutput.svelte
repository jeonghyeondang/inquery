<script lang="ts">
  import type { IPythonOutput } from "$lib/stores/aiChat.svelte";

  let { output }: { output: IPythonOutput } = $props();
</script>

{#if output.stdout}
  <div class="mt-2 rounded-md border border-border bg-muted/50 p-3">
    <div class="flex items-center gap-1.5 mb-1.5">
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-3.5 w-3.5 text-muted-foreground">
        <path fill-rule="evenodd" d="M6.28 5.22a.75.75 0 010 1.06L2.56 10l3.72 3.72a.75.75 0 01-1.06 1.06L.97 10.53a.75.75 0 010-1.06l4.25-4.25a.75.75 0 011.06 0zm7.44 0a.75.75 0 011.06 0l4.25 4.25a.75.75 0 010 1.06l-4.25 4.25a.75.75 0 01-1.06-1.06L17.44 10l-3.72-3.72a.75.75 0 010-1.06z" clip-rule="evenodd" />
      </svg>
      <span class="text-xs font-medium text-muted-foreground">Python Output</span>
    </div>
    <pre class="text-xs text-foreground whitespace-pre-wrap font-mono leading-relaxed">{output.stdout}</pre>
  </div>
{/if}

{#if output.charts && output.charts.length > 0}
  <div class="mt-2 space-y-2">
    {#each output.charts as chart, i}
      <div class="rounded-md border border-border overflow-hidden bg-background">
        <img
          src="data:image/png;base64,{chart}"
          alt="Python chart {i + 1}"
          class="w-full max-w-[600px]"
        />
      </div>
    {/each}
  </div>
{/if}
