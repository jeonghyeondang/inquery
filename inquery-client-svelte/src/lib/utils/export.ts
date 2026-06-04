/**
 * Export utilities - CSV, INSERT SQL, Chart PNG
 */

// ============================================================================
// CSV Export
// ============================================================================

function escapeCsvCell(value: unknown): string {
	if (value === null || value === undefined) return '';
	const str = String(value);
	if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
		return `"${str.replace(/"/g, '""')}"`;
	}
	return str;
}

export function downloadTableAsCSV(
	columns: Array<{ name: string; [key: string]: unknown }>,
	rows: unknown[][],
	filename?: string
): void {
	const BOM = '\uFEFF';
	const header = columns.map(c => escapeCsvCell(c.name)).join(',');
	const body = rows.map(row =>
		row.map(cell => escapeCsvCell(cell)).join(',')
	).join('\n');

	const csv = BOM + header + '\n' + body;
	const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = (filename || `export_${Date.now()}`) + '.csv';
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}

// ============================================================================
// JSON Export
// ============================================================================

export function downloadTableAsJSON(
	columns: Array<{ name: string; [key: string]: unknown }>,
	rows: unknown[][],
	filename?: string
): void {
	const data = rows.map(row => {
		const obj: Record<string, unknown> = {};
		columns.forEach((col, index) => {
			obj[col.name] = row[index];
		});
		return obj;
	});

	const json = JSON.stringify(data, null, 2);
	const blob = new Blob([json], { type: 'application/json;charset=utf-8;' });
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = (filename || `export_${Date.now()}`) + '.json';
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}

// ============================================================================
// INSERT SQL Generation
// ============================================================================

function escapeSQL(value: unknown): string {
	if (value === null || value === undefined) return 'NULL';
	if (typeof value === 'number') return String(value);
	if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE';
	return `'${String(value).replace(/'/g, "''")}'`;
}

export function generateInsertSQL(
	tableName: string,
	columns: Array<{ name: string; [key: string]: unknown }>,
	rows: unknown[][]
): string {
	if (rows.length === 0) return '-- No data to export';

	const colNames = columns.map(c => c.name).join(', ');
	const statements = rows.map(row => {
		const values = row.map(cell => escapeSQL(cell)).join(', ');
		return `INSERT INTO ${tableName} (${colNames}) VALUES (${values});`;
	});

	return statements.join('\n');
}

export function downloadInsertSQL(
	tableName: string,
	columns: Array<{ name: string; [key: string]: unknown }>,
	rows: unknown[][],
	filename?: string
): void {
	const sql = generateInsertSQL(tableName, columns, rows);
	const blob = new Blob([sql], { type: 'text/sql;charset=utf-8;' });
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = (filename || `insert_${tableName}_${Date.now()}`) + '.sql';
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}

// ============================================================================
// Chart PNG Export
// ============================================================================

export async function downloadChartAsPNG(
	chartElement: HTMLElement,
	filename?: string
): Promise<void> {
	const isDark = document.documentElement.classList.contains('dark');
	const bgColor = isDark ? '#1a1a2e' : '#ffffff';

	// Strategy 1: ECharts native export (fastest, best quality)
	try {
		const echarts = await import('echarts');
		const chartInstance = findEChartsInstance(chartElement, echarts);
		if (chartInstance) {
			const dataUrl = chartInstance.getDataURL({
				type: 'png',
				pixelRatio: 2,
				backgroundColor: bgColor,
			});
			triggerDownload(dataUrl, filename);
			return;
		}
	} catch { /* echarts not available */ }

	// Strategy 2: html-to-image (handles CSS Grid, modern layouts)
	// Same approach as dashboard PNG export: replace canvas→img first, then toPng
	const replacedCanvases: Array<{ img: HTMLImageElement; canvas: HTMLCanvasElement; parent: HTMLElement }> = [];
	chartElement.querySelectorAll('canvas').forEach(canvas => {
		try {
			const dataUrl = canvas.toDataURL('image/png');
			const img = document.createElement('img');
			img.src = dataUrl;
			img.style.width = canvas.style.width || `${canvas.width}px`;
			img.style.height = canvas.style.height || `${canvas.height}px`;
			img.style.display = 'block';
			const parent = canvas.parentElement!;
			parent.replaceChild(img, canvas);
			replacedCanvases.push({ img, canvas, parent });
		} catch { /* tainted canvas */ }
	});

	try {
		const { toPng } = await import('html-to-image');
		const dataUrl = await toPng(chartElement, {
			backgroundColor: bgColor,
			pixelRatio: 2,
			width: chartElement.scrollWidth,
			height: chartElement.scrollHeight,
		});
		triggerDownload(dataUrl, filename);
	} catch { /* html-to-image failed */ } finally {
		// Restore canvases
		replacedCanvases.forEach(({ img, canvas, parent }) => {
			parent.replaceChild(canvas, img);
		});
	}
}

function findEChartsInstance(element: HTMLElement, echarts: any): any {
	if (element.firstElementChild) {
		const instance = echarts.getInstanceByDom(element.firstElementChild);
		if (instance) return instance;
	}
	const divs = element.querySelectorAll('div');
	for (const div of divs) {
		const instance = echarts.getInstanceByDom(div);
		if (instance) return instance;
	}
	return null;
}

function triggerDownload(dataUrl: string, filename?: string): void {
	const link = document.createElement('a');
	link.href = dataUrl;
	link.download = (filename || `chart_${Date.now()}`) + '.png';
	document.body.appendChild(link);
	link.click();
	document.body.removeChild(link);
}

// ============================================================================
// Generic text download
// ============================================================================

export function downloadText(content: string, filename: string, mimeType = 'text/plain'): void {
	const blob = new Blob([content], { type: `${mimeType};charset=utf-8;` });
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = filename;
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}

// ============================================================================
// Cell value formatting
// ============================================================================

export function formatCellValue(value: unknown, dataType?: string): string {
	if (value === null || value === undefined) return 'NULL';
	if (typeof value === 'number') {
		if (dataType?.toLowerCase().includes('float') || dataType?.toLowerCase().includes('double') || dataType?.toLowerCase().includes('decimal')) {
			return value.toLocaleString(undefined, { maximumFractionDigits: 6 });
		}
		return value.toLocaleString();
	}
	return String(value);
}
