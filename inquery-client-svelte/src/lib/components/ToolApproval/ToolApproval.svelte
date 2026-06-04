<script lang="ts">
  import type { IToolApprovalRequest, IToolApprovalParam } from "$lib/stores/aiChat.svelte";
  import { submitToolApproval } from "$lib/stores/aiChat.svelte";
  import { getBaseURL } from "$lib/service/base";
  import i18n from "$lib/i18n";

  let {
    msgId,
    approval,
  }: {
    msgId: string;
    approval: IToolApprovalRequest;
  } = $props();

  let paramValues = $state<Record<string, string>>({});
  let isSubmitting = $state(false);
  let showDetails = $state(false);
  let previewMode = $state<Record<string, boolean>>({});
  let loadingOptions = $state<Record<string, boolean>>({});
  let dynamicOptions = $state<Record<string, Array<{ label: string; value: string }>>>({});
  let htmlIframeDocs = $state<Record<string, string>>({});
  // Searchable dropdown state
  let searchQueries = $state<Record<string, string>>({});
  let searchOpen = $state<Record<string, boolean>>({});
  let searchTimers = $state<Record<string, ReturnType<typeof setTimeout>>>({});

  const isResolved = $derived(!!approval.resolved);

  // Derive service info from tool name
  const serviceInfo = $derived.by(() => {
    const name = (approval.toolName || "").toLowerCase();
    if (name.includes("conf")) return { service: "Confluence", icon: "/icons/confluence.svg", color: "blue" } as const;
    if (name.includes("slack")) return { service: "Slack", icon: "/icons/slack.svg", color: "purple" } as const;
    if (name.includes("jira")) return { service: "Jira", icon: "/icons/jira.svg", color: "blue" } as const;
    if (name.includes("github") || name.includes("gh")) return { service: "GitHub", icon: "/icons/github.svg", color: "gray" } as const;
    return { service: "External", icon: null, color: "gray" } as const;
  });

  const actionLabel = $derived.by(() => {
    const name = (approval.toolName || "").toLowerCase();
    if (name.includes("create") || name.includes("post")) return i18n('common.toolApproval.action.create');
    if (name.includes("put") || name.includes("update")) return i18n('common.toolApproval.action.update');
    if (name.includes("delete") || name.includes("remove")) return i18n('common.toolApproval.action.delete');
    if (name.includes("send")) return i18n('common.toolApproval.action.send');
    return i18n('common.toolApproval.action.execute');
  });

  // Parse target: "Space: 14516224 | https://..." → { label, url }
  const targetInfo = $derived.by(() => {
    if (!approval.target) return null;
    const parts = approval.target.split(" | ");
    if (parts.length === 2 && parts[1].startsWith("http")) {
      return { label: parts[0].trim(), url: parts[1].trim() };
    }
    return { label: approval.target, url: null };
  });

  const editableParams = $derived(approval.parameters.filter((p) => p.required && p.type !== "html"));
  const htmlParams = $derived(approval.parameters.filter((p) => p.type === "html"));
  const previewParams = $derived(approval.parameters.filter((p) => !p.required && p.type !== "html"));

  $effect(() => {
    const initial: Record<string, string> = {};
    const initialSearchQueries: Record<string, string> = {};
    for (const param of approval.parameters) {
      initial[param.name] = param.value || "";
      // For searchable dropdowns with pre-filled values, show the value in the search input
      // so the user can see what's currently selected (e.g., channel ID)
      if (param.type === "dropdown" && isSearchEndpoint(param.optionsEndpoint) && param.value) {
        initialSearchQueries[param.name] = param.value;
      }
    }
    paramValues = initial;
    if (Object.keys(initialSearchQueries).length > 0) {
      searchQueries = initialSearchQueries;
    }
  });

  // Load dropdown options once on mount (not in $effect to avoid re-trigger loops)
  const fetchedEndpoints = new Set<string>();
  $effect(() => {
    if (isResolved) return;
    for (const param of approval.parameters) {
      if (param.type !== "dropdown" || dynamicOptions[param.name]) continue;

      if (param.options && !param.optionsEndpoint) {
        dynamicOptions = { ...dynamicOptions, [param.name]: param.options };
      } else if (param.optionsEndpoint && !isSearchEndpoint(param.optionsEndpoint) && !fetchedEndpoints.has(param.name)) {
        // Fixed-list endpoints (e.g., issue types): load once immediately
        fetchedEndpoints.add(param.name);
        fetchOptions(param);
      } else if (param.optionsEndpoint && isSearchEndpoint(param.optionsEndpoint) && param.value && !fetchedEndpoints.has(param.name)) {
        // Searchable endpoints with pre-filled value (e.g., channel ID):
        // auto-fetch to resolve the ID to a human-readable name
        fetchedEndpoints.add(param.name);
        fetchAndResolvePrefilledOption(param);
      }
    }
  });

  /** Endpoints that require user input to search (not loaded on mount) */
  function isSearchEndpoint(endpoint?: string | null): boolean {
    if (!endpoint) return false;
    return endpoint.includes("/users") || endpoint.includes("/channels");
  }

  /** Fetch options for a pre-filled value and resolve the ID to a label */
  async function fetchAndResolvePrefilledOption(param: IToolApprovalParam) {
    await fetchOptions(param, "");
    // After fetching, find the matching option and set the search query to its label
    const opts = dynamicOptions[param.name] || [];
    const prefilledValue = param.value || "";
    const matched = opts.find((o) => o.value === prefilledValue);
    if (matched) {
      searchQueries = { ...searchQueries, [param.name]: matched.label };
    }
  }

  async function fetchOptions(param: IToolApprovalParam, query = "") {
    loadingOptions = { ...loadingOptions, [param.name]: true };
    try {
      const sep = param.optionsEndpoint!.includes("?") ? "&" : "?";
      const url = `${getBaseURL()}${param.optionsEndpoint}${sep}query=${encodeURIComponent(query)}`;
      const token = typeof window !== "undefined" ? localStorage.getItem("Inquery") || "" : "";
      const res = await fetch(url, {
        headers: token ? { INQUERY: token } : {},
      });
      if (res.ok) {
        const data = await res.json();
        const options = Array.isArray(data?.data) ? data.data : Array.isArray(data) ? data : [];
        dynamicOptions = { ...dynamicOptions, [param.name]: options };
      }
    } catch {
      // Silently fall back to static options
    } finally {
      loadingOptions = { ...loadingOptions, [param.name]: false };
    }
  }

  function handleSearchInput(param: IToolApprovalParam, value: string) {
    searchQueries = { ...searchQueries, [param.name]: value };
    searchOpen = { ...searchOpen, [param.name]: true };
    // Debounce API calls
    if (searchTimers[param.name]) clearTimeout(searchTimers[param.name]);
    searchTimers = {
      ...searchTimers,
      [param.name]: setTimeout(() => {
        if (param.optionsEndpoint) fetchOptions(param, value);
      }, 300),
    };
  }

  function selectOption(paramName: string, opt: { label: string; value: string }) {
    paramValues = { ...paramValues, [paramName]: opt.value };
    searchQueries = { ...searchQueries, [paramName]: opt.label };
    searchOpen = { ...searchOpen, [paramName]: false };
  }

  $effect(() => {
    const docs: Record<string, string> = {};
    for (const param of htmlParams) {
      if (param.value) {
        docs[param.name] = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
          body { margin: 0; padding: 12px; font-family: system-ui, -apple-system, sans-serif; font-size: 13px; line-height: 1.6; color: #1a1a1a; background: #ffffff; }
          table { border-collapse: collapse; width: 100%; margin: 8px 0; }
          th, td { border: 1px solid #d0d0d0; padding: 6px 10px; text-align: left; font-size: 12px; }
          th { background: #f5f5f5; font-weight: 600; }
          h1,h2,h3,h4 { margin: 8px 0 4px; }
          p { margin: 4px 0; }
          code { background: #f0f0f0; border-radius: 3px; padding: 1px 4px; font-size: 12px; }
          pre { background: #f5f5f5; border: 1px solid #e0e0e0; border-radius: 4px; padding: 8px 12px; overflow-x: auto; }
          @media (prefers-color-scheme: dark) {
            body { color: #e0e0e0; background: #1a1a2e; }
            th, td { border-color: #404040; }
            th { background: #2a2a3e; }
            code { background: #2a2a3e; }
            pre { background: #2a2a3e; border-color: #404040; }
            a { color: #7ba4d4; }
          }
        </style></head><body>${param.value}</body></html>`;
      }
    }
    htmlIframeDocs = docs;
  });

  async function handleApprove() {
    isSubmitting = true;
    await submitToolApproval(msgId, approval.requestId, true, paramValues);
    isSubmitting = false;
  }

  async function handleDeny() {
    isSubmitting = true;
    await submitToolApproval(msgId, approval.requestId, false);
    isSubmitting = false;
  }

  function getOptions(param: IToolApprovalParam) {
    return dynamicOptions[param.name] || param.options || [];
  }

  /** Render Slack mrkdwn to HTML for preview */
  function renderMrkdwn(text: string): string {
    if (!text) return "";
    let html = text
      // Escape HTML first
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      // Code blocks (``` ... ```)
      .replace(/```([^`]*?)```/gs, '<pre class="slack-code-block">$1</pre>')
      // Inline code (`...`)
      .replace(/`([^`]+?)`/g, '<code class="slack-inline-code">$1</code>')
      // Bold (*text*)
      .replace(/(?<!\w)\*([^*\n]+?)\*(?!\w)/g, '<strong>$1</strong>')
      // Italic (_text_)
      .replace(/(?<!\w)_([^_\n]+?)_(?!\w)/g, '<em>$1</em>')
      // Strikethrough (~text~)
      .replace(/~([^~\n]+?)~/g, '<del>$1</del>')
      // Slack links (<url|text>)
      .replace(/&lt;(https?:\/\/[^|&]+)\|([^&]+)&gt;/g, '<a href="$1" target="_blank" class="slack-link">$2</a>')
      // Blockquotes (&gt; text)
      .replace(/^&gt;\s?(.*)$/gm, '<div class="slack-quote">$1</div>')
      // Bullet lists (• or -)
      .replace(/^[•\-]\s+(.*)$/gm, '<div class="slack-bullet">$1</div>')
      // Newlines
      .replace(/\n/g, '<br>');
    return html;
  }
</script>

{#if isResolved}
  <!-- Resolved: compact read-only card -->
  {@const executionFailed = approval.resolved === 'approved' && approval.executionSuccess === false}
  {@const resolvedColor = executionFailed ? 'red' : approval.resolved === 'approved' ? 'green' : approval.resolved === 'expired' ? 'amber' : 'red'}
  <div class="mt-3 rounded-lg border p-3 space-y-2
    {resolvedColor === 'green'
      ? 'border-green-300/60 bg-green-50/50 dark:border-green-700/40 dark:bg-green-950/20'
      : resolvedColor === 'amber'
        ? 'border-amber-300/60 bg-amber-50/50 dark:border-amber-700/40 dark:bg-amber-950/20'
        : 'border-red-300/60 bg-red-50/50 dark:border-red-700/40 dark:bg-red-950/20'}">
    <div class="flex items-center gap-3">
      <div class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full
        {resolvedColor === 'green'
          ? 'bg-green-100 dark:bg-green-900/40'
          : resolvedColor === 'amber'
            ? 'bg-amber-100 dark:bg-amber-900/40'
            : 'bg-red-100 dark:bg-red-900/40'}">
        {#if serviceInfo.icon}
          <img src={serviceInfo.icon} alt={serviceInfo.service} class="h-4 w-4" />
        {:else if approval.resolved === 'approved' && !executionFailed}
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-4 w-4 text-green-600 dark:text-green-400">
            <path fill-rule="evenodd" d="M12.416 3.376a.75.75 0 0 1 .208 1.04l-5 7.5a.75.75 0 0 1-1.154.114l-3-3a.75.75 0 0 1 1.06-1.06l2.353 2.353 4.493-6.74a.75.75 0 0 1 1.04-.207Z" clip-rule="evenodd" />
          </svg>
        {:else if approval.resolved === 'expired'}
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-4 w-4 text-amber-600 dark:text-amber-400">
            <path fill-rule="evenodd" d="M8 15A7 7 0 1 0 8 1a7 7 0 0 0 0 14ZM8 4a.75.75 0 0 1 .75.75v3.5a.75.75 0 0 1-1.5 0v-3.5A.75.75 0 0 1 8 4Zm0 8a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" clip-rule="evenodd" />
          </svg>
        {:else}
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-4 w-4 text-red-600 dark:text-red-400">
            <path d="M5.28 4.22a.75.75 0 0 0-1.06 1.06L6.94 8l-2.72 2.72a.75.75 0 1 0 1.06 1.06L8 9.06l2.72 2.72a.75.75 0 1 0 1.06-1.06L9.06 8l2.72-2.72a.75.75 0 0 0-1.06-1.06L8 6.94 5.28 4.22Z" />
          </svg>
        {/if}
      </div>
      <div class="flex-1 min-w-0">
        <p class="text-sm text-foreground">
          {#if approval.description}
            {approval.description}
          {:else}
            {i18n('common.toolApproval.aiWantsTo', actionLabel, serviceInfo.service)}
          {/if}
          {#if targetInfo}
            <span class="text-muted-foreground"> → {targetInfo.label}</span>
            {#if targetInfo.url}
              <a href={targetInfo.url} target="_blank" rel="noopener noreferrer" class="text-primary hover:underline ml-1 text-xs">Open</a>
            {/if}
          {/if}
        </p>
      </div>
      <span class="shrink-0 text-xs font-medium px-2 py-0.5 rounded-full
        {resolvedColor === 'green'
          ? 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300'
          : resolvedColor === 'amber'
            ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300'
            : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'}">
        {#if executionFailed}
          Failed
        {:else}
          {approval.resolved === 'approved' ? i18n('common.toolApproval.approved') : approval.resolved === 'expired' ? 'Expired' : i18n('common.toolApproval.denied')}
        {/if}
      </span>
    </div>
    {#if executionFailed && approval.executionError}
      <div class="ml-10 px-3 py-2 rounded-md bg-red-100/70 dark:bg-red-900/30 text-xs text-red-700 dark:text-red-300">
        {approval.executionError}
      </div>
    {/if}
  </div>
{:else}
  <!-- Pending: full approval card -->
  <div class="mt-3 rounded-lg border-2 border-amber-400/60 bg-amber-50/50 dark:bg-amber-950/20 p-4 space-y-3">
    <!-- Header: Shield icon + action summary -->
    <div class="flex items-start gap-3">
      <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-amber-100 dark:bg-amber-900/40">
        {#if serviceInfo.icon}
          <img src={serviceInfo.icon} alt={serviceInfo.service} class="h-5 w-5" />
        {:else}
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-5 w-5 text-amber-600 dark:text-amber-400">
            <path fill-rule="evenodd" d="M10 1a4.5 4.5 0 00-4.5 4.5V9H5a2 2 0 00-2 2v6a2 2 0 002 2h10a2 2 0 002-2v-6a2 2 0 00-2-2h-.5V5.5A4.5 4.5 0 0010 1zm3 8V5.5a3 3 0 10-6 0V9h6z" clip-rule="evenodd" />
          </svg>
        {/if}
      </div>
      <div class="flex-1 min-w-0">
        <p class="text-sm font-semibold text-foreground">
          {i18n('common.toolApproval.title')}
        </p>
        <p class="text-sm text-muted-foreground mt-0.5">
          {#if approval.description}
            {approval.description}
          {:else}
            {i18n('common.toolApproval.aiWantsTo', actionLabel, serviceInfo.service)}
          {/if}
        </p>
        {#if targetInfo}
          <div class="mt-1.5 inline-flex items-center gap-1.5 rounded-md bg-muted/70 px-2 py-1 text-xs text-muted-foreground">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-3 w-3 shrink-0">
              <path fill-rule="evenodd" d="m7.539 14.841.003.003.002.002a.755.755 0 0 0 .912 0l.002-.002.003-.003.012-.009a5.57 5.57 0 0 0 .19-.153 15.588 15.588 0 0 0 2.046-2.082c1.101-1.362 2.291-3.342 2.291-5.597A5 5 0 0 0 3 7c0 2.255 1.19 4.235 2.291 5.597a15.591 15.591 0 0 0 2.046 2.082 8.916 8.916 0 0 0 .189.153l.012.01ZM8 8.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z" clip-rule="evenodd" />
            </svg>
            <span class="font-medium">{i18n('common.toolApproval.target')}:</span>
            <span>{targetInfo.label}</span>
            {#if targetInfo.url}
              <a href={targetInfo.url} target="_blank" rel="noopener noreferrer"
                class="text-primary hover:underline flex items-center gap-0.5">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-3 w-3">
                  <path d="M8.914 2.342a3.25 3.25 0 1 1 4.596 4.596l-1.879 1.879a.75.75 0 0 1-1.06-1.06l1.878-1.88a1.75 1.75 0 0 0-2.474-2.474l-1.88 1.879a.75.75 0 1 1-1.06-1.061l1.879-1.879Z" />
                  <path d="M7.086 13.658a3.25 3.25 0 0 1-4.596-4.596l1.879-1.879a.75.75 0 0 1 1.06 1.06L3.55 10.122a1.75 1.75 0 1 0 2.474 2.474l1.88-1.879a.75.75 0 1 1 1.06 1.061l-1.879 1.879Z" />
                  <path d="M5.97 10.03a.75.75 0 0 1 0-1.06l4-4a.75.75 0 1 1 1.06 1.06l-4 4a.75.75 0 0 1-1.06 0Z" />
                </svg>
                Open
              </a>
            {/if}
          </div>
        {/if}
      </div>
      <!-- Service badge -->
      <span class="shrink-0 inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium
        {serviceInfo.color === 'blue' ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300' :
         serviceInfo.color === 'purple' ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300' :
         'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'}">
        {#if serviceInfo.icon}
          <img src={serviceInfo.icon} alt="" class="h-3 w-3" />
        {/if}
        {serviceInfo.service}
      </span>
    </div>

    <!-- Editable Parameters (always visible) -->
    {#if editableParams.length > 0}
      <div class="space-y-2 pl-12">
        {#each editableParams as param}
          <div class="space-y-1">
            <label for="tool-param-{param.name}" class="text-xs font-medium text-muted-foreground">
              {param.displayName}
            </label>

            {#if param.type === "textarea"}
              {@const textValue = paramValues[param.name] || ""}
              {@const lineCount = textValue.split("\n").length}
              {@const estimatedRows = Math.max(6, Math.min(20, lineCount + 2))}
              {@const isSlack = serviceInfo.service === "Slack"}
              {#if isSlack}
                <div class="flex gap-1 mb-1">
                  <button
                    type="button"
                    class="text-xs px-2 py-0.5 rounded transition-colors {!previewMode[param.name] ? 'bg-primary/10 text-primary font-medium' : 'text-muted-foreground hover:text-foreground'}"
                    onclick={() => { previewMode = { ...previewMode, [param.name]: false }; }}
                  >Edit</button>
                  <button
                    type="button"
                    class="text-xs px-2 py-0.5 rounded transition-colors {previewMode[param.name] ? 'bg-primary/10 text-primary font-medium' : 'text-muted-foreground hover:text-foreground'}"
                    onclick={() => { previewMode = { ...previewMode, [param.name]: true }; }}
                  >Preview</button>
                </div>
              {/if}
              {#if isSlack && previewMode[param.name]}
                <div class="w-full rounded-md border border-input bg-white dark:bg-[#1a1d21] px-3 py-2 text-sm text-[#1d1c1d] dark:text-[#d1d2d3] min-h-[120px] overflow-auto slack-preview"
                  style="max-height: {estimatedRows * 1.5}em;">
                  {@html renderMrkdwn(textValue)}
                </div>
              {:else}
                <textarea
                  id="tool-param-{param.name}"
                  class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary resize-y min-h-[120px]"
                  value={textValue}
                  oninput={(e) => {
                    paramValues = { ...paramValues, [param.name]: (e.target as HTMLTextAreaElement).value };
                  }}
                  rows={estimatedRows}
                ></textarea>
              {/if}
            {:else if param.type === "autocomplete"}
              <!-- Hybrid autocomplete: text input + optional API suggestions -->
              <!-- User can always type freely. If API returns results, shows dropdown. -->
              <div class="relative">
                <input
                  id="tool-param-{param.name}"
                  type="text"
                  class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                  placeholder={param.name.includes("channel") ? "#channel-name, @user, or paste ID" : "Type to search..."}
                  value={searchQueries[param.name] ?? paramValues[param.name] ?? ""}
                  onfocus={() => {
                    searchOpen = { ...searchOpen, [param.name]: true };
                    if (param.optionsEndpoint && !dynamicOptions[param.name]) {
                      fetchOptions(param, "");
                    }
                  }}
                  oninput={(e) => {
                    const val = (e.target as HTMLInputElement).value;
                    searchQueries = { ...searchQueries, [param.name]: val };
                    paramValues = { ...paramValues, [param.name]: val };
                    searchOpen = { ...searchOpen, [param.name]: true };
                    if (searchTimers[param.name]) clearTimeout(searchTimers[param.name]);
                    searchTimers = {
                      ...searchTimers,
                      [param.name]: setTimeout(() => {
                        if (param.optionsEndpoint) fetchOptions(param, val);
                      }, 300),
                    };
                  }}
                  onblur={() => {
                    setTimeout(() => { searchOpen = { ...searchOpen, [param.name]: false }; }, 200);
                  }}
                />
                {#if loadingOptions[param.name]}
                  <span class="absolute right-2 top-2.5 text-xs text-muted-foreground animate-pulse">Searching...</span>
                {/if}
                {#if searchOpen[param.name] && getOptions(param).length > 0}
                  <ul class="absolute z-50 mt-1 max-h-56 w-full overflow-auto rounded-md border border-input bg-background py-1 shadow-lg">
                    {#each getOptions(param) as opt}
                      <li>
                        <button
                          type="button"
                          class="w-full px-3 py-1.5 text-left text-sm hover:bg-muted transition-colors
                            {paramValues[param.name] === opt.value ? 'bg-primary/10 text-primary font-medium' : 'text-foreground'}"
                          onmousedown={(e) => { e.preventDefault(); selectOption(param.name, opt); }}
                        >
                          {opt.label}
                        </button>
                      </li>
                    {/each}
                  </ul>
                {/if}
                {#if !loadingOptions[param.name] && getOptions(param).length === 0 && param.name.includes("channel")}
                  <p class="text-[11px] text-muted-foreground mt-0.5">
                    Enter a channel name (e.g. #general), @username for DM, or paste a channel ID
                  </p>
                {/if}
              </div>
            {:else if param.type === "dropdown" && isSearchEndpoint(param.optionsEndpoint)}
              <!-- Searchable combobox (type to search: users, channels, etc.) -->
              <div class="relative">
                <input
                  id="tool-param-{param.name}"
                  type="text"
                  class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                  placeholder="Type to search..."
                  value={searchQueries[param.name] ?? ""}
                  onfocus={() => {
                    searchOpen = { ...searchOpen, [param.name]: true };
                  }}
                  oninput={(e) => handleSearchInput(param, (e.target as HTMLInputElement).value)}
                  onblur={() => {
                    setTimeout(() => { searchOpen = { ...searchOpen, [param.name]: false }; }, 200);
                  }}
                />
                {#if loadingOptions[param.name]}
                  <span class="absolute right-2 top-2.5 text-xs text-muted-foreground">Loading...</span>
                {/if}
                {#if searchOpen[param.name] && getOptions(param).length > 0}
                  <ul class="absolute z-50 mt-1 max-h-48 w-full overflow-auto rounded-md border border-input bg-background py-1 shadow-lg">
                    {#each getOptions(param) as opt}
                      <li>
                        <button
                          type="button"
                          class="w-full px-3 py-1.5 text-left text-sm hover:bg-muted transition-colors
                            {paramValues[param.name] === opt.value ? 'bg-primary/10 text-primary font-medium' : 'text-foreground'}"
                          onmousedown={(e) => { e.preventDefault(); selectOption(param.name, opt); }}
                        >
                          {opt.label}
                        </button>
                      </li>
                    {/each}
                  </ul>
                {/if}
              </div>
            {:else if param.type === "dropdown"}
              <!-- Select dropdown for fixed-list options (e.g., issue types) -->
              {#if loadingOptions[param.name]}
                <div class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-muted-foreground">
                  Loading...
                </div>
              {:else}
                <select
                  id="tool-param-{param.name}"
                  class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary appearance-none cursor-pointer"
                  value={paramValues[param.name] || ""}
                  onchange={(e) => {
                    paramValues = { ...paramValues, [param.name]: (e.target as HTMLSelectElement).value };
                  }}
                >
                  <option value="" disabled>Select {param.displayName}...</option>
                  {#each getOptions(param) as opt}
                    <option value={opt.value} selected={paramValues[param.name] === opt.value}>{opt.label}</option>
                  {/each}
                </select>
              {/if}
            {:else}
              <input
                id="tool-param-{param.name}"
                type="text"
                class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
                value={paramValues[param.name] || ""}
                oninput={(e) => {
                  paramValues = { ...paramValues, [param.name]: (e.target as HTMLInputElement).value };
                }}
              />
            {/if}
          </div>
        {/each}
      </div>
    {/if}

    <!-- HTML Content with Edit/Preview toggle -->
    {#if htmlParams.length > 0}
      <div class="space-y-2 pl-12">
        {#each htmlParams as param}
          <div class="space-y-1">
            <div class="flex items-center justify-between">
              <label class="text-xs font-medium text-muted-foreground">
                {param.displayName}
              </label>
              <div class="flex gap-1">
                <button
                  type="button"
                  class="text-xs px-2 py-0.5 rounded transition-colors {previewMode[param.name] !== false ? 'text-muted-foreground hover:text-foreground' : 'bg-primary/10 text-primary font-medium'}"
                  onclick={() => { previewMode = { ...previewMode, [param.name]: false }; }}
                >Edit</button>
                <button
                  type="button"
                  class="text-xs px-2 py-0.5 rounded transition-colors {previewMode[param.name] !== false ? 'bg-primary/10 text-primary font-medium' : 'text-muted-foreground hover:text-foreground'}"
                  onclick={() => { previewMode = { ...previewMode, [param.name]: true }; }}
                >Preview</button>
              </div>
            </div>
            {#if previewMode[param.name] !== false}
              <div class="rounded-md border border-input overflow-hidden bg-background">
                {#if htmlIframeDocs[param.name]}
                  <iframe
                    srcdoc={htmlIframeDocs[param.name]}
                    class="w-full border-0"
                    style="height: 200px;"
                    sandbox=""
                    title={param.displayName}
                  ></iframe>
                {/if}
              </div>
            {:else}
              <textarea
                class="w-full rounded-md border border-input bg-background px-3 py-2 text-xs font-mono text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary resize-y min-h-[200px]"
                value={paramValues[param.name] || param.value || ""}
                oninput={(e) => {
                  const newVal = (e.target as HTMLTextAreaElement).value;
                  paramValues = { ...paramValues, [param.name]: newVal };
                  htmlIframeDocs = { ...htmlIframeDocs, [param.name]: `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
                    body { margin: 0; padding: 12px; font-family: system-ui, -apple-system, sans-serif; font-size: 13px; line-height: 1.6; color: #1a1a1a; background: #ffffff; }
                    table { border-collapse: collapse; width: 100%; margin: 8px 0; }
                    th, td { border: 1px solid #d0d0d0; padding: 6px 10px; text-align: left; font-size: 12px; }
                    th { background: #f5f5f5; font-weight: 600; }
                    h1,h2,h3,h4 { margin: 8px 0 4px; }
                    p { margin: 4px 0; }
                    code { background: #f0f0f0; border-radius: 3px; padding: 1px 4px; font-size: 12px; }
                    pre { background: #f5f5f5; border: 1px solid #e0e0e0; border-radius: 4px; padding: 8px 12px; overflow-x: auto; }
                    @media (prefers-color-scheme: dark) {
                      body { color: #e0e0e0; background: #1a1a2e; }
                      th, td { border-color: #404040; }
                      th { background: #2a2a3e; }
                      code { background: #2a2a3e; }
                      pre { background: #2a2a3e; border-color: #404040; }
                      a { color: #7ba4d4; }
                    }
                  </style></head><body>${newVal}</body></html>` };
                }}
                rows={12}
              ></textarea>
            {/if}
          </div>
        {/each}
      </div>
    {/if}

    <!-- Preview / Details (collapsible) -->
    {#if previewParams.length > 0}
      <div class="pl-12">
        <button
          class="text-xs text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1"
          onclick={() => showDetails = !showDetails}
        >
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor"
            class="h-3 w-3 transition-transform {showDetails ? 'rotate-90' : ''}">
            <path d="M6.22 4.22a.75.75 0 0 1 1.06 0l3.25 3.25a.75.75 0 0 1 0 1.06l-3.25 3.25a.75.75 0 0 1-1.06-1.06L8.94 8 6.22 5.28a.75.75 0 0 1 0-1.06Z" />
          </svg>
          {showDetails ? i18n('common.toolApproval.hideDetails') : i18n('common.toolApproval.showDetails')}
        </button>

        {#if showDetails}
          <div class="mt-2 space-y-2 rounded-md bg-muted/50 p-3">
            {#each previewParams as param}
              <div>
                <span class="text-xs font-medium text-muted-foreground">{param.displayName}</span>
                <p class="text-xs text-foreground mt-0.5 whitespace-pre-wrap break-words">{paramValues[param.name] || param.value || ""}</p>
              </div>
            {/each}
          </div>
        {/if}
      </div>
    {/if}

    <!-- Action Buttons -->
    <div class="flex items-center gap-2 pl-12 pt-1">
      <button
        class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors disabled:opacity-50"
        onclick={handleApprove}
        disabled={isSubmitting}
      >
        {#if isSubmitting}
          <svg class="animate-spin h-3.5 w-3.5" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
          </svg>
          {i18n('common.toolApproval.processing')}
        {:else}
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-3.5 w-3.5">
            <path fill-rule="evenodd" d="M12.416 3.376a.75.75 0 0 1 .208 1.04l-5 7.5a.75.75 0 0 1-1.154.114l-3-3a.75.75 0 0 1 1.06-1.06l2.353 2.353 4.493-6.74a.75.75 0 0 1 1.04-.207Z" clip-rule="evenodd" />
          </svg>
          {i18n('common.toolApproval.allow')}
        {/if}
      </button>
      <button
        class="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-md border border-border bg-background text-muted-foreground text-sm font-medium hover:bg-muted hover:text-foreground transition-colors disabled:opacity-50"
        onclick={handleDeny}
        disabled={isSubmitting}
      >
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-3.5 w-3.5">
          <path d="M5.28 4.22a.75.75 0 0 0-1.06 1.06L6.94 8l-2.72 2.72a.75.75 0 1 0 1.06 1.06L8 9.06l2.72 2.72a.75.75 0 1 0 1.06-1.06L9.06 8l2.72-2.72a.75.75 0 0 0-1.06-1.06L8 6.94 5.28 4.22Z" />
        </svg>
        {i18n('common.toolApproval.block')}
      </button>
    </div>
  </div>
{/if}

<style>
  :global(.slack-preview strong) { font-weight: 700; }
  :global(.slack-preview em) { font-style: italic; }
  :global(.slack-preview del) { text-decoration: line-through; }
  :global(.slack-preview .slack-code-block) {
    display: block;
    background: #f8f8f8;
    border: 1px solid #e1e1e1;
    border-radius: 4px;
    padding: 8px 12px;
    margin: 4px 0;
    font-family: 'Monaco', 'Menlo', 'Courier New', monospace;
    font-size: 12px;
    white-space: pre;
    overflow-x: auto;
    line-height: 1.5;
  }
  :global([data-theme='dark'] .slack-preview .slack-code-block) {
    background: #2d2d2d;
    border-color: #444;
    color: #d1d2d3;
  }
  :global(.slack-preview .slack-inline-code) {
    background: #f0f0f0;
    border-radius: 3px;
    padding: 1px 4px;
    font-family: 'Monaco', 'Menlo', 'Courier New', monospace;
    font-size: 12px;
    color: #e01e5a;
  }
  :global([data-theme='dark'] .slack-preview .slack-inline-code) {
    background: #3a3a3a;
    color: #e06b85;
  }
  :global(.slack-preview .slack-quote) {
    border-left: 3px solid #ddd;
    padding-left: 8px;
    margin: 2px 0;
    color: #666;
  }
  :global([data-theme='dark'] .slack-preview .slack-quote) {
    border-left-color: #555;
    color: #aaa;
  }
  :global(.slack-preview .slack-bullet) {
    padding-left: 16px;
    position: relative;
  }
  :global(.slack-preview .slack-bullet::before) {
    content: '•';
    position: absolute;
    left: 4px;
  }
  :global(.slack-preview .slack-link) {
    color: #1264a3;
    text-decoration: none;
  }
  :global(.slack-preview .slack-link:hover) {
    text-decoration: underline;
  }
  :global([data-theme='dark'] .slack-preview .slack-link) {
    color: #5ba4d4;
  }
</style>
