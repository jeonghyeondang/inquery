<!--
	Per-format file icon for attachments without an image preview.
	Picked over rendering a tiny first-page thumbnail because the colored
	icon is more legible at chip / card sizes and avoids extra round-trips
	to fetch a server-rendered preview that the team didn't find useful.

	Resolution order:
	  1. filename extension (.pdf / .pptx / .docx / .xlsx / etc.)
	  2. mime type (covers files dropped without an extension)
	  3. {kind} fallback (image → FileImage, anything else → FileText)
-->
<script lang="ts">
	import {
		FileText,
		FileImage,
		FileSpreadsheet,
		Presentation,
		File as FileIcon
	} from 'lucide-svelte';

	interface Props {
		filename: string;
		mimeType?: string;
		kind: 'image' | 'pdf' | 'office' | 'text';
		class?: string;
	}

	let { filename, mimeType = '', kind, class: className = 'h-5 w-5' }: Props = $props();

	type IconChoice = { component: typeof FileText; color: string };

	function pickIcon(): IconChoice {
		const lower = (filename || '').toLowerCase();
		const mime = (mimeType || '').toLowerCase();

		if (lower.endsWith('.pdf') || mime === 'application/pdf') {
			return { component: FileText, color: 'text-red-500' };
		}
		if (lower.endsWith('.pptx') || mime.includes('presentationml')) {
			return { component: Presentation, color: 'text-orange-500' };
		}
		if (lower.endsWith('.docx') || mime.includes('wordprocessingml')) {
			return { component: FileText, color: 'text-blue-600' };
		}
		if (lower.endsWith('.xlsx') || mime.includes('spreadsheetml')) {
			return { component: FileSpreadsheet, color: 'text-green-600' };
		}
		if (lower.endsWith('.csv') || mime === 'text/csv') {
			return { component: FileSpreadsheet, color: 'text-green-700' };
		}
		if (kind === 'image') {
			return { component: FileImage, color: 'text-blue-500' };
		}
		if (kind === 'text') {
			return { component: FileText, color: 'text-gray-500' };
		}
		return { component: FileIcon, color: 'text-gray-500' };
	}

	let chosen = $derived(pickIcon());
	let IconComp = $derived(chosen.component);
</script>

<IconComp class={`${chosen.color} ${className}`} />
