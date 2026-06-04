<script lang="ts">
	let { size = 16, class: className = '', filled = false }: { size?: number; class?: string; filled?: boolean } = $props();

	const gradientId = `ai-gradient-${Math.random().toString(36).slice(2, 9)}`;
	const filledGradientId = `ai-filled-gradient-${Math.random().toString(36).slice(2, 9)}`;
</script>

<svg
	xmlns="http://www.w3.org/2000/svg"
	width={size}
	height={size}
	fill="none"
	viewBox="0 0 16 16"
	aria-hidden="true"
	focusable="false"
	class={className}
>
	<defs>
		<linearGradient id={gradientId} x1="0%" y1="0%" x2="100%" y2="100%">
			<stop offset="0%" stop-color="#9333EA" />
			<stop offset="50%" stop-color="#C026D3" />
			<stop offset="100%" stop-color="#EC4899" />
		</linearGradient>
		<linearGradient id={filledGradientId} x1="0%" y1="0%" x2="100%" y2="100%">
			<stop offset="20.5%" stop-color="#4299E0" />
			<stop offset="46.91%" stop-color="#CA42E0" />
			<stop offset="79.5%" stop-color="#FF5F46" />
		</linearGradient>
	</defs>
	{#if filled}
		<!-- Filled: large star background -->
		<path
			fill="url(#{filledGradientId})"
			fill-rule="nonzero"
			d="M13.667 9.425c1.376-.452 1.376-2.398 0-2.85l-2.472-.813a1.5 1.5 0 0 1-.957-.956l-.813-2.473c-.452-1.376-2.398-1.376-2.85 0l-.813 2.473a1.5 1.5 0 0 1-.956.956l-2.473.813c-1.376.452-1.376 2.398 0 2.85l2.473.813a1.5 1.5 0 0 1 .956.957l.813 2.472c.452 1.376 2.398 1.376 2.85 0l.813-2.472a1.5 1.5 0 0 1 .957-.957z"
			clip-rule="evenodd"
		/>
		<!-- Filled: inner star overlay -->
		<path
			fill="url(#{filledGradientId})"
			fill-rule="nonzero"
			d="M10.726 8.813 13.199 8l-2.473-.813a3 3 0 0 1-1.913-1.913L8 2.801l-.813 2.473a3 3 0 0 1-1.913 1.913L2.801 8l2.473.813a3 3 0 0 1 1.913 1.913L8 13.199l.813-2.473a3 3 0 0 1 1.913-1.913z"
			clip-rule="evenodd"
		/>
	{:else}
		<!-- Outline: stroke only -->
		<path
			fill="none"
			stroke="url(#{gradientId})"
			stroke-width="2.5"
			fill-rule="evenodd"
			d="M13.667 9.425c1.376-.452 1.376-2.398 0-2.85l-2.472-.813a1.5 1.5 0 0 1-.957-.956l-.813-2.473c-.452-1.376-2.398-1.376-2.85 0l-.813 2.473a1.5 1.5 0 0 1-.956.956l-2.473.813c-1.376.452-1.376 2.398 0 2.85l2.473.813a1.5 1.5 0 0 1 .956.957l.813 2.472c.452 1.376 2.398 1.376 2.85 0l.813-2.472a1.5 1.5 0 0 1 .957-.957z"
			clip-rule="evenodd"
		/>
	{/if}
</svg>
